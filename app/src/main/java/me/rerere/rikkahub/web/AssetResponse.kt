package me.rerere.rikkahub.web

import java.io.IOException
import java.io.InputStream

/** Only an asset-open failure may select a fallback. Never send a second body after streaming began. */
internal suspend fun <T> respondAsset(
    open: () -> InputStream,
    onOpenFailure: suspend (IOException) -> T,
    respond: suspend (InputStream) -> T,
): T {
    val input = try {
        open()
    } catch (error: IOException) {
        return onOpenFailure(error)
    }
    return input.use { respond(it) }
}
