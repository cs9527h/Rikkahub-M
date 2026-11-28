package me.rerere.ui.util

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class RecoverableUiOperationTest {
    @Test
    fun `consumer exception is not misreported as operation failure`() {
        val error = IllegalStateException("consumer")
        var failureToast = false
        val caught = runCatching {
            performUiOperation(
                operation = { 42 },
                onFailure = { failureToast = true },
                onSuccess = { throw error },
            )
        }.exceptionOrNull()
        assertSame(error, caught)
        assertFalse(failureToast)
    }

    @Test
    fun `recoverable crop failure reports once and never invokes consumer`() {
        val error = IllegalArgumentException("decode")
        var reported: Exception? = null
        var consumed = false
        performUiOperation<Unit>(
            operation = { throw error },
            onFailure = { reported = it },
            onSuccess = { consumed = true },
        )
        assertSame(error, reported)
        assertFalse(consumed)
    }

    @Test
    fun `cancellation escapes recovery without displaying failure`() {
        val cancellation = CancellationException("stopped")
        var reported = false
        val caught = runCatching {
            performUiOperation<Unit>(
                operation = { throw cancellation },
                onFailure = { reported = true },
                onSuccess = { error("must not consume") },
            )
        }.exceptionOrNull()
        assertSame(cancellation, caught)
        assertFalse(reported)
    }

    @Test
    fun `fatal errors propagate instead of falling back or notifying failure`() {
        listOf(OutOfMemoryError("test"), AssertionError("test"), LinkageError("test")).forEach { fatal ->
            var reported = false
            val caught = runCatching {
                performUiOperation<Unit>(
                    operation = { throw fatal },
                    onFailure = { reported = true },
                    onSuccess = { error("must not consume") },
                )
            }.exceptionOrNull()
            assertSame(fatal, caught)
            assertFalse(reported)
        }
    }

    @Test
    fun `successful operation invokes consumer exactly once with its result`() {
        var invocations = 0
        performUiOperation(
            operation = { 42 },
            onFailure = { error("must not recover") },
            onSuccess = {
                assertEquals(42, it)
                invocations++
            },
        )
        assertEquals(1, invocations)
    }
}
