package me.rerere.rikkahub.data.ai.mcp

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Terminal resource cleanup must run after parent cancellation, but must never hold a session lock forever. */
internal suspend fun closeMcpClientSafely(
    close: suspend () -> Unit,
    onFailure: (Exception) -> Unit,
    timeoutMillis: Long = 5_000,
) = withContext(NonCancellable) {
    try {
        withTimeout(timeoutMillis) { close() }
    } catch (error: Exception) {
        // A cleanup-local timeout/cancellation is an incomplete close, not a replacement of the
        // parent's original cancellation. Fatal JVM errors are deliberately not recovered.
        onFailure(error)
    }
}
