package me.rerere.common.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SseBackpressureTest {
    private fun client(
        body: String,
        canceled: CountDownLatch = CountDownLatch(1),
        failAtEnd: Boolean = false,
    ) =
        OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    canceled.countDown()
                }
            })
            .addInterceptor { chain ->
                val original = body.toResponseBody("text/event-stream".toMediaType())
                val responseBody = if (!failAtEnd) {
                    original
                } else {
                    object : okhttp3.ResponseBody() {
                        private val source = object : okio.ForwardingSource(original.source()) {
                            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                                val count = super.read(sink, byteCount)
                                if (count == -1L) throw java.io.IOException("test stream interrupted")
                                return count
                            }
                        }.buffer()
                        override fun contentType() = original.contentType()
                        override fun contentLength() = -1L
                        override fun source() = source
                    }
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(responseBody).build()
            }.build()

    @Test
    fun burstRetainsEveryChunkAndClosedWithSlowConsumer() = runBlocking {
        val client = client((0 until 512).joinToString("") { "data: $it\n\n" })
        try {
            val actual = mutableListOf<SseEvent>()
            withTimeout(15_000) {
                client.sseFlow(Request.Builder().url("http://localhost/events").build()).collect {
                    delay(1)
                    actual.add(it)
                }
            }
            assertEquals(SseEvent.Open, actual.first())
            assertEquals((0 until 512).map { it.toString() }, actual.filterIsInstance<SseEvent.Event>().map { it.data })
            assertEquals(SseEvent.Closed, actual.last())
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun failureAfterBurstIsDeliveredBeforeFlowThrows() = runBlocking {
        val client = client((0 until 256).joinToString("") { "data: $it\n\n" }, failAtEnd = true)
        try {
            val actual = mutableListOf<SseEvent>()
            val failure = runCatching {
                withTimeout(10_000) {
                    client.sseFlow(Request.Builder().url("http://localhost/events").build()).collect {
                        delay(1)
                        actual.add(it)
                    }
                }
            }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertEquals((0 until 256).map { it.toString() }, actual.filterIsInstance<SseEvent.Event>().map { it.data })
            assertTrue(actual.last() is SseEvent.Failure)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun cancellationWakesProducerBlockedByFullQueue() = runBlocking {
        val canceled = CountDownLatch(1)
        val client = client((0 until 4096).joinToString("") { "data: $it\n\n" }, canceled)
        try {
            val opened = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val job = async {
                client.sseFlow(Request.Builder().url("http://localhost/events").build()).collect {
                    opened.complete(Unit)
                    gate.await()
                }
            }
            withTimeout(5_000) { opened.await() }
            delay(100) // Reader fills the bounded queue while consumer remains suspended.
            withTimeout(5_000) { job.cancelAndJoin() }
            assertTrue(canceled.await(5, TimeUnit.SECONDS))
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
