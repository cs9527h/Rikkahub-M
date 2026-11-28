package me.rerere.rikkahub.data.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BuiltinSkillExtractionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `failed extraction leaves enabled skills intact and retries before marking success`() {
        val output = folder.root.resolve("SKILL.md")
        val failure = IOException("temporary extraction failure")
        var attempts = 0
        val extraction = BuiltinSkillExtraction {
            if (++attempts == 1) throw failure
            output.writeText("ready")
        }
        val enabled = linkedSetOf("builtin")
        val first = runCatching {
            extraction.ensureExtracted()
            if (!output.exists()) enabled.clear() // analogous to catalog pruning after extraction
        }
        assertSame(failure, first.exceptionOrNull())
        assertEquals(setOf("builtin"), enabled)
        assertFalse(output.exists())
        extraction.ensureExtracted()
        extraction.ensureExtracted()
        assertEquals(2, attempts)
        assertEquals("ready", output.readText())
    }

    @Test
    fun `concurrent callers publish only one completed extraction`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val extraction = BuiltinSkillExtraction {
            attempts.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            folder.root.resolve("SKILL.md").writeText("complete")
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { extraction.ensureExtracted() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = executor.submit { extraction.ensureExtracted() }
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertEquals(1, attempts.get())
            assertEquals("complete", folder.root.resolve("SKILL.md").readText())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
