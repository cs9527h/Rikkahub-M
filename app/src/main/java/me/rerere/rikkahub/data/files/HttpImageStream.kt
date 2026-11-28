package me.rerere.rikkahub.data.files

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.net.HttpURLConnection

/** Keeps HTTP resource ownership inside the consuming coroutine, including a suspended export. */
internal suspend fun <T> withHttpImageStream(
    connection: HttpURLConnection,
    consume: suspend (InputStream) -> T,
): T = try {
    currentCoroutineContext().ensureActive()
    connection.connect()
    check(connection.responseCode == HttpURLConnection.HTTP_OK) {
        "Image download failed with HTTP ${connection.responseCode}"
    }
    connection.inputStream.use { input ->
        currentCoroutineContext().ensureActive()
        consume(input)
    }
} finally {
    connection.disconnect()
}
