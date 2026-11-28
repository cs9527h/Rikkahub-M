package me.rerere.rikkahub.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** Catch outside IO dispatch so cancellation during result handoff also rolls back orphan inputs. */
internal suspend fun <T> withMediaInputCleanup(
    directory: File,
    isPersisted: suspend () -> Boolean,
    block: suspend () -> T,
): T = try {
    block()
} catch (failure: Throwable) {
    try {
        // Only rollback is non-cancellable. Never delete inputs of a committed DB record.
        withContext(NonCancellable + Dispatchers.IO) {
            if (!isPersisted()) {
                check(!directory.exists() || directory.deleteRecursively()) {
                    "Media input rollback failed"
                }
            }
        }
    } catch (cleanupFailure: Throwable) {
        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
    }
    throw failure
}
