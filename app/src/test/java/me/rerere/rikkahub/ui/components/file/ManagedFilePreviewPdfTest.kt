package me.rerere.rikkahub.ui.components.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedFilePreviewPdfTest {
    @Test
    fun ordinaryPageKeepsAspectRatioWithinPixelBudget() {
        val (width, height) = managedPdfPreviewDimensions(595, 842)
        assertTrue(width in 1..1440)
        assertTrue(height in 1..2048)
        assertTrue(width.toLong() * height <= 3_000_000L)
        assertTrue(kotlin.math.abs(width.toDouble() / height - 595.0 / 842) < 0.002)
    }

    @Test
    fun extremeAspectRatioNeverCreatesZeroSizeOrOversizedBitmap() {
        val wide = managedPdfPreviewDimensions(Int.MAX_VALUE, 1)
        val tall = managedPdfPreviewDimensions(1, Int.MAX_VALUE)
        assertEquals(1, wide.second)
        assertEquals(1, tall.first)
        for ((width, height) in listOf(wide, tall, managedPdfPreviewDimensions(Int.MAX_VALUE, Int.MAX_VALUE))) {
            assertTrue(width in 1..1440)
            assertTrue(height in 1..2048)
            assertTrue(width.toLong() * height <= 3_000_000L)
        }
    }

    @Test
    fun invalidDimensionsFailBeforeAllocation() {
        assertTrue(runCatching { managedPdfPreviewDimensions(0, 1) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { managedPdfPreviewDimensions(1, -1) }.exceptionOrNull() is IllegalArgumentException)
    }
}
