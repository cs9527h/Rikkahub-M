package me.rerere.rikkahub.utils

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

private const val REPO = "cs9527h/Rikkahub-M"
private const val API_RELEASES = "https://api.github.com/repos/$REPO/releases"
private const val ATOM_FEED = "https://github.com/$REPO/releases.atom"
private const val CHECK_INTERVAL_MILLIS = 12L * 60 * 60 * 1000

/** 国内备用更新源，静态文件由 scripts/publish_update_mirror.sh 上传；可在设置里改。 */
const val DEFAULT_MIRROR_BASE = "https://8.133.167.195/updates"

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data class Failed(val reason: String) : UpdateCheckResult
}

/** GitHub REST 字段名和 Kotlin 属性名不同，必须显式映射，否则解析必然失败。 */
@Serializable
data class ReleaseAsset(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val url: String = "",
)

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tag: String = "",
    val name: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    val body: String = "",
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<ReleaseAsset> = emptyList(),
)

/** 更新日志条目：GitHub、备用源、内置说明三处共用同一结构。 */
@Serializable
data class ReleaseNote(
    val version: String,
    val tag: String = "v$version",
    val title: String = "",
    val body: String = "",
    val publishedAt: String = "",
    val prerelease: Boolean = false,
    val apkUrl: String? = null,
    val sha256: String? = null,
    val apkSize: Long? = null,
    val pageUrl: String = "https://github.com/$REPO/releases/tag/$tag",
    val source: String = "github",
)

@Serializable
data class MirrorManifest(
    val format: Int = 1,
    val updatedAt: String = "",
    val releases: List<ReleaseNote> = emptyList(),
)

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val size: String,
    val sha256: String? = null,
)

@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>,
    val tag: String = "v$version",
    val pageUrl: String = "https://github.com/$REPO/releases/tag/$tag",
    val prerelease: Boolean = false,
)

class UpdateChecker(
    private val client: OkHttpClient,
    private val appScope: AppScope,
    private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }
    private val prefs = context.getSharedPreferences("update-check", Context.MODE_PRIVATE)
    private val state = MutableStateFlow<UiState<UpdateInfo>>(UiState.Idle)
    private val lastError = MutableStateFlow<String?>(null)
    private var fetchFailure: String? = null

    val updateState: StateFlow<UiState<UpdateInfo>> = state.asStateFlow()

    /** 最近一次检查失败原因，供“检查更新”展示，不覆盖已有的成功数据。 */
    val lastFailure: StateFlow<String?> = lastError.asStateFlow()

    private val cacheFile = File(context.filesDir, "update-cache.json")

    init {
        appScope.launch { runCatching { refresh(force = false) } }
    }

    suspend fun checkNow(): UpdateCheckResult = refresh(force = true)

    suspend fun refresh(force: Boolean): UpdateCheckResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (!force && now - last < CHECK_INTERVAL_MILLIS) {
            return@withContext state.value.toResult()
        }
        fetchFailure = null
        val notes = fetchReleases()
        val result: UpdateCheckResult = if (notes == null) {
            val reason = fetchFailure ?: "网络不可用"
            lastError.value = reason
            state.value = UiState.Error(UpdateCheckException(reason))
            UpdateCheckResult.Failed(reason)
        } else {
            lastError.value = null
            saveCache(notes)
            val latestStable = UpdateParsing.latestStable(notes, BuildConfig.VERSION_NAME)
            if (latestStable != null) {
                val info = UpdateInfo(
                    version = latestStable.version,
                    publishedAt = latestStable.publishedAt,
                    changelog = latestStable.body,
                    downloads = downloadsOf(latestStable),
                    tag = latestStable.tag,
                    pageUrl = latestStable.pageUrl,
                )
                state.value = UiState.Success(info)
                UpdateCheckResult.Available(info)
            } else {
                // 自动检查只认正式版；手动点“检查更新”时把预览版也如实报出来。
                val latestPreview = notes.filter { it.prerelease }
                    .maxByOrNull { Version(it.version) }
                    ?.takeIf { Version(it.version) > Version(BuildConfig.VERSION_NAME) }
                if (force && latestPreview != null) {
                    val info = UpdateInfo(
                        version = latestPreview.version,
                        publishedAt = latestPreview.publishedAt,
                        changelog = latestPreview.body,
                        downloads = downloadsOf(latestPreview),
                        tag = latestPreview.tag,
                        pageUrl = latestPreview.pageUrl,
                        prerelease = true,
                    )
                    state.value = UiState.Idle
                    UpdateCheckResult.Available(info)
                } else {
                    state.value = UiState.Idle
                    UpdateCheckResult.UpToDate
                }
            }
        }
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        result
    }

    /** 依次尝试 GitHub API、GitHub Atom（不吃 API 限流）、国内备用源、上次成功缓存；全失败返回 null。 */
    private suspend fun fetchReleases(): List<ReleaseNote>? {
        val failures = mutableListOf<String>()
        try {
            return apiReleases()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failures += "GitHub 接口：" + e.describeNetworkFailure()
        }
        try {
            val atom = atomReleases()
            if (atom.isNotEmpty()) return atom
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failures += "GitHub 发布页：" + e.describeNetworkFailure()
        }
        try {
            val mirror = mirrorReleases()
            if (mirror.isNotEmpty()) return mirror
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failures += "备用源：" + e.describeNetworkFailure()
        }
        try {
            val cached = loadCache()
            if (cached.isNotEmpty()) return cached
        } catch (e: Exception) {
            failures += "本地缓存不可用"
        }
        fetchFailure = failures.joinToString("；")
        return null
    }

    private suspend fun apiReleases(): List<ReleaseNote> {
        val body = httpGet("$API_RELEASES?per_page=20")
        return UpdateParsing.githubReleases(json, body)
    }

    /** releases.atom 不需要 token，也不受 API 限流影响，是 403 时最实用的回退。 */
    private suspend fun atomReleases(): List<ReleaseNote> =
        UpdateParsing.atomReleases(httpGet(ATOM_FEED, accept = "application/atom+xml"))

    private suspend fun mirrorReleases(): List<ReleaseNote> =
        UpdateParsing.manifest(json, httpGet("${mirrorBase()}/manifest.json"), mirrorBase())

    private suspend fun loadCache(): List<ReleaseNote> = withContext(Dispatchers.IO) {
        if (!cacheFile.exists()) return@withContext emptyList()
        json.decodeFromString(ListSerializer(ReleaseNote.serializer()), cacheFile.readText())
    }

    private suspend fun saveCache(notes: List<ReleaseNote>) = withContext(Dispatchers.IO) {
        runCatching { cacheFile.writeText(json.encodeToString(ListSerializer(ReleaseNote.serializer()), notes)) }
        Unit
    }

    private fun downloadsOf(note: ReleaseNote): List<UpdateDownload> {
        val name = apkName(note.version)
        val url = note.apkUrl ?: "https://github.com/$REPO/releases/download/${note.tag}/$name"
        return listOf(
            UpdateDownload(
                name = name,
                url = url,
                size = note.apkSize?.let { "${it / 1024} KB" } ?: "universal APK",
                sha256 = note.sha256,
            )
        )
    }

    /** 更新日志：内置当前版本说明始终可读，联网后合并已发布的正式/预览版本。 */
    suspend fun releaseNotes(): List<ReleaseNote> = withContext(Dispatchers.IO) {
        val bundled: List<ReleaseNote> = bundledNotes()
        val remote: List<ReleaseNote> = fetchReleases() ?: emptyList()
        bundled.plus(remote)
            .distinctBy { note -> note.version }
            .sortedByDescending { note -> Version(note.version) }
    }

    fun bundledNotes(): List<ReleaseNote> = runCatching {
        val text = context.assets.open("updates.json").bufferedReader().use { it.readText() }
        json.decodeFromString(MirrorManifest.serializer(), text).releases
    }.getOrNull() ?: emptyList()

    /** 下载 → 校验 SHA-256/包名/版本号/签名 → 交给系统安装器确认，绝不静默安装。 */
    suspend fun installUpdate(info: UpdateInfo): Result<File> = withContext(Dispatchers.IO) {
        val download = info.downloads.firstOrNull()
            ?: return@withContext Result.failure(UpdateCheckException("该版本没有可下载的安装包"))
        val result = UpdateInstaller.downloadAndVerify(
            context = context,
            client = client,
            url = download.url,
            expectedSha256 = download.sha256,
        )
        result.onSuccess { file ->
            UpdateInstaller.startSystemInstall(context, file)
        }.onFailure { error ->
            // 备用源不可达时，退回到 GitHub 发布页，让用户手动下载。
            runCatching { context.openUrl(info.pageUrl) }
            Toast.makeText(context, "更新包校验或下载失败：" + (error.message ?: "未知错误"), Toast.LENGTH_LONG).show()
        }
        result
    }

    /** 兼容旧调用点：聊天页更新卡片点下载时也走校验流程。 */
    fun downloadUpdate(context: Context, download: UpdateDownload) {
        appScope.launch {
            val info = UpdateInfo(
                version = download.name.removePrefix("Rikkahub-M-").removeSuffix(".apk"),
                publishedAt = "",
                changelog = "",
                downloads = listOf(download),
            )
            installUpdate(info)
        }
    }

    private fun mirrorBase(): String =
        prefs.getString(KEY_MIRROR_BASE, DEFAULT_MIRROR_BASE)?.trimEnd('/') ?: DEFAULT_MIRROR_BASE

    private suspend fun httpGet(url: String, accept: String = "application/vnd.github+json"): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", "Rikkahub-M/${BuildConfig.VERSION_NAME}")
            .build()
        return client.newCall(request).await().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw UpdateHttpException(response.code, body)
            body
        }
    }

    private fun Throwable.describeNetworkFailure(): String = when (this) {
        is UpdateHttpException -> describeHttp(code, body)
        is java.net.UnknownHostException -> "无法解析域名，请检查网络"
        is java.net.SocketTimeoutException -> "连接超时"
        else -> message ?: this::class.simpleName ?: "网络错误"
    }

    private fun describeHttp(code: Int, body: String): String = when {
        code == 403 && body.contains("rate limit", ignoreCase = true) -> "GitHub 接口限流（403）"
        code == 403 -> "GitHub 拒绝访问（403），可能是网络限制"
        code == 429 -> "请求过于频繁（429）"
        code == 404 -> "发布记录不存在（404）"
        code in 500..599 -> "GitHub 服务异常（$code）"
        else -> "HTTP $code"
    }

    private fun UiState<UpdateInfo>.toResult(): UpdateCheckResult = when (this) {
        is UiState.Success -> UpdateCheckResult.Available(data)
        is UiState.Error -> UpdateCheckResult.Failed(error.message ?: "上次检查失败")
        else -> UpdateCheckResult.Failed("上次检查未完成")
    }

    private fun apkName(version: String) = UpdateParsing.apkName(version)

    private companion object {
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_MIRROR_BASE = "mirror_base"

    }
}

class UpdateCheckException(message: String) : Exception(message)

class UpdateHttpException(val code: Int, val body: String) : Exception("HTTP $code")
