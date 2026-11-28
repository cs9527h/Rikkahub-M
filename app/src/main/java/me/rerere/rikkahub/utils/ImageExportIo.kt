package me.rerere.rikkahub.utils

import java.io.InputStream
import java.io.OutputStream

/** Own both streams even when opening the input or closing either stream fails. */
internal fun copyImageExport(output: OutputStream?, openInput: () -> InputStream) {
    checkNotNull(output) { "Image output stream is missing" }.use { target ->
        openInput().use { source -> source.copyTo(target) }
    }
}

internal inline fun <T> withImageExportRollback(rollback: () -> Unit, write: () -> T): T = try {
    write()
} catch (failure: Throwable) {
    try {
        rollback()
    } catch (cleanupFailure: Throwable) {
        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
    }
    throw failure
}
