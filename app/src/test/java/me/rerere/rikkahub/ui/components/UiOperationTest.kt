package me.rerere.rikkahub.ui.components

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class UiOperationTest {
    @Test fun successfulActionReturnsValueAndResetsBusyOnce() = runBlocking {
        var busy = true
        var cleanups = 0
        val logs = mutableListOf<String>()
        val result = runUiOperation("Backup.exportLocal", logs::add, {
            busy = false
            cleanups++
        }) { 42 }
        assertEquals(42, result.getOrThrow())
        assertFalse(busy)
        assertEquals(1, cleanups)
        assertTrue(logs.isEmpty())
    }

    @Test fun recoverableFailureRetainsOriginalForUiButLogsOnlyPayloadFreeDiagnostics() = runBlocking {
        val secret = "key-sk-private prompt-personal /sdcard/private/path https://host/?token=secret"
        val cause = IllegalArgumentException(secret)
        val error = IOException(secret, cause)
        val logs = mutableListOf<String>()
        var busy = true
        val result = runUiOperation("Backup.upload", logs::add, { busy = false }) { throw error }
        assertSame(error, result.exceptionOrNull())
        assertFalse(busy)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("Backup.upload"))
        assertTrue(logs.single().contains("IOException"))
        assertTrue(logs.single().contains("IllegalArgumentException"))
        assertFalse(logs.single().contains(secret))
        assertFalse(logs.single().contains("key-sk-private"))
        assertFalse(logs.single().contains("/sdcard/private/path"))
    }

    @Test fun explicitCancellationEscapesWithoutFailureToastAndStillResetsBusy() = runBlocking {
        val cancellation = CancellationException("user left screen")
        val logs = mutableListOf<String>()
        var busy = true
        var failureUiCalls = 0
        try {
            runUiOperation("Backup.import", logs::add, { busy = false }) { throw cancellation }
                .onFailure { failureUiCalls++ }
            fail("Cancellation must remain coroutine control flow")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertFalse(busy)
        assertEquals(0, failureUiCalls)
        assertTrue(logs.isEmpty())
    }

    @Test fun cancellingSuspendedScreenActionCleansUpWithoutPublishingFailure() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val logs = mutableListOf<String>()
        var busy = true
        var cleanups = 0
        var failures = 0
        val action = launch {
            runUiOperation("Backup.testWebDav", logs::add, {
                busy = false
                cleanups++
            }) {
                entered.complete(Unit)
                awaitCancellation()
            }.onFailure { failures++ }
        }
        entered.await()
        action.cancelAndJoin()
        assertTrue(action.isCancelled)
        assertFalse(busy)
        assertEquals(1, cleanups)
        assertEquals(0, failures)
        assertTrue(logs.isEmpty())
    }

    @Test fun fatalJvmErrorEscapesInsteadOfBecomingRecoverableUiResult() = runBlocking {
        val fatal = AssertionError("fatal")
        val logs = mutableListOf<String>()
        var cleanups = 0
        try {
            runUiOperation("Backup.exportLocal", logs::add, { cleanups++ }) { throw fatal }
            fail("Fatal JVM errors must escape")
        } catch (actual: AssertionError) {
            assertSame(fatal, actual)
        }
        assertEquals(1, cleanups)
        assertTrue(logs.isEmpty())
    }

    @Test fun failedActionDoesNotLeaveRetryBusyOrPoisonSubsequentSuccess() = runBlocking {
        var busy = true
        val logs = mutableListOf<String>()
        val first = runUiOperation("Backup.exportLocal", logs::add, { busy = false }) { throw IOException() }
        assertTrue(first.isFailure)
        assertFalse(busy)
        busy = true
        val second = runUiOperation("Backup.exportLocal", logs::add, { busy = false }) { "saved" }
        assertEquals("saved", second.getOrThrow())
        assertFalse(busy)
        assertEquals(1, logs.size)
    }
}
