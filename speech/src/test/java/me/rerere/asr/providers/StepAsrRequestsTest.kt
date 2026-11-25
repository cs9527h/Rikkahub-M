package me.rerere.asr.providers

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class StepAsrRequestsTest {
    @Test
    fun disposeCancelsRealCallWaitingForHeaders() = verifyCancellation(sendHeaders = false, cancelJob = false)

    @Test
    fun disposeCancelsRealCallBlockedReadingSseBody() = verifyCancellation(sendHeaders = true, cancelJob = false)

    @Test
    fun coroutineCancellationCancelsRealCallBlockedReadingSseBody() = verifyCancellation(sendHeaders = true, cancelJob = true)

    private fun verifyCancellation(sendHeaders: Boolean, cancelJob: Boolean) = runBlocking {
        val accepted = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = ServerSocket(0)
        val serverThread = thread(name = "step-asr-test-server", isDaemon = true) {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                while (reader.readLine()?.isNotEmpty() == true) { /* read headers */ }
                if (sendHeaders) {
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                }
                accepted.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { canceled.countDown() }
        }).build()
        val requests = StepAsrRequests(client)
        try {
            val job = async(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    requests.execute(Request.Builder().url("http://127.0.0.1:${server.localPort}/sse").build()) {
                        reading.countDown()
                        it.body.source().readUtf8Line() ?: ""
                    }
                }
            }
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            if (sendHeaders) assertTrue(reading.await(5, TimeUnit.SECONDS))
            if (cancelJob) {
                withTimeout(5000) { job.cancelAndJoin() }
            } else {
                requests.dispose()
                assertTrue(withTimeout(5000) { job.await() }.isFailure)
            }
            assertTrue("Real OkHttp Call.cancel must run", canceled.await(5, TimeUnit.SECONDS))
        } finally {
            requests.dispose()
            release.countDown()
            server.close()
            serverThread.join(5000)
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
