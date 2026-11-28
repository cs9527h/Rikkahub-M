package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import me.rerere.common.util.formatExceptionForLog
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) return chain.proceed(chain.request())
        val request = chain.request()
        val started = System.currentTimeMillis()
        val requestHeaders = request.headers.forLog()
        val requestBody = request.body.forLog()
        val response = try {
            chain.proceed(request)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Logging.logRequest(LogEntry.RequestLog(
                tag = "HTTP",
                url = request.url.forLog(failure = true),
                method = request.method,
                requestHeaders = requestHeaders,
                // Fault diagnostics never retain a prompt/body, even when explicit HTTP tracing is enabled.
                requestBody = "[Body omitted from failure diagnostic]",
                error = formatExceptionForLog("http.request", error),
            ))
            throw error
        }
        Logging.logRequest(LogEntry.RequestLog(
            tag = "HTTP",
            url = request.url.forLog(failure = false),
            method = request.method,
            requestHeaders = requestHeaders,
            requestBody = requestBody,
            responseCode = response.code,
            responseHeaders = response.headers.forLog(),
            durationMs = System.currentTimeMillis() - started,
        ))
        return response
    }
}

private fun Headers.forLog(): Map<String, String> = names().associateWith { name ->
    if (name.lowercase() in PUBLIC_HEADER_VALUES) get(name).orEmpty() else "██"
}

private val PUBLIC_HEADER_VALUES = setOf(
    "accept", "accept-encoding", "content-type", "content-length", "content-encoding",
    "transfer-encoding", "connection", "date", "server", "retry-after", "cache-control",
)

private fun HttpUrl.forLog(failure: Boolean): String = newBuilder()
    .username("").password("").query(null).fragment(null)
    .apply {
        if (failure) {
            encodedPath("/")
        } else {
            // Retain parameter names for opt-in tracing, never credential or prompt values.
            this@forLog.queryParameterNames.forEach { addQueryParameter(it, "██") }
        }
    }.build().toString()

private fun RequestBody?.forLog(): String? {
    val body = this ?: return null
    // Logging must never consume bodies that the transport is permitted to write only once.
    if (body.isOneShot() || body.isDuplex()) return "[One-shot or duplex body not captured]"
    val captured = Buffer()
    var total = 0L
    val sink = object : Sink {
        override fun write(source: Buffer, byteCount: Long) {
            val keep = minOf(byteCount, (MAX_CAPTURE_BYTES - captured.size).coerceAtLeast(0L))
            captured.write(source, keep)
            source.skip(byteCount - keep)
            total += byteCount
        }
        override fun flush() = Unit
        override fun close() = Unit
        override fun timeout(): Timeout = Timeout.NONE
    }
    return try {
        sink.buffer().use { body.writeTo(it) }
        captured.readUtf8() + if (total > MAX_CAPTURE_BYTES) "\n[Body truncated]" else ""
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        // Capture is optional; the actual request must still be executed once by the real transport.
        "[Body capture unavailable]"
    }
}

private const val MAX_CAPTURE_BYTES = 128L * 1024
