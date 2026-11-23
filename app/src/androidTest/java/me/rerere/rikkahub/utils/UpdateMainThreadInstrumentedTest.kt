package me.rerere.rikkahub.utils

import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * 真机/模拟器上的主线程纪律验证。
 *
 * 用户截图里的 NetworkOnMainThreadException 只有在 Android 运行时才会发生，
 * 所以这里做两件事：
 *  1) 在**真正的主线程**上设置 StrictMode 网络检测（StrictMode 是线程级的，
 *     必须在主线程 setThreadPolicy 才能挡住主线程的 socket 操作），
 *  2) 从主线程调用 [UpdateDownloadEngine.download] / [UpdateInstaller.downloadAndVerify]，
 *     断言没有任何网络违规、且下载回调不在主线程执行。
 *
 * 先跑 [mainThreadSocketIsActuallyDetected] 作为对照：如果这条控制用例抓不到
 * 主线程 socket，就说明本次采集环境失效，后面的「没有违规」不算证据。
 */
@RunWith(AndroidJUnit4::class)
class UpdateMainThreadInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
    private val payloadSha = sha256Of(payload)

    private lateinit var server: TinyHttpServer
    private val violations = Collections.synchronizedList(mutableListOf<String>())
    private var executor: ExecutorService? = null
    private var originalPolicy: StrictMode.ThreadPolicy? = null
    private var dir: File? = null

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "update-instrumented").apply { mkdirs() }
        server = TinyHttpServer(payload).apply { start() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val threadExecutor = Executors.newSingleThreadExecutor()
            executor = threadExecutor
            onMainThread {
                originalPolicy = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder()
                        .detectNetwork()
                        .penaltyLog()
                        .penaltyListener(threadExecutor) { violation ->
                            // 只记类名：不同 ROM 的文案不同，类型才稳定
                            violations += violation.javaClass.name
                        }
                        .build(),
                )
            }
        }
    }

    @After
    fun tearDown() {
        onMainThread {
            originalPolicy?.let { StrictMode.setThreadPolicy(it) }
        }
        executor?.shutdownNow()
        server.close()
        dir?.deleteRecursively()
    }

    /** 对照用例：主线程开 socket 必须被这次采集抓到，否则后面的「无违规」没有意义。 */
    @Test
    fun mainThreadSocketIsActuallyDetected() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        violations.clear()
        onMainThread {
            runCatching { Socket("127.0.0.1", server.port).use { it.getOutputStream().write(1) } }
        }
        assertTrue("StrictMode 采集未生效：主线程 socket 没有产生任何违规", awaitViolation())
    }

    @Test
    fun downloadEngineFromMainThreadStaysStrictModeClean() {
        violations.clear()
        val callbackThread = AtomicReference<String?>()
        val target = File(requireNotNull(dir), "engine.apk")

        val file = runBlocking(Dispatchers.Main) {
            UpdateDownloadEngine.download(
                client = OkHttpClient(),
                sources = listOf(UpdateSource("本地源", server.url)),
                target = target,
                expectedSha256 = payloadSha,
                expectedSize = payload.size.toLong(),
                onProgress = { _, _, _ -> callbackThread.compareAndSet(null, Thread.currentThread().name) },
                retryDelayMillis = { 0 },
            )
        }

        assertEquals(payload.size.toLong(), file.length())
        assertEquals(payloadSha, sha256Of(file.readBytes()))
        assertFalse(
            "下载回调跑在主线程上：${callbackThread.get()}",
            callbackThread.get() == null || callbackThread.get() == mainThreadName(),
        )
        assertNoNetworkViolation()
    }

    @Test
    fun installerFromMainThreadNeverTouchesNetworkOnMain() {
        violations.clear()
        val callbackThread = AtomicReference<String?>()

        val result = runBlocking(Dispatchers.Main) {
            UpdateInstaller.downloadAndVerify(
                context = context,
                client = OkHttpClient(),
                sources = listOf(UpdateSource("本地源", server.url)),
                expectedSha256 = payloadSha,
                expectedSize = payload.size.toLong(),
                onProgress = { _, _, _ -> callbackThread.compareAndSet(null, Thread.currentThread().name) },
            )
        }

        // 测试用的是假 APK：下载会成功，安装校验应当以 UpdateCheckException 收尾，
        // 而不是主线程网络异常。
        val error = result.exceptionOrNull()
        assertNotNull("安装校验阶段必须拦下无效 APK", error)
        assertTrue("应为安装包校验失败，实际 ${error?.javaClass?.name}", error is UpdateCheckException)
        assertFalse(isNetworkOnMainThreadError(error))
        assertFalse(
            "下载回调跑在主线程上：${callbackThread.get()}",
            callbackThread.get() == null || callbackThread.get() == mainThreadName(),
        )
        assertNoNetworkViolation()
    }

    @Test
    fun progressStateIsUpdatedOffMainThread() {
        violations.clear()
        val mainName = mainThreadName()
        val threads = Collections.synchronizedList(mutableListOf<String>())

        runBlocking(Dispatchers.Main) {
            UpdateDownloadEngine.download(
                client = OkHttpClient(),
                sources = listOf(UpdateSource("本地源", server.url)),
                target = File(requireNotNull(dir), "progress.apk"),
                expectedSha256 = payloadSha,
                expectedSize = payload.size.toLong(),
                onProgress = { _, _, _ -> threads += Thread.currentThread().name },
                onVerifying = { threads += Thread.currentThread().name },
                retryDelayMillis = { 0 },
            )
        }

        assertTrue("没有观测到任何进度回调", threads.isNotEmpty())
        threads.forEach { name -> assertFalse("进度/校验回调跑在主线程上：$name", name == mainName) }
        assertNoNetworkViolation()
    }

    private fun mainThreadName(): String = Looper.getMainLooper().thread.name

    private fun onMainThread(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun awaitViolation(timeoutMillis: Long = 3000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (violations.isNotEmpty()) return true
            Thread.sleep(20)
        }
        return violations.isNotEmpty()
    }

    private fun assertNoNetworkViolation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        // 违规是通过 executor 异步回调的，给它一点时间落地再断言
        Thread.sleep(150)
        assertTrue(
            "主线程上发生了网络违规：${violations.joinToString()}",
            violations.filter { it.contains("Network", ignoreCase = true) }.isEmpty(),
        )
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 极简 HTTP/1.1 服务：只用 ServerSocket，不引第三方依赖（androidTest 里没有 com.sun.net.httpserver）。 */
    private class TinyHttpServer(private val payload: ByteArray) {
        private val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))

        @Volatile
        private var running = true

        val port: Int get() = serverSocket.localPort
        val url: String get() = "http://127.0.0.1:$port/apk"

        private val worker = Thread({
            while (running) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: Exception) {
                    break
                }
                try {
                    socket.use { connection ->
                        val input = BufferedInputStream(connection.inputStream)
                        readLine(input)
                        while (readLine(input).isNotEmpty()) {
                            // 读完请求头即可，测试不需要请求体
                        }
                        val head = "HTTP/1.1 200 OK\r\n" +
                            "Content-Length: ${payload.size}\r\n" +
                            "Content-Type: application/vnd.android.package-archive\r\n" +
                            "Connection: close\r\n\r\n"
                        val output = connection.getOutputStream()
                        output.write(head.toByteArray())
                        output.write(payload)
                        output.flush()
                    }
                } catch (e: Exception) {
                    // 单个连接失败不影响后续用例
                }
            }
        }, "tiny-http-server").apply { isDaemon = true }

        fun start() = worker.start()

        fun close() {
            running = false
            runCatching { serverSocket.close() }
        }

        private fun readLine(input: BufferedInputStream): String {
            val builder = StringBuilder()
            while (true) {
                val byte = input.read()
                if (byte == -1) break
                val char = byte.toChar()
                if (char == '\n') break
                if (char != '\r') builder.append(char)
            }
            return builder.toString()
        }
    }
}
