package me.rerere.rikkahub.ui.pages.mediacreation

import android.util.Log
import kotlinx.coroutines.CancellationException
import me.rerere.common.util.formatExceptionForLog

internal fun reportMediaFailure(operation: String, error: Throwable) {
    if (error is CancellationException) throw error
    if (error is Error) throw error
    Log.e("MediaCreation", formatExceptionForLog(operation, error))
}
