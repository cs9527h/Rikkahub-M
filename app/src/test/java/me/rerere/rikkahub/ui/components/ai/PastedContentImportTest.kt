package me.rerere.rikkahub.ui.components.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PastedContentImportTest {
    @Test
    fun `slow provider copies off caller and publishes back before grant release`() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ui ->
            runBlocking(ui) {
                val caller = Thread.currentThread()
                var released = false
                importPastedContent(
                    copy = {
                        assertNotEquals(caller, Thread.currentThread())
                        assertFalse(released)
                        "image"
                    },
                    onImported = {
                        assertSame(caller, Thread.currentThread())
                        assertEquals("image", it)
                        assertFalse(released)
                    },
                    release = { released = true },
                )
                assertTrue(released)
            }
        }
    }

    @Test
    fun `provider failure releases grant and does not publish`() = runBlocking {
        var releases = 0
        var published = false
        try {
            importPastedContent(
                copy = { throw IOException("provider unavailable") },
                onImported = { published = true },
                release = { releases++ },
            )
        } catch (_: IOException) {
            // UI receives this and shows failure instead of success.
        }
        assertFalse(published)
        assertEquals(1, releases)
    }

    @Test
    fun `cancellation waits for blocking copy then releases without stale UI update`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CountDownLatch(1)
        var releases = 0
        var published = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            importPastedContent(
                copy = {
                    started.complete(Unit)
                    check(finish.await(5, TimeUnit.SECONDS))
                    "image"
                },
                onImported = { published = true },
                release = { releases++ },
            )
        }
        started.await()
        job.cancel()
        finish.countDown()
        job.join()
        assertFalse(published)
        assertEquals(1, releases)
    }
}
