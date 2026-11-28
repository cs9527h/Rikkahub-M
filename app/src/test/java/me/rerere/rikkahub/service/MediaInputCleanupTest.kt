package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MediaInputCleanupTest {
    @Test
    fun cancellationOnIoResultHandoffDeletesOrphanInputs() = runBlocking {
        val root = Files.createTempDirectory("media-input-cancel").toFile()
        val directory = File(root, "record")
        val owner = Job()
        try {
            val failure = runCatching {
                withContext(owner) {
                    withMediaInputCleanup(directory, isPersisted = { false }) {
                        withContext(Dispatchers.IO) {
                            directory.mkdirs()
                            File(directory, "in_0.png").writeBytes(byteArrayOf(1, 2, 3))
                            owner.cancel(CancellationException("cancel at dispatch handoff"))
                            "copied"
                        }
                    }
                }
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertFalse(directory.exists())
        } finally { owner.cancel(); root.deleteRecursively() }
    }

    @Test
    fun committedRecordInputsSurviveLaterSubmissionFailure() = runBlocking {
        val directory = Files.createTempDirectory("media-input-committed").toFile()
        val input = File(directory, "in_0.png").apply { writeText("committed input") }
        val failure = IllegalStateException("session update failed")
        try {
            val actual = runCatching {
                withMediaInputCleanup(directory, isPersisted = { true }) { throw failure }
            }.exceptionOrNull()
            me.rerere.rikkahub.testutil.assertRecoveredFailure(failure, actual)
            assertTrue(input.isFile)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun cleanupFailureDoesNotReplaceCancellationOrFatalError() = runBlocking {
        val directory = Files.createTempDirectory("media-input-error").toFile()
        try {
            for (failure in listOf(CancellationException("cancel"), AssertionError("fatal"))) {
                val cleanupFailure = IllegalStateException("database unavailable")
                val actual = runCatching {
                    withMediaInputCleanup(directory, isPersisted = { throw cleanupFailure }) { throw failure }
                }.exceptionOrNull()
                me.rerere.rikkahub.testutil.assertRecoveredFailure(failure, actual)
                me.rerere.rikkahub.testutil.assertRecoveredFailure(cleanupFailure, actual!!.suppressed.single())
                assertTrue(directory.exists()) // Fail closed when DB ownership cannot be established.
            }
        } finally { directory.deleteRecursively() }
    }
}
