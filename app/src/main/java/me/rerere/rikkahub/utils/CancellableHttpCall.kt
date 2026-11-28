package me.rerere.rikkahub.utils

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.Call

/** Own the Call through body consumption, including a blocking execute/read on an IO thread. */
internal suspend fun <T> withCancellableHttpCall(call: Call, consume: suspend (Call) -> T): T = coroutineScope {
    val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        consume(call)
    } finally {
        canceller.cancel()
    }
}
