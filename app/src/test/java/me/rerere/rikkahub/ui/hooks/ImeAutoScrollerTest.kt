package me.rerere.rikkahub.ui.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

class ImeAutoScrollerTest {
    @Test
    fun `repeated keyboard opening includes the zero frame`() {
        val heights = listOf(0, 100, 200, 300, 200, 100, 0, 100, 200, 300)
        val deltas = heights.zipWithNext(::imeScrollDelta)
        assertEquals(listOf(100, 100, 100, -100, -100, -100, 100, 100, 100), deltas)
        assertEquals(300, deltas.sum())
    }

    @Test
    fun `closing restores the original reading anchor`() {
        val heights = listOf(0, 80, 250, 400, 310, 150, 0)
        assertEquals(0, heights.zipWithNext(::imeScrollDelta).sum())
    }

    @Test
    fun `same height does not scroll and resize uses delta`() {
        assertEquals(0, imeScrollDelta(320, 320))
        assertEquals(80, imeScrollDelta(320, 400))
        assertEquals(-400, imeScrollDelta(400, 0))
    }
}
