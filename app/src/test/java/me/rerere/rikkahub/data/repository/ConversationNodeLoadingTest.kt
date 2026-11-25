package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationNodeLoadingTest {
    @Test
    fun `failed page never returns a partial history or skips its small neighbors`() = runBlocking {
        val firstPage = List(64) { "small-$it" }
        val persisted = firstPage + "x".repeat(3_000_000) + List(63) { "neighbor-$it" } + "last-page"
        val offsets = mutableListOf<Int>()
        val failure = IllegalStateException("Row too big to fit into CursorWindow")
        var saved = false
        val result = runCatching {
            val loaded = loadConversationNodePages { limit, offset ->
                assertEquals(64, limit)
                offsets.add(offset)
                if (offset == 64) throw failure
                persisted.drop(offset).take(limit)
            }
            // Mirrors the consumer's load -> edit -> destructive whole-snapshot save sequence.
            assertEquals(persisted, loaded)
            saved = true
        }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(listOf(0, 64), offsets)
        assertTrue(!saved)
        assertEquals(129, persisted.size)
        assertEquals("neighbor-62", persisted[127])
    }

    @Test
    fun `complete pages preserve all nodes and cancellation propagates`() = runBlocking {
        val persisted = List(130) { "node-$it" }
        assertEquals(persisted, loadConversationNodePages { limit, offset -> persisted.drop(offset).take(limit) })
        val cancellation = CancellationException("cancel load")
        val result = runCatching {
            loadConversationNodePages<String> { _, _ -> throw cancellation }
        }
        assertSame(cancellation, result.exceptionOrNull())
    }
}
