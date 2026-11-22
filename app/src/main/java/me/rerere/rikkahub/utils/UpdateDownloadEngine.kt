package me.rerere.rikkahub.utils

import kotlinx.coroutines.delay
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

object UpdateDownloadEngine {

    private const val MAX_TRIES_PER_SOURCE = 3
    private const val META_FILE_SUFFIX = ".meta"

    @Volatile
    private var downloadClient: OkHttpClient? = null

    /** 大文件不能用聊天用的短超时；取消整体超时，保留连接/读取超时，不改动 TLS 校验。 */
    fun clientFor(base: OkHttpClient): OkHttpClient =
        downloadClient ?: base.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
            .also { downloadClient = it }

    suspend fun download(
        client: OkHttpClient,
        sources: List<UpdateSource>,
        target: File,
        expectedSha256: String?,
        expectedSize: Long? = null,
        onProgress: (read: Long, total: Long, source: String) -> Unit = { _, _, _ -> },
        onVerifying: (source: String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        retryDelayMillis: (attempt: Int) -> Long = { UpdateTransferPolicy.retryDelayMillis(it) },
    ): File {
        require(sources.isNotEmpty()) { "没有可用的下载地址" }
        target.parentFile?.mkdirs()
        val metaFile = File(target.path + META_FILE_SUFFIX)
        var lastError: Throwable? = null

        for ((index, source) in sources.withIndex()) {
            val result = attemptSource(
                client = clientFor(client),
                target = target,
                metaFile = metaFile,
                source = source,
                expectedSha256 = expectedSha256,
                expectedSize = expectedSize,
                onProgress = onProgress,
                onVerifying = onVerifying,
                isCancelled = isCancelled,
                retryDelayMillis = retryDelayMillis,
            )
            result.fold(
                onSuccess = { return it },
                onFailure = { error ->
                    if (error is UpdateCancelledException) throw error
                    lastError = error
                },
            )
            if (index < sources.lastIndex) onProgress(0, 0, "切换 ${sources[index + 1].label}")
        }
        throw lastError ?: UpdateCheckException("下载失败")
    }

    private suspend fun attemptSource(
        client: OkHttpClient,
        target: File,
        metaFile: File,
        source: UpdateSource,
        expectedSha256: String?,
        expectedSize: Long?,
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
        onProgress: (Long, Long, String) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val meta = readMeta(metaFile)
        val resumable = meta?.url == source.url
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
            writeMeta(metaFile, DownloadMeta(source.url, response.header("ETag"), response.header("Last-Modified")))
        }
    }

    internal data class DownloadMeta(val url: String, val etag: String?, val lastModified: String?)

    internal fun readMeta(file: File): DownloadMeta? = runCatching {
        if (!file.isFile) return null
        val lines = file.readLines()
        if (lines.firstOrNull().isNullOrBlank()) return null
        DownloadMeta(
            url = lines[0],
            etag = lines.getOrNull(1)?.takeIf { it.isNotBlank() },
            lastModified = lines.getOrNull(2)?.takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    private fun writeMeta(file: File, meta: DownloadMeta) {
        runCatching {
            file.writeText(listOf(meta.url, meta.etag.orEmpty(), meta.lastModified.orEmpty()).joinToString("\n"))
        }
    }

    internal fun verifyFile(target: File, expectedSha256: String?, expectedSize: Long?) {
        if (!target.isFile || target.length() == 0L) throw UpdateCheckException("下载文件为空")
        if (expectedSize != null && expectedSize > 0 && target.length() != expectedSize) {
            throw UpdateCheckException("文件大小与发布信息不一致（${target.length()} ≠ $expectedSize）")
        }
        val expected = expectedSha256?.lowercase()?.replace(":", "")?.trim()
        if (expected.isNullOrEmpty()) return
        if (sha256(target) != expected) throw UpdateCheckException("SHA-256 不匹配，已删除损坏文件")
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

    /** 下载中断后用于界面恢复：返回已下载字节数与对应来源。 */
    fun partialState(target: File): Pair<Long, String?> {
        if (!target.isFile) return 0L to null
        val source = readMeta(File(target.path + META_FILE_SUFFIX))?.url
        return target.length() to source
    }

    fun isRetryableTransport(error: Throwable): Boolean = error is IOException && error !is UpdateCheckException
}
