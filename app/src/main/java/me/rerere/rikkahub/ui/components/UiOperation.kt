package me.rerere.rikkahub.ui.components

import me.rerere.common.util.formatExceptionForLog
import me.rerere.common.util.runCatchingCancellable

/** One UI action: cancellation is control flow, diagnostics are payload-free, busy cleanup always runs. */
internal suspend fun <T> runUiOperation(
    operation: String,
    logFailure: (String) -> Unit,
    onFinally: () -> Unit = {},
    block: suspend () -> T,
): Result<T> = try {
    runCatchingCancellable { block() }.onFailure { logFailure(formatExceptionForLog(operation, it)) }
} finally {
    onFinally()
}
