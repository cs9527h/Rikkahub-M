package me.rerere.tts.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import okhttp3.Call
import okhttp3.Response

/** Keeps cancellation attached during blocking reads as well as while waiting for headers. */
internal suspend inline fun <T> Call.useCancellable(block: (Response) -> T): T {
    val canceller = Job(currentCoroutineContext().job)
    canceller.invokeOnCompletion { if (it is CancellationException) cancel() }
    return try {
        currentCoroutineContext().ensureActive()
        val result = execute().use(block)
        currentCoroutineContext().ensureActive()
        result
    } catch (error: Exception) {
        // Cancelling a socket may report IOException; don't mistake it for provider failure.
        currentCoroutineContext().ensureActive()
        throw error
    } finally {
        canceller.complete()
    }
}
