package me.rerere.ui.util

import kotlinx.coroutines.CancellationException

/** Recovery belongs to the operation, not to the caller consuming its successful result. */
internal inline fun <T> performUiOperation(
    operation: () -> T,
    onFailure: (Exception) -> Unit,
    onSuccess: (T) -> Unit,
) {
    val result = try {
        operation()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        onFailure(error)
        return
    }
    // Deliberately outside the catch: caller errors must reach the owner's exception handler.
    onSuccess(result)
}
