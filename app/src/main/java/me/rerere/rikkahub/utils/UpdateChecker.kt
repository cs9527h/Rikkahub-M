package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.common.http.await
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request

/** 只认本仓库的正式 Release，绝不把上游 RikkaHub 的版本当成更新。 */
private const val RELEASES_API = "https://api.github.com/repos/cs9527h/Rikkahub-M/releases"
private const val CHECK_INTERVAL_MILLIS = 12L * 60 * 60 * 1000

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data class Failed(val reason: String) : UpdateCheckResult
}

@Serializable
data class ReleaseAsset(val name: String, val size: Long, val url: String)

@Serializable
data class ReleaseNote(
    val tag: String,
    val name: String,
    val body: String,
    val publishedAt: String,
    val prerelease: Boolean,
)

class UpdateChecker(
    private val client: OkHttpClient,
    private val appScope: AppScope,
    private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("update-check", Context.MODE_PRIVATE)
    private val state = MutableStateFlow<UiState<UpdateInfo>>(UiState.Idle)

    val updateState: StateFlow<UiState<UpdateInfo>> = state.asStateFlow()

    init {
        // 启动后异步检查，不阻塞首屏；间隔与“本版不再提醒”都记录在本地。
        appScope.launch {
            runCatching { refresh(force = false) }
        }
    }

    /** 手动点击版本号时调用，忽略间隔限制。 */
    suspend fun checkNow(): UpdateCheckResult = refresh(force = true)

    suspend fun refresh(force: Boolean): UpdateCheckResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (!force && now - last < CHECK_INTERVAL_MILLIS) {
            return@withContext state.value.toResult()
        }
        val request = Request.Builder()
            .url("$RELEASES_API?per_page=10")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Rikkahub-M/${BuildConfig.VERSION_NAME}")
            .build()
        val result = runCatching {
            val body = client.newCall(request).await().use { response ->
                if (!response.isSuccessful) error("检查更新失败：HTTP ${response.code}")
                response.body.string()
            }
            val releases = json.decodeFromString(ListSerializer(ReleaseNote.serializer()), body)
            val latest = releases.firstOrNull { !it.prerelease && Version(it.tag.removePrefix("v")) > Version(BuildConfig.VERSION_NAME) }
            if (latest == null) {
                state.value = UiState.Idle
                UpdateCheckResult.UpToDate
            } else {
                val info = UpdateInfo(
                    version = latest.tag.removePrefix("v"),
                    publishedAt = latest.publishedAt,
                    changelog = latest.body,
                    downloads = releaseDownloads(latest.tag),
                )
                state.value = UiState.Success(info)
                UpdateCheckResult.Available(info)
            }
        }.getOrElse { error ->
            state.value = UiState.Error(error)
            UpdateCheckResult.Failed(error.message ?: "网络不可用")
        }
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        result
    }

    private suspend fun releaseDownloads(tag: String): List<UpdateDownload> = runCatching {
        val request = Request.Builder()
            .url("$RELEASES_API/tags/$tag")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Rikkahub-M/${BuildConfig.VERSION_NAME}")
            .build()
        val body = client.newCall(request).await().use { it.body.string() }
        val assets = json.decodeFromString(ListSerializer(ReleaseAsset.serializer()),
            json.parseToJsonElement(body).jsonObject["assets"].toString())
        assets.filter { it.name.endsWith(".apk", ignoreCase = true) }.map {
            UpdateDownload(name = it.name, url = it.url, size = "${it.size / 1024} KB")
        }
    }.getOrDefault(emptyList())

    private fun UiState<UpdateInfo>.toResult(): UpdateCheckResult = when (this) {
        is UiState.Success -> UpdateCheckResult.Available(data)
        is UiState.Error -> UpdateCheckResult.Failed(error.message ?: "网络不可用")
        else -> UpdateCheckResult.Failed("上次检查未完成")
    }

    /** 更新日志页直接用同一份 GitHub 数据，避免应用里再维护一份会过期的版本列表。 */
    suspend fun releaseNotes(): List<ReleaseNote> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$RELEASES_API?per_page=20")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Rikkahub-M/${BuildConfig.VERSION_NAME}")
            .build()
        val body = client.newCall(request).await().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body.string()
        }
        json.decodeFromString(ListSerializer(ReleaseNote.serializer()), body)
    }

    fun downloadUpdate(context: Context, download: UpdateDownload) {
        runCatching {
            val request = DownloadManager.Request(download.url.toUri()).apply {
                setTitle(download.name)
                setDescription("正在下载更新包...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, download.name)
                setMimeType("application/vnd.android.package-archive")
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
        }.onFailure {
            Toast.makeText(context, "下载失败，正在打开下载页", Toast.LENGTH_SHORT).show()
            context.openUrl("https://github.com/cs9527h/Rikkahub-M/releases")
        }
    }

    private companion object {
        const val KEY_LAST_CHECK = "last_check"
    }
}

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val size: String
)

@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>
)

/**
 * 版本号值类，封装版本号字符串并提供比较功能
 *
 * 支持完整的 SemVer 规范：MAJOR.MINOR.PATCH[-prerelease][+build]
 * - 预发布版本优先级低于正式版：1.0.0-alpha < 1.0.0
 * - 预发布标识符按段逐个比较：数字按数值比较，字符串按字典序比较
 * - 预发布标识符优先级：alpha < beta < rc（通过字典序自然满足）
 * - build metadata（+号后面的部分）不影响优先级比较
 */
@JvmInline
value class Version(val value: String) : Comparable<Version> {

    private fun parse(): ParsedVersion {
        // 去掉 build metadata（+号后面的部分）
        val withoutBuild = value.split("+").first()
        // 分离主版本号和预发布标识符
        val hyphenIndex = withoutBuild.indexOf('-')
        val (coreStr, prereleaseStr) = if (hyphenIndex >= 0) {
            withoutBuild.substring(0, hyphenIndex) to withoutBuild.substring(hyphenIndex + 1)
        } else {
            withoutBuild to null
        }
        val core = coreStr.split(".").map { it.toIntOrNull() ?: 0 }
        val prerelease = prereleaseStr?.split(".")
        return ParsedVersion(core, prerelease)
    }

    override fun compareTo(other: Version): Int {
        val a = this.parse()
        val b = other.parse()

        // 先比较主版本号
        val maxLen = maxOf(a.core.size, b.core.size)
        for (i in 0 until maxLen) {
            val ap = if (i < a.core.size) a.core[i] else 0
            val bp = if (i < b.core.size) b.core[i] else 0
            if (ap != bp) return ap.compareTo(bp)
        }

        // 主版本号相同时比较预发布标识符
        // 有预发布标识符的版本优先级低于没有的：1.0.0-alpha < 1.0.0
        return when {
            a.prerelease == null && b.prerelease == null -> 0
            a.prerelease != null && b.prerelease == null -> -1
            a.prerelease == null && b.prerelease != null -> 1
            else -> comparePrerelease(a.prerelease!!, b.prerelease!!)
        }
    }

    companion object {
        fun compare(version1: String, version2: String): Int {
            return Version(version1).compareTo(Version(version2))
        }

        private fun comparePrerelease(a: List<String>, b: List<String>): Int {
            val maxLen = maxOf(a.size, b.size)
            for (i in 0 until maxLen) {
                // 字段少的优先级更低：1.0.0-alpha < 1.0.0-alpha.1
                if (i >= a.size) return -1
                if (i >= b.size) return 1

                val aNum = a[i].toIntOrNull()
                val bNum = b[i].toIntOrNull()

                val cmp = when {
                    // 都是字：按数值比较
                    aNum != null && bNum != null -> aNum.compareTo(bNum)
                    // 数字优先级低于字符串
                    aNum != null -> -1
                    bNum != null -> 1
                    // 都是字符串：按字典序比较
                    else -> a[i].compareTo(b[i])
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

private data class ParsedVersion(
    val core: List<Int>,
    val prerelease: List<String>?,
)

// 扩展操作符函数，使比较更直观
operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))
