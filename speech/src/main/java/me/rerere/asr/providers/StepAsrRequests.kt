package me.rerere.asr.providers

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns Calls until the entire SSE body has been consumed, not just headers. */
internal class StepAsrRequests(client: OkHttpClient) {
    private val client = client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    private val calls = mutableSetOf<Call>()
    private var disposed = false

    fun dispose() {
        val active = synchronized(this) {
            disposed = true
            calls.toList().also { calls.clear() }
        }
        active.forEach { it.cancel() }
    }

    suspend fun execute(request: Request, consume: (Response) -> String): String =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            val accepted = synchronized(this) {
                if (disposed) {
                    false
                } else {
                    calls.add(call)
                    true
                }
            }
            if (!accepted) {
                call.cancel()
                continuation.cancel()
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    synchronized(this@StepAsrRequests) { calls.remove(call) }
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use(consume)
                        if (continuation.isActive) continuation.resume(text)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    } finally {
                        synchronized(this@StepAsrRequests) { calls.remove(call) }
                    }
                }
            })
        }
}
