package me.rerere.rikkahub.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 纯下载引擎：多来源、退避重试、Range 断点续传、大小与 SHA-256 校验。
 * 不依赖 Android Context，便于用本地 HTTP 测试服务器做集成测试；
 * 包名/版本/签名等安装校验仍由 [UpdateInstaller] 完成。
 */
/** 续传条件不满足，文件已重置：同一来源从头重下一次是可恢复的。 */
class UpdateRestartException(message: String) : IOException(message)

/** 一个下载源为什么失败，直接展示给用户，避免“下载失败”这种笼统提示。 */
data class UpdateSourceFailure(val label: String, val reason: String)

/** 所有来源都失败：把每个来源的真实原因带回去，界面据此给出可执行建议。 */
class UpdateSourcesException(val failures: List<UpdateSourceFailure>) : IOException(
    failures.joinToString("；") { "${it.label}：${it.reason}" }
)

/** 诊断用：把异常翻译成用户能看懂的一句话。 */
fun describeDownloadError(error: Throwable?): String = when (error) {
    null -> "未知错误"
    is UpdateHttpException -> when (error.code) {
        403 -> "被服务器拒绝（HTTP 403）"
        404 -> "文件不存在（HTTP 404）"
        429 -> "请求过于频繁（HTTP 429）"
        in 500..599 -> "服务器异常（HTTP ${error.code}）"
        else -> "HTTP ${error.code}"
    }
    is javax.net.ssl.SSLException -> "HTTPS 证书校验失败，已按安全策略中止"
    is java.net.UnknownHostException -> "域名解析失败"
    is java.net.SocketTimeoutException -> "连接或读取超时"
    is java.net.ConnectException -> "连接被拒绝或网络不可达"
    is UpdateCheckException -> error.message ?: "文件校验失败"
    is java.io.IOException -> when {
        error.message?.contains("ENOSPC", true) == true ||
            error.message?.contains("No space left", true) == true -> "手机存储空间不足"
        else -> error.message?.take(120) ?: "网络中断"
    }
    else -> error.message?.take(120) ?: error.javaClass.simpleName
}

object UpdateDownloadEngine {

    private const val MAX_TRIES_PER_SOURCE = 2
    private const val META_FILE_SUFFIX = ".meta"

    @Volatile
    private var downloadClient: OkHttpClient? = null

    /** 大文件不能用聊天用的短超时；取消整体超时，保留连接/读取超时，不改动 TLS 校验。 */
    fun clientFor(base: OkHttpClient): OkHttpClient =
        downloadClient ?: base.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            // 连接超时压短：主源被墙时要尽快切到备用源，而不是让用户干等一分多钟
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
            .also { downloadClient = it }

    /**
     * 下载入口**必须在 IO 线程执行**：OkHttp 的 execute()、文件写入与哈希计算都是阻塞调用。
     * 调用方（UpdateChecker.startDownload）运行在 AppScope 的 Main 上，
     * 0.8.2 少了这层 withContext，真机上直接抛 NetworkOnMainThreadException。
     */
    suspend fun download(
        client: OkHttpClient,
        sources: List<UpdateSource>,
        target: File,
        expectedSha256: String?,
        expectedSize: Long? = null,
        version: String? = null,
        onProgress: (read: Long, total: Long, source: String) -> Unit = { _, _, _ -> },
        onVerifying: (source: String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        retryDelayMillis: (attempt: Int) -> Long = { UpdateTransferPolicy.retryDelayMillis(it) },
    ): File = withContext(Dispatchers.IO) {
        downloadBlocking(
            client = client,
            sources = sources,
            target = target,
            expectedSha256 = expectedSha256,
            expectedSize = expectedSize,
            version = version,
            onProgress = onProgress,
            onVerifying = onVerifying,
            isCancelled = isCancelled,
            retryDelayMillis = retryDelayMillis,
        )
    }

    private suspend fun downloadBlocking(
        client: OkHttpClient,
        sources: List<UpdateSource>,
        target: File,
        expectedSha256: String?,
        expectedSize: Long?,
        version: String?,
        onProgress: (read: Long, total: Long, source: String) -> Unit,
        onVerifying: (source: String) -> Unit,
        isCancelled: () -> Boolean,
        retryDelayMillis: (attempt: Int) -> Long,
    ): File {
        require(sources.isNotEmpty()) { "没有可用的下载地址" }
        target.parentFile?.mkdirs()
        val metaFile = File(target.path + META_FILE_SUFFIX)
        val failures = mutableListOf<UpdateSourceFailure>()

        for ((index, source) in sources.withIndex()) {
            val result = attemptSource(
                client = clientFor(client),
                target = target,
                metaFile = metaFile,
                source = source,
                expectedSha256 = expectedSha256,
                expectedSize = expectedSize,
                version = version,
                onProgress = onProgress,
                onVerifying = onVerifying,
                isCancelled = isCancelled,
                retryDelayMillis = retryDelayMillis,
            )
            result.fold(
                onSuccess = { return it },
                onFailure = { error ->
                    if (error is UpdateCancelledException) throw error
                    failures += UpdateSourceFailure(source.label, describeDownloadError(error))
                },
            )
            if (index < sources.lastIndex) onProgress(0, 0, "切换到 ${sources[index + 1].label}")
        }
        throw UpdateSourcesException(failures)
    }

    private suspend fun attemptSource(
        client: OkHttpClient,
        target: File,
        metaFile: File,
        source: UpdateSource,
        expectedSha256: String?,
        expectedSize: Long?,
        version: String?,
        onProgress: (Long, Long, String) -> Unit,
        onVerifying: (String) -> Unit,
        isCancelled: () -> Boolean,
        retryDelayMillis: (Int) -> Long,
    ): Result<File> {
        var lastError: Throwable? = null
        repeat(MAX_TRIES_PER_SOURCE) { attempt ->
            if (isCancelled()) return Result.failure(UpdateCancelledException())
            val result = runCatching {
                transfer(
                    client = client,
                    target = target,
                    metaFile = metaFile,
                    source = source,
                    expectedSha256 = expectedSha256,
                    expectedSize = expectedSize,
                    version = version,
                    onProgress = onProgress,
                    isCancelled = isCancelled,
                )
                onVerifying(source.label)
                verifyFile(target, expectedSha256, expectedSize)
                target
            }
            result.fold(
                onSuccess = { return Result.success(it) },
                onFailure = { error ->
                    when (error) {
                        is UpdateCancelledException -> return Result.failure(error)
                        is UpdateCheckException -> {
                            // 哈希/大小不对：文件不可用，删除后换下一个来源
                            deleteQuietly(target, metaFile)
                            return Result.failure(error)
                        }
                        is UpdateRestartException -> lastError = error
                        is UpdateHttpException -> {
                            lastError = error
                            if (!UpdateTransferPolicy.isRetryable(error.code)) return Result.failure(error)
                        }
                        else -> lastError = error
                    }
                    if (attempt < MAX_TRIES_PER_SOURCE - 1) {
                        delay(retryDelayMillis(attempt))
                    }
                },
            )
        }
        return Result.failure(lastError ?: UpdateCheckException("下载失败"))
    }

    private fun transfer(
        client: OkHttpClient,
        target: File,
        metaFile: File,
        source: UpdateSource,
        expectedSha256: String?,
        expectedSize: Long?,
        version: String?,
        onProgress: (Long, Long, String) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val meta = readMeta(metaFile)
        // 只有来源与版本都对得上才续传：否则拼出来的文件不是这个版本的安装包
        val resumable = meta?.url == source.url &&
            (version.isNullOrBlank() || meta.version.isNullOrBlank() || meta.version == version)
        var resumeFrom = if (resumable) target.length() else 0L
        if (!resumable) deleteQuietly(target, metaFile)
        if (resumeFrom > 0 && !target.exists()) resumeFrom = 0

        val request = Request.Builder()
            .url(source.url)
            .apply {
                if (resumeFrom > 0) {
                    header("Range", "bytes=$resumeFrom-")
                    val validator = meta?.etag ?: meta?.lastModified
                    if (!validator.isNullOrBlank()) header("If-Range", validator)
                }
            }
            .build()

        client.newCall(request).execute().use { response ->
            if (isCancelled()) throw UpdateCancelledException()
            if (response.code == 416 && resumeFrom > 0) {
                // 416 通常意味着本地已经是完整文件：先按发布信息校验，通过就不再重下。
                val complete = runCatching { verifyFile(target, expectedSha256, expectedSize) }.isSuccess
                if (complete) return
                deleteQuietly(target, metaFile)
                throw UpdateRestartException("服务端拒绝续传，已重置下载")
            }
            if (!response.isSuccessful) throw UpdateHttpException(response.code, "")

            val append = response.code == 206
            val total: Long
            if (append) {
                val range = UpdateTransferPolicy.parseContentRange(response.header("Content-Range"))
                    ?: run {
                        deleteQuietly(target, metaFile)
                        throw UpdateRestartException("续传响应缺少有效的 Content-Range")
                    }
                if (UpdateTransferPolicy.resumeStart(range, resumeFrom) == null) {
                    deleteQuietly(target, metaFile)
                    throw UpdateRestartException("续传区间不匹配，已重置下载")
                }
                total = range.total ?: (range.start + response.body.contentLength())
            } else {
                resumeFrom = 0
                target.delete()
                total = response.body.contentLength()
            }

            // 续传元数据必须在写正文之前落盘：第一次下载就被中断时，下次才知道能从哪儿接着下。
            writeMeta(
                metaFile,
                DownloadMeta(source.url, response.header("ETag"), response.header("Last-Modified"), version.orEmpty()),
            )
            FileOutputStream(target, append).use { output ->
                response.body.byteStream().use { input ->
                    var read = resumeFrom
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw UpdateCancelledException()
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        read += count
                        onProgress(read, total, source.label)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }

            if (isCancelled()) throw UpdateCancelledException()
            val written = target.length()
            if (total > 0 && written != total) {
                throw UpdateCheckException("下载不完整（$written/$total 字节），已保留可续传的部分")
            }
        }
    }

    internal data class DownloadMeta(
        val url: String,
        val etag: String?,
        val lastModified: String?,
        /** 这份半截文件属于哪个版本：版本变了（例如切到更新的版本）就不能续传。 */
        val version: String? = null,
    )

    internal fun readMeta(file: File): DownloadMeta? = runCatching {
        if (!file.isFile) return null
        val lines = file.readLines()
        if (lines.firstOrNull().isNullOrBlank()) return null
        DownloadMeta(
            url = lines[0],
            etag = lines.getOrNull(1)?.takeIf { it.isNotBlank() },
            lastModified = lines.getOrNull(2)?.takeIf { it.isNotBlank() },
            version = lines.getOrNull(3)?.takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    private fun writeMeta(file: File, meta: DownloadMeta) {
        runCatching {
            file.writeText(
                listOf(meta.url, meta.etag.orEmpty(), meta.lastModified.orEmpty(), meta.version.orEmpty())
                    .joinToString("\n"),
            )
        }
    }

    internal fun verifyFile(target: File, expectedSha256: String?, expectedSize: Long?) {
        if (!target.isFile || target.length() == 0L) throw UpdateCheckException("下载文件为空")
        val expected = expectedSha256?.lowercase()?.replace(":", "")?.trim()
        if (!expected.isNullOrEmpty()) {
            // 有哈希时以哈希为准：manifest/asset 里的字节数可能是错的，但内容对就是对的
            if (sha256(target) != expected) throw UpdateCheckException("SHA-256 不匹配，已删除损坏文件")
            return
        }
        if (expectedSize != null && expectedSize > 0 && target.length() != expectedSize) {
            throw UpdateCheckException("文件大小与发布信息不一致（${target.length()} ≠ $expectedSize）")
        }
    }

    /** 流式计算，大文件不占内存；测试与安装前校验共用。 */
    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").apply {
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                update(buffer, 0, count)
            }
        }
    }.digest().joinToString("") { "%02x".format(it) }

    private fun deleteQuietly(vararg files: File) {
        files.forEach { runCatching { if (it.exists()) it.delete() } }
    }

    /** 下载中断后用于界面恢复：返回已下载字节数、来源与版本。 */
    fun partialState(target: File): Triple<Long, String?, String?> {
        if (!target.isFile) return Triple(0L, null, null)
        val meta = readMeta(File(target.path + META_FILE_SUFFIX))
        return Triple(target.length(), meta?.url, meta?.version)
    }

    fun isRetryableTransport(error: Throwable): Boolean = error is IOException && error !is UpdateCheckException
}
