package me.rerere.rikkahub.utils

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * 用本地 HTTP 测试服务器复现下载故障，不依赖真实网络：
 * 200 全量、206 续传、服务端忽略 Range、416、403、500 重试、Content-Range 非法、
 * 缺 Content-Length、大小不符、SHA-256 失败、取消、连接失败。
 */
class UpdateDownloadEngineTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var dir: File
    private val client = OkHttpClient()

    private val payload = ByteArray(2000) { (it % 251).toByte() }
    private val payloadSha = sha256Of(payload)

    private var handler: (HttpExchange) -> Unit = {}

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("update-test").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/apk") { exchange -> handler(exchange) }
        server.start()
        base = "http://127.0.0.1:${server.address.port}/apk"
    }

    @After
    fun tearDown() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun ok(exchange: HttpExchange, bytes: ByteArray, etag: String = "\"v1\"") {
        exchange.responseHeaders.add("ETag", etag)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun runDownload(
        sha: String? = payloadSha,
        size: Long? = payload.size.toLong(),
        onProgress: (Long, Long, String) -> Unit = { _, _, _ -> },
        isCancelled: () -> Boolean = { false },
        target: File = File(dir, "update.apk"),
    ): File = runBlocking {
        UpdateDownloadEngine.download(
            client = client,
            sources = listOf(UpdateSource("test", base)),
            target = target,
            expectedSha256 = sha,
            expectedSize = size,
            onProgress = onProgress,
            isCancelled = isCancelled,
            retryDelayMillis = { 0 },
        )
    }

    @Test
    fun fullDownloadVerifiesShaAndSize() {
        handler = { exchange -> ok(exchange, payload) }

        val file = runDownload()

        assertEquals(payload.size.toLong(), file.length())
        assertEquals(payloadSha, UpdateDownloadEngine.sha256(file))
    }

    @Test
    fun resumesFromPartialFileUsingRange() {
        val target = File(dir, "update.apk")
        val firstHalf = payload.size / 2
        target.writeBytes(payload.copyOfRange(0, firstHalf))
        File(target.path + ".meta").writeText("$base\n\"v1\"\n")

        val seenRange = java.util.concurrent.atomic.AtomicReference<String>()
        val seenIfRange = java.util.concurrent.atomic.AtomicReference<String>()
        handler = { exchange ->
            seenRange.set(exchange.requestHeaders.getFirst("Range"))
            seenIfRange.set(exchange.requestHeaders.getFirst("If-Range"))
            exchange.responseHeaders.add("ETag", "\"v1\"")
            exchange.responseHeaders.add("Content-Range", "bytes $firstHalf-${payload.size - 1}/${payload.size}")
            exchange.sendResponseHeaders(206, (payload.size - firstHalf).toLong())
            exchange.responseBody.use { it.write(payload, firstHalf, payload.size - firstHalf) }
        }

        val file = runDownload()

        assertEquals("bytes=$firstHalf-", seenRange.get())
        assertEquals("\"v1\"", seenIfRange.get())
        assertEquals(payloadSha, UpdateDownloadEngine.sha256(file))
    }

    @Test
    fun restartsWhenServerIgnoresRange() {
        val target = File(dir, "update.apk")
        target.writeBytes(payload.copyOfRange(0, 500))
        File(target.path + ".meta").writeText("$base\n\"v1\"\n")

        handler = { exchange -> ok(exchange, payload) }

        val file = runDownload()

        assertEquals(payload.size.toLong(), file.length())
        assertEquals(payloadSha, UpdateDownloadEngine.sha256(file))
    }

    @Test
    fun usesCompleteLocalFileOnUnsatisfiableRange() {
        val target = File(dir, "update.apk")
        target.writeBytes(payload)
        File(target.path + ".meta").writeText("$base\n\"v1\"\n")

        handler = { exchange ->
            exchange.sendResponseHeaders(416, -1)
            exchange.close()
        }

        // 416 且本地内容已通过哈希/大小校验：直接使用，不再重下 19MB
        val file = runDownload(target = target)

        assertEquals(payload.size.toLong(), file.length())
        assertEquals(payloadSha, UpdateDownloadEngine.sha256(file))
    }

    @Test
    fun resetsOnUnsatisfiableRangeWithIncompleteFile() {
        val target = File(dir, "update.apk")
        target.writeBytes(payload.copyOfRange(0, 500))
        File(target.path + ".meta").writeText("$base\n\"v1\"\n")

        handler = { exchange ->
            exchange.sendResponseHeaders(416, -1)
            exchange.close()
        }

        val error = runCatching { runDownload(target = target) }.exceptionOrNull()

        // 本地片段不完整时 416 不可恢复：以来源失败收尾，且不保留坏片段
        val sourceError = error as UpdateSourcesException
        assertTrue(sourceError.failures.single().reason.contains("416"))
        assertFalse(target.exists())
    }

    @Test
    fun forbiddenIsNotRetried() {
        val calls = AtomicInteger(0)
        handler = { exchange ->
            calls.incrementAndGet()
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }

        val error = runCatching { runDownload() }.exceptionOrNull()

        // 403 不可重试：每个来源只请求一次，然后汇总失败原因
        val sourceError = error as UpdateSourcesException
        assertTrue(sourceError.failures.single().reason.contains("403"))
        assertEquals(1, calls.get())
    }

    @Test
    fun serverErrorsAreRetriedThenSucceed() {
        val calls = AtomicInteger(0)
        handler = { exchange ->
            if (calls.incrementAndGet() < 3) {
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
            } else {
                ok(exchange, payload)
            }
        }

        val file = runDownload()

        assertEquals(3, calls.get())
        assertEquals(payloadSha, UpdateDownloadEngine.sha256(file))
    }

    @Test
    fun invalidContentRangeResetsInsteadOfCorrupting() {
        val target = File(dir, "update.apk")
        target.writeBytes(payload.copyOfRange(0, 800))
        File(target.path + ".meta").writeText("$base\n\"v1\"\n")

        val calls = AtomicInteger(0)
        handler = { exchange ->
            if (calls.incrementAndGet() == 1) {
                exchange.responseHeaders.add("Content-Range", "bytes 100-199/2000")
                exchange.sendResponseHeaders(206, 1900)
                exchange.responseBody.use { it.write(payload, 100, 1900) }
            } else {
                ok(exchange, payload)
            }
        }

        val file = runDownload()

        assertEquals(2, calls.get())
        assertEquals(payloadSha, UpdateDownloadEngine.sha256(file))
    }

    @Test
    fun missingContentLengthUsesIndeterminateTotal() {
        var totalSeen = 1L
        handler = { exchange ->
            exchange.sendResponseHeaders(200, 0) // chunked，无 Content-Length
            exchange.responseBody.use { it.write(payload) }
        }

        runDownload(onProgress = { _, total, _ -> totalSeen = total })

        assertTrue(totalSeen <= 0)
    }

    @Test
    fun sizeMismatchDeletesFileWhenNoHash() {
        val target = File(dir, "update.apk")
        handler = { exchange -> ok(exchange, payload) }

        // 没有哈希时才靠大小判断完整性
        val error = runCatching { runDownload(sha = null, size = 9999, target = target) }.exceptionOrNull()

        assertTrue(error is UpdateSourcesException)
        assertTrue((error as UpdateSourcesException).failures.single().reason.contains("大小"))
        assertFalse(target.exists())
    }

    @Test
    fun hashWinsOverWrongSizeMetadata() {
        val target = File(dir, "update.apk")
        handler = { exchange -> ok(exchange, payload) }

        // 备用源 manifest 曾经把大小写错；内容是正确的时候就该放行
        val file = runDownload(size = 9999, target = target)

        assertEquals(payload.size.toLong(), file.length())
        assertEquals(payloadSha, UpdateDownloadEngine.sha256(file))
    }

    @Test
    fun shaMismatchDeletesFile() {
        val target = File(dir, "update.apk")
        handler = { exchange -> ok(exchange, payload) }

        val error = runCatching { runDownload(sha = "deadbeef", target = target) }.exceptionOrNull()

        assertTrue(error is UpdateSourcesException)
        assertTrue((error as UpdateSourcesException).failures.single().reason.contains("SHA-256"))
        assertFalse(target.exists())
    }

    @Test
    fun allSourcesFailWithPerSourceReasons() {
        handler = { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        val other = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        other.createContext("/apk") { exchange ->
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }
        other.start()
        try {
            val second = "http://127.0.0.1:${other.address.port}/apk"
            val error = runCatching {
                runBlocking {
                    UpdateDownloadEngine.download(
                        client = client,
                        sources = listOf(UpdateSource("备用源", base), UpdateSource("GitHub", second)),
                        target = File(dir, "update.apk"),
                        expectedSha256 = payloadSha,
                        expectedSize = payload.size.toLong(),
                        retryDelayMillis = { 0 },
                    )
                }
            }.exceptionOrNull()

            // 界面要能告诉用户「哪个源、什么原因」，而不是笼统的下载失败
            val failures = (error as UpdateSourcesException).failures
            assertEquals(2, failures.size)
            assertEquals("备用源", failures[0].label)
            assertTrue(failures[0].reason.contains("404"))
            assertEquals("GitHub", failures[1].label)
            assertTrue(failures[1].reason.contains("403"))
        } finally {
            other.stop(0)
        }
    }

    @Test
    fun versionChangeDoesNotResumeStalePartialFile() {
        val target = File(dir, "update.apk")
        target.writeBytes(payload.copyOfRange(0, 500))
        // 半截文件属于旧版本：即便 URL 相同也不能续传
        File(target.path + ".meta").writeText("$base\n\"v1\"\n\n0.8.1")
        val seenRange = java.util.concurrent.atomic.AtomicReference<String>()
        handler = { exchange ->
            seenRange.set(exchange.requestHeaders.getFirst("Range"))
            ok(exchange, payload)
        }

        val file = runBlocking {
            UpdateDownloadEngine.download(
                client = client,
                sources = listOf(UpdateSource("test", base)),
                target = target,
                expectedSha256 = payloadSha,
                expectedSize = payload.size.toLong(),
                version = "0.8.2",
                retryDelayMillis = { 0 },
            )
        }

        assertEquals(null, seenRange.get())
        assertEquals(payload.size.toLong(), file.length())
        assertEquals(payloadSha, UpdateDownloadEngine.sha256(file))
    }

    @Test
    fun cancelKeepsPartialFileForNextResume() {
        val target = File(dir, "update.apk")
        var cancelled = false
        handler = { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { out ->
                var sent = 0
                while (sent < payload.size) {
                    val step = minOf(200, payload.size - sent)
                    out.write(payload, sent, step)
                    sent += step
                    out.flush()
                    Thread.sleep(1)
                }
            }
        }

        val error = runCatching {
            runDownload(
                target = target,
                onProgress = { read, _, _ -> if (read >= 500) cancelled = true },
                isCancelled = { cancelled },
            )
        }.exceptionOrNull()

        assertTrue(error is UpdateCancelledException)
        assertTrue(target.length() > 0)
        assertTrue(target.length() <= payload.size.toLong())
    }

    @Test
    fun connectionFailureReportsIOException() {
        val error = runCatching {
            runBlocking {
                UpdateDownloadEngine.download(
                    client = client,
                    sources = listOf(UpdateSource("dead", "http://127.0.0.1:1/apk")),
                    target = File(dir, "update.apk"),
                    expectedSha256 = payloadSha,
                    retryDelayMillis = { 0 },
                )
            }
        }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
    }

    private fun sha256Of(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
