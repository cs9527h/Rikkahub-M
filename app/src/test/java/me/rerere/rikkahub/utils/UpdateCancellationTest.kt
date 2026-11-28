package me.rerere.rikkahub.utils

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UpdateCancellationTest {
    @Test
    fun cancellationInterruptsRealCallWaitingForHeaders() = cancellationInterruptsDownload(false)

    @Test
    fun cancellationInterruptsRealCallReadingBodyAndKeepsPartial() = cancellationInterruptsDownload(true)

    private fun cancellationInterruptsDownload(sendBody: Boolean) = runBlocking {
        val dir = Files.createTempDirectory("update-cancel").toFile()
        val target = File(dir, "update.apk")
        val received = CountDownLatch(1)
        val transferred = CountDownLatch(1)
        val release = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val attempts = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/apk") { exchange ->
            attempts.incrementAndGet()
            try {
                if (sendBody) {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write(byteArrayOf(1, 2, 3))
                    exchange.responseBody.flush()
                }
                received.countDown()
                release.await(10, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.start()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { canceled.countDown() }
        }).build()
        try {
            val url = "http://127.0.0.1:${server.address.port}/apk"
            val job = async(Dispatchers.IO) {
                UpdateDownloadEngine.download(
                    client, listOf(UpdateSource("first", url), UpdateSource("second", url)),
                    target, expectedSha256 = null,
                    onProgress = { _, _, _ -> transferred.countDown() }, retryDelayMillis = { 0 },
                )
            }
            assertTrue(received.await(5, TimeUnit.SECONDS))
            if (sendBody) assertTrue(transferred.await(5, TimeUnit.SECONDS))
            withTimeout(5000) { job.cancelAndJoin() }
            assertTrue("Cancellation must reach actual Call", canceled.await(5, TimeUnit.SECONDS))
            assertEquals("Cancellation must not retry or switch sources", 1, attempts.get())
            if (sendBody) assertTrue("Keep resumable data", target.length() > 0)
        } finally {
            release.countDown()
            server.stop(0)
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            dir.deleteRecursively()
        }
    }

    @Test
    fun callbackCancellationAndFatalErrorAreNotRetriedOrWrapped() {
        for (failure in listOf(kotlinx.coroutines.CancellationException("cancel"), AssertionError("fatal"))) {
            val dir = Files.createTempDirectory("update-callback").toFile()
            val attempts = AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/apk") { exchange ->
                attempts.incrementAndGet()
                exchange.sendResponseHeaders(200, 3)
                exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
            }
            server.start()
            val client = OkHttpClient()
            try {
                val url = "http://127.0.0.1:${server.address.port}/apk"
                val actual = runCatching {
                    runBlocking {
                        UpdateDownloadEngine.download(
                            client, listOf(UpdateSource("first", url), UpdateSource("second", url)),
                            File(dir, "update.apk"), expectedSha256 = null,
                            onProgress = { _, _, _ -> throw failure }, retryDelayMillis = { 0 },
                        )
                    }
                }.exceptionOrNull()
                me.rerere.rikkahub.testutil.assertRecoveredFailure(failure, actual)
                assertEquals(1, attempts.get())
            } finally {
                server.stop(0)
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
                dir.deleteRecursively()
            }
        }
    }
}
