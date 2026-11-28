package me.rerere.tts.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CancellableSpeechCallTest {
    @Test
    fun `cancellation after headers cancels real Call closes body and skips success`() = runBlocking {
        val consuming = CountDownLatch(1)
        val release = CountDownLatch(1)
        var closed = false
        var returned = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(object : ResponseBody() {
                    private val source = Buffer().writeUtf8("audio")
                    override fun contentType() = null
                    override fun contentLength() = 5L
                    override fun source() = source
                    override fun close() {
                        closed = true
                        super.close()
                    }
                }).build()
        }.build()
        val call: Call = client.newCall(Request.Builder().url("https://example.test/").build())
        val job = launch(Dispatchers.IO) {
            call.useCancellable {
                consuming.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                it.body.bytes()
            }
            returned = true
        }
        try {
            assertTrue(consuming.await(5, TimeUnit.SECONDS))
            job.cancel()
            assertTrue(call.isCanceled())
        } finally {
            release.countDown()
            job.cancel()
            job.join()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
        assertTrue(closed)
        assertFalse(returned)
    }

    @Test
    fun `consumer exceptions close the response and preserve failure`() = runBlocking {
        var closed = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(object : ResponseBody() {
                    override fun contentType() = null
                    override fun contentLength() = 0L
                    override fun source() = Buffer()
                    override fun close() {
                        closed = true
                        super.close()
                    }
                }).build()
        }.build()
        val failure = IllegalArgumentException("consumer")
        try {
            val caught = runCatching {
                client.newCall(Request.Builder().url("https://example.test/").build()).useCancellable<Unit> {
                    throw failure
                }
            }.exceptionOrNull()
            assertTrue(generateSequence(caught) { it.cause }.any { it === failure })
            assertTrue(closed)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
