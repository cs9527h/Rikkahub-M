package me.rerere.common.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableResultTest {
    @Test
    fun `ordinary exceptions preserve Result failure contract and cause`() {
        val error = IllegalStateException("private payload")
        val result = runCatchingCancellable<Unit> { throw error }
        assertTrue(result.isFailure)
        assertSame(error, result.exceptionOrNull())
        assertEquals(42, runCatchingCancellable { 42 }.getOrThrow())
    }

    @Test
    fun `explicit cancellation escapes instead of becoming Result failure`() {
        val cancellation = CancellationException("stopped")
        val caught = runCatching { runCatchingCancellable<Unit> { throw cancellation } }.exceptionOrNull()
        assertSame(cancellation, caught)
    }

    @Test
    fun `fatal JVM errors escape without invoking Result fallbacks`() {
        listOf(OutOfMemoryError("test"), AssertionError("test"), LinkageError("test")).forEach { fatal ->
            var recovered = false
            val caught = runCatching {
                runCatchingCancellable<Unit> { throw fatal }.getOrElse { recovered = true }
            }.exceptionOrNull()
            assertSame(fatal, caught)
            assertFalse(recovered)
        }
    }

    @Test
    fun `suspended coroutine cancellation skips Result failure fallback and releases resources`() = runBlocking {
        var fallbackRan = false
        var released = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                runCatchingCancellable { awaitCancellation() }.onFailure { fallbackRan = true }
            } finally {
                released = true
            }
        }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(released)
        assertFalse(fallbackRan)
    }
}
