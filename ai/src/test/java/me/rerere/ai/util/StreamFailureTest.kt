package me.rerere.ai.util

import kotlinx.coroutines.CancellationException
import me.rerere.common.util.formatExceptionForLog
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class StreamFailureTest {
    @Test
    fun `empty HTTP errors and invalid JSON never become successful completion`() {
        response(500, "").use { assertTrue(streamFailure("provider.stream", null, it) is IOException) }
        response(503, "not-json-private-body").use {
            val failure = streamFailure("provider.stream", null, it)
            assertTrue(failure is IOException)
            assertTrue(failure.message.orEmpty().contains("503"))
            assertEquals(1, failure.suppressed.size)
            assertFalse(formatExceptionForLog("provider.stream", failure).contains("private-body"))
        }
    }

    @Test
    fun `original network failure is retained when error body parsing also fails`() {
        val network = IOException("network private token")
        response(502, "malformed").use {
            assertSame(network, streamFailure("provider.stream", network, it))
            assertEquals(1, network.suppressed.size)
        }
    }

    @Test
    fun `zero quota decoding produces entitlement failure rather than a swallowed exception`() {
        val body = """{"error":"Requests per Minute (actual/limit): 0/0"}"""
        response(429, body).use { response ->
            val failure = streamFailure("responses.stream", null, response) {
                rateLimitEntitlementMessage(response.code, it)?.let(::HttpException)
                    ?: error("Expected zero quota marker")
            }
            assertTrue(failure is HttpException)
            assertTrue(failure.message.orEmpty().contains("RPM 0/0"))
        }
    }

    @Test
    fun `peek is bounded and response ownership remains with SSE owner`() {
        var closed = false
        var bytesRead = 0L
        val source = object : ForwardingSource(Buffer().writeUtf8("x".repeat(100_000))) {
            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also {
                if (it > 0) bytesRead += it
            }
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = source
        }
        response(500, "").newBuilder().body(body).build().use {
            var decodedLength = 0
            streamFailure("provider.stream", null, it) { text ->
                decodedLength = text.length
                IOException("decoded")
            }
            assertEquals(16 * 1024, decodedLength)
            assertTrue(bytesRead < 100_000)
            assertFalse(closed)
        }
        assertTrue(closed)
    }

    @Test
    fun `cancellation does not inspect response body and preserves identity`() {
        val cancellation = CancellationException("stopped")
        assertSame(cancellation, streamFailure("provider.stream", cancellation, null) { error("must not decode") })
    }

    private fun response(code: Int, text: String): Response = Response.Builder()
        .request(Request.Builder().url("https://example.test/?key=private").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("error").body(text.toResponseBody()).build()
}
