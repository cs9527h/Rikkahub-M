package me.rerere.rikkahub.data.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import me.rerere.common.util.runCatchingCancellable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Invokes the same resource-owning suspend helper used by FilesManager, not a source-text assertion. */
class HttpImageStreamTest {
    @Test
    fun successClosesInputAndDisconnects() = runBlocking {
        val connection = Connection()
        assertEquals(1, withHttpImageStream(connection) { it.read() })
        assertTrue(connection.closed)
        assertTrue(connection.disconnected)
    }

    @Test
    fun suspendedConsumerCancellationClosesInputDisconnectsAndEscapesResultWrapper() = runBlocking {
        val connection = Connection()
        val entered = CompletableDeferred<Unit>()
        val cancellation = CancellationException("cancel export")
        var observed: Throwable? = null
        val worker = async {
            try {
                runCatchingCancellable {
                    withHttpImageStream(connection) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            } catch (error: Throwable) {
                observed = error
                throw error
            }
        }
        entered.await()
        assertFalse(connection.closed)
        worker.cancel(cancellation)
        try {
            worker.await()
            error("Expected cancellation")
        } catch (error: CancellationException) {
            me.rerere.rikkahub.testutil.assertRecoveredFailure(cancellation, error)
        }
        me.rerere.rikkahub.testutil.assertRecoveredFailure(cancellation, observed)
        assertTrue(connection.closed)
        assertTrue(connection.disconnected)
    }

    @Test
    fun decodeFailureIsReportedAfterBothResourcesClose() = runBlocking {
        val connection = Connection()
        val failure = IOException("decode failed")
        val result = runCatchingCancellable { withHttpImageStream(connection) { throw failure } }
        me.rerere.rikkahub.testutil.assertRecoveredFailure(failure, result.exceptionOrNull())
        assertTrue(connection.closed)
        assertTrue(connection.disconnected)
    }

    @Test
    fun fatalErrorEscapesResultWrapperAfterBothResourcesClose() = runBlocking {
        val connection = Connection()
        val fatal = AssertionError("fatal decoder")
        try {
            runCatchingCancellable { withHttpImageStream(connection) { throw fatal } }
            error("Expected fatal error")
        } catch (error: AssertionError) {
            me.rerere.rikkahub.testutil.assertRecoveredFailure(fatal, error)
        }
        assertTrue(connection.closed)
        assertTrue(connection.disconnected)
    }

    @Test
    fun connectAndHttpStatusFailuresAlsoDisconnectWithoutOpeningBody() = runBlocking {
        val connectFailure = Connection().apply { failure = IOException("connect failed") }
        assertTrue(runCatchingCancellable { withHttpImageStream(connectFailure) { } }.isFailure)
        assertTrue(connectFailure.disconnected)
        assertFalse(connectFailure.opened)
        val rejected = Connection().apply { status = 403 }
        assertTrue(runCatchingCancellable { withHttpImageStream(rejected) { } }.isFailure)
        assertTrue(rejected.disconnected)
        assertFalse(rejected.opened)
    }

    private class Connection : HttpURLConnection(URL("https://example.invalid/private?token=secret")) {
        var closed = false
        var disconnected = false
        var opened = false
        var status = HTTP_OK
        var failure: IOException? = null
        override fun connect() {
            failure?.let { throw it }
        }
        override fun disconnect() {
            disconnected = true
        }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            init {
                opened = true
            }
            override fun close() {
                closed = true
                super.close()
            }
        }
    }
}
