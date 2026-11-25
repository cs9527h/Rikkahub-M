package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PngTextChunkReaderTest {

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val length = data.size
        out.write(
            byteArrayOf((length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte())
        )
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(data)
        out.write(ByteArray(4)) // 占位 CRC，读取端不校验
        return out.toByteArray()
    }

    private fun png(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        chunks.forEach { out.write(it) }
        return out.toByteArray()
    }

    @Test
    fun `reads tEXt chunk text`() {
        val text = "[chara:eyJzcGVjIjoidjIifQ==]"
        val bytes = png(
            chunk("IHDR", ByteArray(13)),
            chunk("tEXt", "chara\u0000$text".toByteArray(Charsets.ISO_8859_1)),
            chunk("IEND", ByteArray(0)),
        )
        val chunks = PngTextChunkReader.readTextChunks(ByteArrayInputStream(bytes))
        assertEquals(listOf(text), chunks)
    }

    @Test
    fun `reads iTXt chunk text`() {
        val text = "[chara:\u4e2d\u6587]"
        val payload = ByteArrayOutputStream().apply {
            write("chara".toByteArray(Charsets.ISO_8859_1))
            write(0)
            write(0) // 未压缩
            write(0)
            write(0) // 空语言标签的终止符
            write(0) // 空翻译关键字
            write(text.toByteArray(Charsets.UTF_8))
        }.toByteArray()
        val chunks = PngTextChunkReader.readTextChunks(
            ByteArrayInputStream(png(chunk("IHDR", ByteArray(13)), chunk("iTXt", payload), chunk("IEND", ByteArray(0))))
        )
        assertEquals(listOf(text), chunks)
    }

    @Test
    fun `returns empty for non png input`() {
        val chunks = PngTextChunkReader.readTextChunks(ByteArrayInputStream("not a png at all".toByteArray()))
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `returns empty for png without text chunks`() {
        val chunks = PngTextChunkReader.readTextChunks(
            ByteArrayInputStream(png(chunk("IHDR", ByteArray(13)), chunk("IEND", ByteArray(0))))
        )
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `text chunk after an oversized image block is still read`() {
        // 4K 立绘的 IDAT 常超过单块上限；过去遇到它就 break，卡数据整段读不到。
        val text = "[chara:huge]"
        val bytes = png(
            chunk("IHDR", ByteArray(13)),
            chunk("IDAT", ByteArray(9 * 1024 * 1024)),
            chunk("tEXt", "chara\u0000$text".toByteArray(Charsets.ISO_8859_1)),
            chunk("IEND", ByteArray(0)),
        )
        assertEquals(listOf(text), PngTextChunkReader.readTextChunks(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `total text size is capped instead of exhausting the heap`() {
        val text = "a".repeat(7 * 1024 * 1024)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        repeat(3) { out.write(chunk("tEXt", "k\u0000$text".toByteArray(Charsets.ISO_8859_1))) }
        out.write(chunk("IEND", ByteArray(0)))
        val chunks = PngTextChunkReader.readTextChunks(ByteArrayInputStream(out.toByteArray()))
        assertTrue("至少读到一段", chunks.isNotEmpty())
        assertTrue("总量应被限制", chunks.sumOf { it.length } <= 8 * 1024 * 1024)
    }
}
