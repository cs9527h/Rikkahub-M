package me.rerere.rikkahub.ui.pages.setting

import android.util.Log
import kotlinx.coroutines.CancellationException
import me.rerere.common.util.formatExceptionForLog

/** Leaf UI error boundary: cancellation is control flow; diagnostics never include input or paths. */
internal fun reportSettingsFailure(operation: String, error: Throwable) {
    if (error is CancellationException) throw error
    if (error is Error) throw error
    Log.e("Settings", formatExceptionForLog(operation, error))
}
