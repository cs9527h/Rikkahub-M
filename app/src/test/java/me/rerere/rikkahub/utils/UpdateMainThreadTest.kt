package me.rerere.rikkahub.utils

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 「主线程上不许有网络/文件 IO」的回归锁。
 *
 * 真机故障（0.8.2 / 0.8.5 的截图）：更新下载抛 NetworkOnMainThreadException，
 * 界面上是「备用源：NetworkOnMainThreadException；GitHub：NetworkOnMainThreadException」。
 * 这里用 Dispatchers.setMain 把主调度器换成测试调度器，**从主调度器发起下载**，
 * 然后在真正执行 socket IO 的线程上记线程名（OkHttp network interceptor + onVerifying 回调），
 * 断言它们都不是主线程。覆盖：全量下载、Range/206 续传、切源+退避重试、SHA-256 校验阶段。
 *
 * 注意：断言的是「发起下载的线程」与「真正干活的线程」不同，
 * 所以任何把 withContext(Dispatchers.IO) 去掉的改动都会让这些用例失败。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateMainThreadTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var dir: File

    private val payload = ByteArray(4000) { (it % 251).toByte() }
    private val payloadSha = sha256Of(payload)

    /** 真正执行 socket IO 的线程名（network interceptor 就运行在这个线程上）。 */
    private val networkThreads = Collections.synchronizedList(mutableListOf<String>())
    private val callerThread = AtomicReference<Thread?>()

    private var handler: (HttpExchange) -> Unit = {}

    private val client = OkHttpClient.Builder()
        .addNetworkInterceptor { chain ->
            networkThreads += Thread.currentThread().name
            chain.proceed(chain.request())
        }
        .build()

    @Before
    fun setUp() {
        // 主调度器用测试实现代替：测试里就能真的「从 Main 发起下载」，而不依赖 Android 主线程
        Dispatchers.setMain(StandardTestDispatcher())
        dir = Files.createTempDirectory("update-main-thread").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/apk") { exchange -> handler(exchange) }
        server.start()
        base = "http://127.0.0.1:${server.address.port}/apk"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.stop(0)
        dir.deleteRecursively()
    }

    @Test
    fun fullDownloadFromMainDispatcherKeepsNetworkOffMainThread() = runTest {
        handler = { exchange -> ok(exchange, payload) }

        val file = withContext(Dispatchers.Main) {
            callerThread.set(Thread.currentThread())
            UpdateDownloadEngine.download(
                client = client,
                sources = listOf(UpdateSource("测试源", base)),
                target = File(dir, "update.apk"),
                expectedSha256 = payloadSha,
                expectedSize = payload.size.toLong(),
                retryDelayMillis = { 0 },
            )
        }

        assertEquals(payload.size.toLong(), file.length())
        assertEquals(payloadSha, UpdateDownloadEngine.sha256(file))
        assertNoWorkOnMainThread()
    }

    @Test
    fun resumeRangeRequestAlsoStaysOffMainThread() = runTest {
        val target = File(dir, "update.apk")
        val firstHalf = payload.size / 2
        target.writeBytes(payload.copyOfRange(0, firstHalf))
        File(target.path + ".meta").writeText("$base\n\"v1\"\n")

        val seenRange = AtomicReference<String?>()
        handler = { exchange ->
            seenRange.set(exchange.requestHeaders.getFirst("Range"))
            exchange.responseHeaders.add("ETag", "\"v1\"")
            exchange.responseHeaders.add("Content-Range", "bytes $firstHalf-${payload.size - 1}/${payload.size}")
            exchange.sendResponseHeaders(206, (payload.size - firstHalf).toLong())
            exchange.responseBody.use { it.write(payload, firstHalf, payload.size - firstHalf) }
        }

        val file = withContext(Dispatchers.Main) {
            callerThread.set(Thread.currentThread())
            UpdateDownloadEngine.download(
                client = client,
                sources = listOf(UpdateSource("测试源", base)),
                target = target,
                expectedSha256 = payloadSha,
                expectedSize = payload.size.toLong(),
                retryDelayMillis = { 0 },
            )
        }

        assertEquals("bytes=$firstHalf-", seenRange.get())
        assertEquals(payloadSha, UpdateDownloadEngine.sha256(file))
        assertNoWorkOnMainThread()
    }

    @Test
    fun sourceFallbackWithBackoffRetryStaysOffMainThread() = runTest {
        val calls = AtomicInteger(0)
        handler = { exchange ->
            // 第一个来源两次都 500（触发退避重试），第二个来源才成功
            if (calls.incrementAndGet() < 3) {
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
            } else {
                ok(exchange, payload)
            }
        }

        val file = withContext(Dispatchers.Main) {
            callerThread.set(Thread.currentThread())
            UpdateDownloadEngine.download(
                client = client,
                sources = listOf(UpdateSource("备用源", base), UpdateSource("GitHub", base)),
                target = File(dir, "update.apk"),
                expectedSha256 = payloadSha,
                expectedSize = payload.size.toLong(),
                retryDelayMillis = { 0 },
            )
        }

        assertEquals("重试与切源路径都必须真的被走到", 3, calls.get())
        assertEquals(payloadSha, UpdateDownloadEngine.sha256(file))
        assertNoWorkOnMainThread()
    }

    @Test
    fun verificationStageRunsOffMainThreadAndIsReported() = runTest {
        handler = { exchange -> ok(exchange, payload) }
        val verifyThreads = Collections.synchronizedList(mutableListOf<String>())
        val stages = Collections.synchronizedList(mutableListOf<UpdateStage>())

        withContext(Dispatchers.Main) {
            callerThread.set(Thread.currentThread())
            UpdateDownloadEngine.download(
                client = client,
                sources = listOf(UpdateSource("测试源", base)),
                target = File(dir, "update.apk"),
                expectedSha256 = payloadSha,
                expectedSize = payload.size.toLong(),
                onVerifying = { verifyThreads += Thread.currentThread().name },
                onStage = { stage -> stages += stage },
                retryDelayMillis = { 0 },
            )
        }

        // 阶段顺序是诊断文案的依据：失败时界面会写「（失败阶段：…）」
        assertEquals(
            listOf(UpdateStage.PREPARE, UpdateStage.CONNECT, UpdateStage.TRANSFER, UpdateStage.VERIFY),
            stages.toList(),
        )
        // onVerifying 与随后的 sha256 校验在同一个协程续体上执行，线程相同即证明哈希没跑在主线程
        assertTrue("没有观测到校验阶段", verifyThreads.isNotEmpty())
        val main = requireNotNull(callerThread.get()) { "未记录发起下载的线程" }
        verifyThreads.forEach { name ->
            assertFalse("SHA-256 校验跑在主线程上：$name", name == main.name)
        }
        assertNoWorkOnMainThread()
    }

    @Test
    fun describeDownloadErrorFlagsMainThreadViolation() {
        // 真机上这个异常由 BlockGuard 抛出、message 为 null；单测只能靠类名/文案识别，两条都要认
        assertTrue(isNetworkOnMainThreadError(IllegalStateException("NetworkOnMainThreadException")))
        assertFalse(isNetworkOnMainThreadError(java.io.IOException("timeout")))
        assertTrue(
            "主线程网络违规必须给出面向用户的中文提示，而不是当成网络中断",
            describeDownloadError(IllegalStateException("NetworkOnMainThreadException")).contains("主线程"),
        )
        assertEquals("未知错误", describeDownloadError(null))
    }

    private fun assertNoWorkOnMainThread() {
        val main = requireNotNull(callerThread.get()) { "未记录发起下载的线程" }
        assertTrue("没有观测到任何网络请求", networkThreads.isNotEmpty())
        networkThreads.forEach { name ->
            assertFalse("网络请求跑在主线程上：$name", name == main.name)
        }
    }

    private fun ok(exchange: HttpExchange, bytes: ByteArray, etag: String = "\"v1\"") {
        exchange.responseHeaders.add("ETag", etag)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun sha256Of(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
