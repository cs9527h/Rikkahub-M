package me.rerere.rikkahub.utils

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
    /** GitHub 新 API 直接给出 "sha256:..."，有它就不必再猜。 */
    val digest: String = "",
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
    /** 备用源 manifest 里的别名（老版本只认 apkUrl，兼容两种写法）。 */
    @SerialName("url") val directUrl: String? = null,
    @SerialName("size") val exactSize: Long? = null,
    val pageUrl: String = "https://github.com/$REPO/releases/tag/$tag",
    val source: String = "github",
    /** 弹窗用的一两行摘要；留空时从 body 提取。 */
    val summary: String = "",
    /** 置顶的「精选更新」：只用于真实正式版本。 */
    val featured: Boolean = false,
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
    /** 精确字节数（来自 GitHub asset 或镜像 manifest），用于下载完整性校验。 */
    val bytes: Long = 0,
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
    val summary: String = "",
    /** 置顶的「精选更新」：只在真实正式版本上使用，不用于计划或预告。 */
    val featured: Boolean = false,
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

    private val downloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)

    /** 上次中断留下的半截安装包；界面据此提示「继续上次下载」。 */
    private val partialBytes = MutableStateFlow(0L)
    val partialDownloadBytes: StateFlow<Long> = partialBytes.asStateFlow()
    private var downloadJob: Job? = null
    private var activeInfo: UpdateInfo? = null

    @Volatile
    private var cancelRequested = false

    /** 当前下载进行到哪一步；失败时写进状态与日志，便于用户反馈「卡在哪」。 */
    @Volatile
    private var lastStage: UpdateStage = UpdateStage.PREPARE

    /** 下载/校验的统一状态；界面、通知栏、聊天页卡片都读这一份。 */
    val download: StateFlow<UpdateDownloadState> = downloadState.asStateFlow()

    /** 通知不可用等系统级问题的提示；非空时界面必须如实告知，而不是假装通知栏正常。 */
    private val notificationProblemState = MutableStateFlow<String?>(null)
    val notificationProblem: StateFlow<String?> = notificationProblemState.asStateFlow()

    /** 前台服务/通知被拒绝时由下载服务回填。 */
    fun reportNotificationProblem(message: String?) {
        notificationProblemState.value = message
    }

    /** 正在下载/待安装的版本信息，供界面从后台下载状态重新打开进度。 */
    val targetInfo: UpdateInfo? get() = activeInfo

    private val cacheFile = File(context.filesDir, "update-cache.json")

    init {
        // 构造发生在 Koin 注入处（可能就是主线程）：清理残留安装包、统计半截文件大小都是文件 IO，
        // 统一丢到 IO 线程执行，主线程只保留构造本身；检查更新复用同一条后台任务。
        appScope.launch(Dispatchers.IO) {
            pruneStaleDownloads()
            partialBytes.value = runCatching {
                File(File(context.cacheDir, "updates"), "Rikkahub-M-update.apk")
                    .takeIf { it.isFile }?.length() ?: 0L
            }.getOrDefault(0L)
            runCatching { refresh(force = false) }
        }
    }

    /** 清理上次没下完或校验失败的残留安装包，避免占着缓存却不会被用到。 */
    private fun pruneStaleDownloads() {
        val dir = File(context.cacheDir, "updates")
        val cutoff = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) file.delete()
            }
        }
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
                ?.let { ensureSha256(it) }
            if (latestStable != null) {
                val info = UpdateInfo(
                    version = latestStable.version,
                    publishedAt = latestStable.publishedAt,
                    changelog = UpdateNotes.userFacing(latestStable.body).ifBlank { latestStable.body },
                    downloads = downloadsOf(latestStable),
                    summary = latestStable.summary.ifBlank { UpdateNotes.summary(latestStable.body) },
                    tag = latestStable.tag,
                    pageUrl = latestStable.pageUrl,
                    featured = latestStable.featured,
                )
                state.value = UiState.Success(info)
                UpdateCheckResult.Available(info)
            } else {
                // 自动检查只认正式版；手动点“检查更新”时把预览版也如实报出来。
                val latestPreview = notes.filter { it.prerelease }
                    .maxByOrNull { Version(it.version) }
                    ?.takeIf { Version(it.version) > Version(BuildConfig.VERSION_NAME) }
                    ?.let { ensureSha256(it) }
                if (force && latestPreview != null) {
                    val info = UpdateInfo(
                        version = latestPreview.version,
                        publishedAt = latestPreview.publishedAt,
                        changelog = UpdateNotes.userFacing(latestPreview.body).ifBlank { latestPreview.body },
                        downloads = downloadsOf(latestPreview),
                        summary = latestPreview.summary.ifBlank { UpdateNotes.summary(latestPreview.body) },
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

    /**
     * GitHub API 不一定给 digest，正式包旁边却有 SHA256SUMS.txt。
     * 有哈希才谈得上“下载完整性校验”，所以这里补一次，失败就如实退化为只校验大小。
     */
    private suspend fun ensureSha256(note: ReleaseNote): ReleaseNote {
        if (!note.sha256.isNullOrBlank()) return note
        val sumsUrl = "https://github.com/$REPO/releases/download/${note.tag.ifBlank { "v${note.version}" }}/SHA256SUMS.txt"
        return runCatching {
            val body = httpGet(sumsUrl, accept = "text/plain")
            val expected = apkName(note.version)
            body.lineSequence()
                .map { it.trim().split(Regex("\\s+")) }
                .firstOrNull { it.size >= 2 && it.last().removePrefix("*").equals(expected, ignoreCase = true) }
                ?.firstOrNull()
                ?.lowercase()
                ?.takeIf { it.length == 64 }
                ?.let { note.copy(sha256 = it) }
                ?: note
        }.getOrDefault(note)
    }

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
                bytes = note.apkSize ?: 0,
            )
        )
    }

    /** 更新日志：内置当前版本说明始终可读，联网后合并已发布的正式/预览版本。 */
    suspend fun releaseNotes(): List<ReleaseNote> = withContext(Dispatchers.IO) {
        val bundled: List<ReleaseNote> = bundledNotes()
        val remote: List<ReleaseNote> = (fetchReleases() ?: emptyList()).map { note ->
            // GitHub Release 正文是技术发布说明：只抽出四类里用户可读的条目，
            // 抽不出来（纯技术文档）就留空，绝不让开发者细节整段进页面。
            note.copy(
                body = UpdateNotes.userFacing(note.body),
                summary = note.summary.ifBlank { UpdateNotes.summary(note.body) },
            )
        }
        // 内置条目优先：同一版本以 assets/updates.json 的精简文案为准，联网刷新不覆盖它
        bundled.plus(remote)
            .distinctBy { note -> note.version }
            .sortedByDescending { note -> Version(note.version) }
    }

    /**
     * 读 assets 里的内置更新说明：属于文件 IO，因此做成 suspend 并在 IO 上执行，
     * 避免哪天从 Compose 里直接调用时把磁盘读留在主线程。
     */
    suspend fun bundledNotes(): List<ReleaseNote> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.assets.open("updates.json").bufferedReader().use { it.readText() }
            json.decodeFromString(MirrorManifest.serializer(), text).releases
        }.getOrNull() ?: emptyList()
    }

    /**
     * 开始下载更新包。已在下载则不重复开始，避免并发和多份半截文件。
     * 进度与校验都写进 [download]，界面关掉再打开也能接着看。
     *
     * 入口本身仍是普通函数（由 Compose 点击回调在主线程调用），但**只做**三件事：
     * 置状态、启动前台服务、把真正的下载丢进 `withContext(Dispatchers.IO)`。
     * AppScope 的 dispatcher 是 Dispatchers.Main（见 RikkaHubApp.AppScope），所以
     * 「构造下载源 → 下载 → 哈希校验 → 记住可用源」必须整段显式切到 IO：
     *   - downloadSources()/mirrorBase() 读 SharedPreferences（文件 IO）
     *   - downloadAndVerify() 走 OkHttp 与文件写入
     * 将来任何新增阻塞调用都要放进 [downloadInBackground] 的 IO 块里；
     * 放错位置会被 UpdateMainThreadSourceGuardTest 当场判失败。
     */
    fun startDownload(info: UpdateInfo) {
        if (downloadJob?.isActive == true) return
        val download = info.downloads.firstOrNull()
        if (download == null) {
            downloadState.value = UpdateDownloadState.Failed(info.version, "该版本没有可下载的安装包")
            return
        }
        activeInfo = info
        cancelRequested = false
        lastStage = UpdateStage.PREPARE
        downloadState.value = UpdateDownloadState.Preparing(info.version)
        // 前台服务必须在用户点击这一刻（应用仍在前台）启动：Android 12+ 从后台启动会被系统拒绝。
        // 这里只有 binder 调用，没有网络/文件读。
        if (!UpdateDownloadService.start(context)) {
            reportNotificationProblem("系统未允许下载通知（前台服务启动失败），进度仅在应用内显示")
        }
        downloadJob = appScope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) { downloadInBackground(info, download) }
            } catch (e: CancellationException) {
                // 用户在下载中被取消：结果由 cancelDownload() 置为 Idle，这里不再覆盖
                null
            }
            publishDownloadOutcome(info, outcome)
        }
    }

    /** 只在 Dispatchers.IO 上执行：构造来源、下载、校验、记录可用源，全都不许碰主线程。 */
    private suspend fun downloadInBackground(info: UpdateInfo, download: UpdateDownload): DownloadOutcome {
        var lastEmit = 0L
        var activeSource = ""
        // 检查更新与下载分离：无论刚才从 GitHub、atom 还是备用源拿到的版本信息，
        // 下载都按「GitHub 正式附件 → 已验证备用源」的顺序尝试，避免检查成功却下载必然失败。
        val sources = downloadSources(info, download)
        val result = UpdateInstaller.downloadAndVerify(
            context = context,
            client = client,
            sources = sources,
            expectedSha256 = download.sha256,
            expectedSize = download.bytes,
            version = info.version,
            onProgress = { read, total, source ->
                if (source.isNotBlank() && !source.startsWith("切换")) activeSource = source
                val now = SystemClock.elapsedRealtime()
                if (now - lastEmit >= 120 || (total > 0 && read >= total)) {
                    lastEmit = now
                    // StateFlow 赋值线程安全，不需要回主线程
                    downloadState.value = UpdateDownloadState.Running(info.version, read, total, source)
                }
            },
            onVerifying = { source ->
                if (source.isNotBlank()) activeSource = source
                downloadState.value = UpdateDownloadState.Verifying(info.version, source)
            },
            onStage = { stage -> lastStage = stage },
            isCancelled = { cancelRequested },
        )
        if (result.isSuccess) {
            val goodUrl = sources.firstOrNull { it.label == activeSource }?.url
            if (!goodUrl.isNullOrBlank()) rememberGoodSource(activeSource, goodUrl)
        }
        return DownloadOutcome(result.getOrNull(), result.exceptionOrNull())
    }

    /** 收尾只保留状态发布这一件事，放在 Main 上做，避免 IO 段与界面状态互相覆盖。 */
    private fun publishDownloadOutcome(info: UpdateInfo, outcome: DownloadOutcome?) {
        downloadJob = null
        if (outcome == null) {
            if (cancelRequested) downloadState.value = UpdateDownloadState.Idle
            return
        }
        val apk = outcome.apk
        val error = outcome.error
        downloadState.value = when {
            apk != null -> UpdateDownloadState.Ready(info.version, apk)
            cancelRequested -> UpdateDownloadState.Idle
            error is CancellationException -> UpdateDownloadState.Idle
            else -> {
                // 阶段 + 异常类型写进日志：用户反馈「下载失败」时能直接定位是连接、写盘还是校验
                Log.w(
                    TAG,
                    "更新下载失败 阶段=${lastStage.label} 版本=${info.version} " +
                        "错误=${error?.javaClass?.name}: ${error?.message}",
                )
                UpdateDownloadState.Failed(
                    info.version,
                    "${describeDownloadFailure(error)}（失败阶段：${lastStage.label}）",
                    info.pageUrl,
                )
            }
        }
    }

    /** 一次下载的最终结果；apk 非空即成功。 */
    private data class DownloadOutcome(val apk: File?, val error: Throwable?)

    /**
     * 下载顺序：上次成功过的来源 → 已验证的国内备用源 → GitHub 正式附件 → 检查结果里的原地址。
     *
     * 真实反馈是 GitHub 附件在国内经常失败：把备用源放在前面，主源只作为兜底，
     * 不再让用户先干等 GitHub 连接超时。所有来源都必须是 HTTPS。
     */
    private fun downloadSources(info: UpdateInfo, download: UpdateDownload): List<UpdateSource> {
        val github = "https://github.com/$REPO/releases/download/${info.tag.ifBlank { "v${info.version}" }}/${download.name}"
        val mirror = "${mirrorBase().trimEnd('/')}/${download.name}"
        val mirrorSource = UpdateSource("备用源", mirror)
        val githubSource = UpdateSource(
            "GitHub",
            if (download.url.startsWith("https://github.com", ignoreCase = true)) download.url else github,
        )
        val lastGood = prefs.getString(KEY_LAST_GOOD_SOURCE, null)
        val preferred = listOfNotNull(
            mirrorSource.takeIf { it.url == lastGood },
            githubSource.takeIf { it.url == lastGood },
        )
        val ordered = preferred + listOf(mirrorSource, githubSource, UpdateSource("原地址", download.url))
        return ordered
            .filter { it.url.startsWith("https://", ignoreCase = true) }
            .distinctBy { it.url }
    }

    private fun rememberGoodSource(label: String, url: String) {
        prefs.edit().putString(KEY_LAST_GOOD_SOURCE, url).putString(KEY_LAST_GOOD_LABEL, label).apply()
    }

    private fun describeDownloadFailure(error: Throwable?): String {
        if (isNetworkOnMainThreadError(error)) {
            // 更新机制自身的缺陷：网络调用落到了主线程，必须让用户回报，而不是当成网络问题
            return "更新下载误在主线程发起网络请求，已被系统拦截（内部错误，请反馈版本号与日志）"
        }
        val raw = error?.message?.takeIf { it.isNotBlank() } ?: return "下载失败，请检查网络后重试"
        return when (error) {
            is UpdateSourcesException -> {
                // 把每个来源的真实原因都列出来，用户才知道是哪一段网络坏了
                "多个下载源都失败：" + error.failures.joinToString("；") { "${it.label}：${it.reason}" } +
                    "。可以稍后重试，或改用浏览器下载。"
            }
            is UpdateHttpException -> when (error.code) {
                403 -> "下载被拒绝（403），可能被网络环境拦截；请改用浏览器下载"
                404 -> "安装包不存在（404），请稍后再试或到浏览器下载"
                429 -> "请求过于频繁（429），请稍后重试"
                in 500..599 -> "服务器异常（${error.code}），请稍后重试"
                else -> "下载失败（HTTP ${error.code}）"
            }
            is javax.net.ssl.SSLException -> "HTTPS 证书校验失败，已中止下载（不会关闭证书校验）"
            is java.net.UnknownHostException -> "无法连接下载服务器（域名解析失败）"
            is java.net.SocketTimeoutException -> "下载超时，已保留进度，可点击重试续传"
            is java.io.IOException -> when {
                raw.contains("ENOSPC", ignoreCase = true) || raw.contains("No space left", ignoreCase = true) ->
                    "手机存储空间不足，安装包没有下完；清理空间后可以重试"
                else -> "网络中断（$raw），已保留进度，可重试续传"
            }
            else -> raw
        }
    }

    /** 取消下载：停掉协程，保留已下载部分（下次同一来源可续传），状态回到空闲。 */
    fun cancelDownload() {
        cancelRequested = true
        downloadJob?.cancel()
        downloadJob = null
        UpdateDownloadService.stop(context)
        downloadState.value = UpdateDownloadState.Idle
    }

    /**
     * 失败后重试同一版本：只对失败/空闲状态生效，重复点击不会叠起两个下载。
     * 进程重启后 activeInfo 为空时，先重新检查一次，再决定是否开始。
     */
    fun retryDownload() {
        val info = activeInfo
        if (info != null) {
            if (downloadState.value is UpdateDownloadState.Running ||
                downloadState.value is UpdateDownloadState.Verifying
            ) {
                return
            }
            startDownload(info)
            return
        }
        appScope.launch {
            val result = refresh(force = true)
            if (result is UpdateCheckResult.Available) startDownload(result.info)
        }
    }

    /** 安装器已经接管（或用户放弃）后清空状态，避免残留 Ready 反复拉起安装。 */
    fun clearDownload() {
        if (downloadJob?.isActive == true) return
        downloadState.value = UpdateDownloadState.Idle
    }

    /** 兼容旧入口：聊天页更新卡片点击下载时也走同一套下载与校验。 */
    fun downloadUpdate(context: Context, download: UpdateDownload) {
        val info = UpdateInfo(
            version = download.name.removePrefix("Rikkahub-M-").removeSuffix(".apk"),
            publishedAt = "",
            changelog = "",
            downloads = listOf(download),
        )
        startDownload(info)
    }

    private fun mirrorBase(): String =
        prefs.getString(KEY_MIRROR_BASE, DEFAULT_MIRROR_BASE)?.trimEnd('/') ?: DEFAULT_MIRROR_BASE

    /**
     * 更新检查的所有 HTTP 读取都在 Dispatchers.IO 上完成，且用同步 execute() 拿完整响应。
     *
     * 这里**故意不用** `Call.await()`：await 只把 enqueue 交给 OkHttp 线程，协程恢复后
     * 仍回到调用方调度器，紧接着的 `response.body.string()` 会在那个线程上真正读 socket ——
     * 调用方是 Main 时就是 NetworkOnMainThreadException（0.8.2/0.8.5 的真机报错）。
     * 把请求、读正文放在同一个 IO 块里，调用方在哪个线程调用都安全。
     */
    private suspend fun httpGet(url: String, accept: String = "application/vnd.github+json"): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Accept", accept)
                .header("User-Agent", "Rikkahub-M/${BuildConfig.VERSION_NAME}")
                .build()
            client.newCall(request).execute().use { response ->
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

    /** 同一版本只提示一次；主动「跳过此版本」后连卡片也不再出现。 */
    fun shouldPrompt(version: String): Boolean =
        prefs.getString(KEY_PROMPTED_VERSION, null) != version &&
            prefs.getString(KEY_IGNORED_VERSION, null) != version

    fun markPrompted(version: String) {
        prefs.edit().putString(KEY_PROMPTED_VERSION, version).apply()
    }

    /** 用户明确跳过某个版本：不再提示，但在「关于」页仍能看到并手动检查。 */
    fun ignoreVersion(version: String) {
        prefs.edit()
            .putString(KEY_IGNORED_VERSION, version)
            .putString(KEY_PROMPTED_VERSION, version)
            .apply()
    }

    fun ignoredVersion(): String? = prefs.getString(KEY_IGNORED_VERSION, null)

    private fun apkName(version: String) = UpdateParsing.apkName(version)

    /** 从更新日志里挑最前面的几条要点做弹窗摘要，最多三条、不超过 120 字。 */
    private fun summarize(body: String): String {
        val bullets = body.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") || it.startsWith("* ") }
            .map { it.removePrefix("- ").removePrefix("* ").replace("**", "").trim() }
            .filter { it.isNotEmpty() }
            .take(3)
            .toList()
        if (bullets.isNotEmpty()) return bullets.joinToString(" · ").take(120)
        return body.lineSequence()
            .map { it.trim().removePrefix("#").trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.take(120)
            ?: ""
    }

    private companion object {
        const val TAG = "UpdateChecker"
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_PROMPTED_VERSION = "prompted_version"
        const val KEY_IGNORED_VERSION = "ignored_version"
        const val KEY_MIRROR_BASE = "mirror_base"
        const val KEY_LAST_GOOD_SOURCE = "last_good_source"
        const val KEY_LAST_GOOD_LABEL = "last_good_label"

    }
}

class UpdateCheckException(message: String) : Exception(message)

class UpdateHttpException(val code: Int, val body: String) : Exception("HTTP $code")
