package me.rerere.rikkahub.data.ai.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpClientCleanupTest {
    @Test
    fun `cancelled parent still finishes suspended client close before releasing job`() = runTest {
        val closing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val failures = mutableListOf<Exception>()
        var closed = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                closeMcpClientSafely(
                    close = {
                        closing.complete(Unit)
                        release.await()
                        closed = true
                    },
                    onFailure = { failures.add(it) },
                )
            }
        }
        job.cancel()
        runCurrent()
        assertTrue(closing.isCompleted)
        assertFalse(closed)
        assertFalse(job.isCompleted)
        release.complete(Unit)
        job.join()
        assertTrue(closed)
        assertTrue(failures.isEmpty())
        assertTrue(job.isCancelled)
    }

    @Test
    fun `unresponsive suspend close is bounded and runs its cancellation finally`() = runTest {
        val failures = mutableListOf<Exception>()
        var finished = false
        closeMcpClientSafely(
            close = {
                try {
                    awaitCancellation()
                } finally {
                    finished = true
                }
            },
            onFailure = { failures.add(it) },
            timeoutMillis = 50,
        )
        assertTrue(finished)
        assertTrue(failures.single() is TimeoutCancellationException)
    }

    @Test
    fun `cleanup does not downgrade fatal errors to recoverable diagnostics`() = runTest {
        var reported = false
        val result = runCatching {
            closeMcpClientSafely(
                close = { throw AssertionError("fatal close") },
                onFailure = { reported = true },
            )
        }
        assertTrue(result.exceptionOrNull() is AssertionError)
        assertFalse(reported)
    }
}
