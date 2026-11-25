package me.rerere.search

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.CoroutineContext

class SearchRequestCancellationTest {
    @Test
    fun `cancelling an in flight await cancels its call and closes a late response`() = runBlocking {
        val call = ControlledCall()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { call.await().close() }
        job.cancel()
        assertTrue(call.isCanceled())
        val body = TrackingBody()
        call.respond(body)
        call.callback.onFailure(call, IOException("cancelled"))
        job.join()
        assertEquals(1, body.closes)
    }

    @Test
    fun `cancellation after resume but before dispatch closes undelivered response`() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val call = ControlledCall()
        var delivered = false
        val job = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            call.await().use { delivered = true }
        }
        val body = TrackingBody()
        call.respond(body)
        assertEquals(0, body.closes)
        job.cancel()
        dispatcher.drain()
        job.join()
        assertFalse(delivered)
        assertTrue(call.isCanceled())
        assertEquals(1, body.closes)
    }

    @Test
    fun `successful handoff leaves response open until consumer closes it`() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val call = ControlledCall()
        var response: Response? = null
        val job = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { response = call.await() }
        val body = TrackingBody()
        call.respond(body)
        dispatcher.drain()
        job.join()
        assertEquals(0, body.closes)
        assertFalse(call.isCanceled())
        response!!.close()
        assertEquals(1, body.closes)
    }

    @Test
    fun `network failure resumes with original exception`() = runBlocking {
        val call = ControlledCall()
        val failure = IOException("network down")
        var caught: Throwable? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            caught = runCatching { call.await() }.exceptionOrNull()
        }
        call.callback.onFailure(call, failure)
        job.join()
        assertTrue(caught is IOException)
        assertEquals(failure.message, caught?.message)
        // Coroutine stacktrace recovery may copy an IOException and retain the original as its cause.
        assertTrue(generateSequence(caught) { it.cause }.any { it === failure })
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }
        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private class TrackingBody : ResponseBody() {
        var closes = 0
        private val buffer = Buffer().writeUtf8("response")
        override fun contentType() = null
        override fun contentLength() = 8L
        override fun source() = buffer
        override fun close() {
            closes++
            super.close()
        }
    }

    private class ControlledCall : Call by okhttp3.OkHttpClient().newCall(
        Request.Builder().url("https://example.test/").build()
    ) {
        lateinit var callback: Callback
        private var cancelled = false
        private val request = Request.Builder().url("https://example.test/").build()
        fun respond(body: ResponseBody) {
            callback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build())
        }
        override fun request() = request
        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
        }
        override fun cancel() {
            cancelled = true
        }
        override fun isCanceled() = cancelled
        override fun isExecuted() = ::callback.isInitialized
        override fun execute(): Response = error("Only async execution is expected")
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = ControlledCall()
    }
}
