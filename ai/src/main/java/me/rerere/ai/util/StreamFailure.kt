package me.rerere.ai.util

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import okhttp3.Response
import java.io.IOException

/** Malformed/empty error bodies must never turn an SSE failure into successful completion. */
internal fun streamFailure(
    operation: String,
    error: Throwable?,
    response: Response?,
    decode: (String) -> Throwable = {
        // Lenient JSON can parse an arbitrary error page as a primitive. It is not the provider's
        // structured error contract; retain the HTTP status rather than displaying its raw body.
        val detail = json.parseToJsonElement(it)
        require(detail is JsonObject) { "Expected a structured provider error" }
        detail.parseErrorDetail()
    },
): Throwable {
    if (error is CancellationException) return error
    var failure = error ?: IOException("$operation failed (HTTP ${response?.code ?: "unknown"})")
    try {
        // RealEventSource owns the Response; peek keeps that ownership and bounds diagnostics parsing.
        val body = response?.peekBody(16 * 1024)?.string()
        if (!body.isNullOrBlank()) {
            val detail = decode(body)
            if (error == null) failure = detail else failure.addSuppressed(detail)
        }
    } catch (parseError: Exception) {
        if (parseError is CancellationException) return parseError
        failure.addSuppressed(parseError)
    }
    return failure
}
