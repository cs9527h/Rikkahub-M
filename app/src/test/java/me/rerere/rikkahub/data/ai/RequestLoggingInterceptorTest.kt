package me.rerere.rikkahub.data.ai

import me.rerere.common.android.Logging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class RequestLoggingInterceptorTest {
    private var wasEnabled = false

    @Before fun prepare() {
        wasEnabled = Logging.isRequestLoggingEnabled()
        Logging.clear()
        Logging.setRequestLoggingEnabled(true)
    }

    @After fun restore() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(wasEnabled)
    }

    @Test fun `one shot body is written once by transport and not consumed by logging`() {
        val body = CountingBody(oneShot = true)
        execute(body)
        assertEquals(1, body.writes)
        assertTrue(Logging.getRequestLogs().single().requestBody!!.contains("not captured"))
    }

    @Test fun `duplex body is written once by transport and not consumed by logging`() {
        val body = CountingBody(duplex = true)
        execute(body)
        assertEquals(1, body.writes)
        assertTrue(Logging.getRequestLogs().single().requestBody!!.contains("not captured"))
    }

    @Test fun `explicit trace retains repeatable body and safe metadata while redacting credentials`() {
        val body = CountingBody()
        execute(body)
        assertEquals(2, body.writes) // one opt-in capture and one actual transport write
        val log = Logging.getRequestLogs().single()
        assertEquals("sample payload", log.requestBody)
        assertEquals(200, log.responseCode)
        assertEquals("application/json", log.requestHeaders["Content-Type"])
        assertEquals("██", log.requestHeaders["Authorization"])
        assertEquals("██", log.requestHeaders["X-Api-Key"])
        assertEquals("██", log.responseHeaders["Set-Cookie"])
        assertFalse(log.url.contains("query-secret"))
        assertFalse(log.url.contains("user-secret"))
        assertFalse(log.url.contains("password-secret"))
    }

    @Test fun `disabled logging never duplicates body serialization`() {
        Logging.setRequestLoggingEnabled(false)
        val body = CountingBody()
        execute(body)
        assertEquals(1, body.writes)
        assertTrue(Logging.getRequestLogs().isEmpty())
    }

    @Test fun `failed transport rethrows original but fault record omits payload and raw exception text`() {
        val failure = IOException("private-prompt key-secret https://server.test/?key=query-secret /private/user/file")
        val body = CountingBody()
        val client = OkHttpClient.Builder().addInterceptor(RequestLoggingInterceptor())
            .addInterceptor { throw failure }.build()
        val result = runCatching { client.newCall(request(body)).execute() }
        assertSame(failure, result.exceptionOrNull())
        val log = Logging.getRequestLogs().single()
        assertTrue(log.error!!.contains("http.request"))
        val record = listOf(log.url, log.requestBody, log.error, log.requestHeaders.toString()).joinToString()
        for (secret in listOf("sample payload", "private-prompt", "key-secret", "query-secret", "/private/user")) {
            assertFalse(record.contains(secret))
        }
    }

    @Test fun `optional capture failure does not stop actual request and oversized capture is bounded`() {
        val body = CountingBody(failFirstWrite = true)
        execute(body)
        assertEquals(2, body.writes)
        assertEquals("[Body capture unavailable]", Logging.getRequestLogs().single().requestBody)
        Logging.clear()
        execute(CountingBody(text = "x".repeat(300_000)))
        val captured = Logging.getRequestLogs().single().requestBody!!
        assertTrue(captured.endsWith("[Body truncated]"))
        assertTrue(captured.length < 132_000)
    }

    private fun execute(body: CountingBody) {
        val client = OkHttpClient.Builder().addInterceptor(RequestLoggingInterceptor())
            .addInterceptor { chain ->
                assertSame(body, chain.request().body)
                Buffer().use { chain.request().body!!.writeTo(it) }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").header("Set-Cookie", "response-secret")
                    .body("ok".toResponseBody()).build()
            }.build()
        client.newCall(request(body)).execute().use { assertEquals(200, it.code) }
    }

    private fun request(body: RequestBody): Request = Request.Builder()
        .url("https://user-secret:password-secret@example.test/v1?key=query-secret")
        .header("Authorization", "Bearer key-secret").header("X-Api-Key", "key-secret")
        .header("Content-Type", "application/json").post(body).build()

    private class CountingBody(
        private val oneShot: Boolean = false,
        private val duplex: Boolean = false,
        private val text: String = "sample payload",
        private val failFirstWrite: Boolean = false,
    ) : RequestBody() {
        var writes = 0
        override fun contentType() = "application/json".toMediaType()
        override fun isOneShot() = oneShot
        override fun isDuplex() = duplex
        override fun writeTo(sink: BufferedSink) {
            writes++
            if ((oneShot || duplex) && writes > 1) throw IOException("body consumed twice")
            if (failFirstWrite && writes == 1) throw IOException("capture failed")
            sink.writeUtf8(text)
        }
    }
}
