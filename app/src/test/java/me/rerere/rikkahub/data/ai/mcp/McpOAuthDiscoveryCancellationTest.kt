package me.rerere.rikkahub.data.ai.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

class McpOAuthDiscoveryCancellationTest {
    @Test
    fun `cancelled resource probe does not continue with well known candidates`() = runBlocking {
        val cancellation = CancellationException("stop metadata lookup")
        var requests = 0
        val client = McpOAuthDiscoveryClient(OkHttpClient()) {
            requests++
            throw cancellation
        }
        val result = runCatching { client.discoverProtectedResource("https://example.test/mcp") }
        assertRecoveredFrom(cancellation, result.exceptionOrNull())
        assertEquals(1, requests)
    }

    @Test
    fun `cancelled metadata candidate stops fallback and already probed response is closed`() = runBlocking {
        val cancellation = CancellationException("stop candidate")
        val body = TrackingBody("probe")
        var requests = 0
        val client = McpOAuthDiscoveryClient(OkHttpClient()) { request ->
            if (++requests == 1) response(request, body) else throw cancellation
        }
        val result = runCatching { client.discoverProtectedResource("https://example.test/mcp") }
        assertRecoveredFrom(cancellation, result.exceptionOrNull())
        assertEquals(2, requests)
        assertTrue(body.closed)
    }

    @Test
    fun `cancelled authorization discovery does not try next issuer endpoint`() = runBlocking {
        val cancellation = CancellationException("stop issuer lookup")
        var requests = 0
        val client = McpOAuthDiscoveryClient(OkHttpClient()) {
            requests++
            throw cancellation
        }
        val result = runCatching { client.discoverAuthorizationServer("https://example.test/issuer") }
        assertRecoveredFrom(cancellation, result.exceptionOrNull())
        assertEquals(1, requests)
    }

    @Test
    fun `ordinary candidate failures still fall back and close every failed HTTP response`() = runBlocking {
        val bodies = mutableListOf<TrackingBody>()
        val client = McpOAuthDiscoveryClient(OkHttpClient()) { request ->
            val body = TrackingBody("not metadata")
            bodies.add(body)
            response(request, body)
        }
        val result = runCatching { client.discoverProtectedResource("https://example.test/mcp") }
        assertTrue(result.isFailure)
        assertEquals(3, bodies.size) // probe, path-specific well-known, origin well-known
        assertTrue(bodies.all { it.closed })
    }

    @Test
    fun `ordinary transport errors remain compatible fallback but fatal errors are not recovered`() = runBlocking {
        var requests = 0
        val client = McpOAuthDiscoveryClient(OkHttpClient()) {
            requests++
            throw IOException("unavailable")
        }
        assertTrue(runCatching { client.discoverProtectedResource("https://example.test/mcp") }.isFailure)
        assertEquals(3, requests)
        val fatal = OutOfMemoryError("allocation failed")
        val fatalClient = McpOAuthDiscoveryClient(OkHttpClient()) { throw fatal }
        assertRecoveredFrom(
            fatal,
            runCatching { fatalClient.discoverProtectedResource("https://example.test/mcp") }.exceptionOrNull()
        )
    }

    private fun assertRecoveredFrom(original: Throwable, actual: Throwable?) {
        checkNotNull(actual) { "Expected cancellation or fatal error to escape discovery" }
        assertEquals(original.javaClass, actual.javaClass)
        assertEquals(original.message, actual.message)
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = actual
        while (current != null && seen.add(current)) {
            if (current === original) return
            current = current.cause
        }
        throw AssertionError("Recovered exception must preserve original identity in its cause chain")
    }

    private fun response(request: Request, body: ResponseBody): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(503).message("Unavailable").body(body).build()

    private class TrackingBody(text: String) : ResponseBody() {
        var closed = false
        private val tracked = object : ForwardingSource(Buffer().writeUtf8(text)) {
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()
        override fun contentType() = null
        override fun contentLength() = -1L
        override fun source() = tracked
    }
}
