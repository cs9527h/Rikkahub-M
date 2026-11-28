package me.rerere.rikkahub.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException

class AssetResponseTest {
    @Test fun missingAssetCanSelectFallbackBeforeResponseStarts() = runBlocking {
        var streamed = false
        val result = respondAsset(
            open = { throw FileNotFoundException("missing optional asset") },
            onOpenFailure = { "fallback" },
        ) {
            streamed = true
            "asset"
        }
        assertEquals("fallback", result)
        assertTrue(!streamed)
    }

    @Test fun cancellationAfterResponseStartsDoesNotGenerateFallbackAndClosesInput() = runBlocking {
        val input = TrackingInput()
        val cancellation = CancellationException("peer disconnected")
        var fallbacks = 0
        try {
            respondAsset(open = { input }, onOpenFailure = { fallbacks++ }) {
                it.read()
                throw cancellation
            }
            fail("Expected original cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertEquals(0, fallbacks)
        assertTrue(input.closed)
    }

    @Test fun streamIoFailureDoesNotAttemptSecondHttpResponse() = runBlocking {
        val input = TrackingInput()
        val failure = IOException("write after headers committed")
        var fallbacks = 0
        try {
            respondAsset(open = { input }, onOpenFailure = { fallbacks++ }) {
                throw failure
            }
            fail("Expected stream error")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }
        assertEquals(0, fallbacks)
        assertTrue(input.closed)
    }

    @Test fun successfulAssetStreamClosesInputAndReturnsResponse() = runBlocking {
        val input = TrackingInput()
        val result = respondAsset(open = { input }, onOpenFailure = { "fallback" }) {
            assertEquals(65, it.read())
            "streamed"
        }
        assertEquals("streamed", result)
        assertTrue(input.closed)
    }

    @Test fun cancellationWhileOpeningIsNotConvertedToMissingAsset() = runBlocking {
        val cancellation = CancellationException("request cancelled")
        var fallbacks = 0
        try {
            respondAsset(open = { throw cancellation }, onOpenFailure = { fallbacks++ }) { Unit }
            fail("Expected opening cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertEquals(0, fallbacks)
    }

    private class TrackingInput : ByteArrayInputStream(byteArrayOf(65)) {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }
}
