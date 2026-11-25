package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.utils.writeImageExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class ImageExportResultTest {
    @Test
    fun `successful encoding closes stream before returning`() {
        var closed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
                super.close()
            }
        }
        writeImageExport(output) {
            it.write(byteArrayOf(1, 2, 3))
            true
        }
        assertTrue(closed)
        assertEquals(listOf<Byte>(1, 2, 3), output.toByteArray().toList())
    }

    @Test
    fun `null stream is an export failure`() {
        assertThrows(IllegalStateException::class.java) { writeImageExport(null) { true } }
    }

    @Test
    fun `encoder false is an export failure`() {
        assertThrows(IllegalStateException::class.java) { writeImageExport(ByteArrayOutputStream()) { false } }
    }

    @Test
    fun `close failure is an export failure not success`() {
        val output = object : ByteArrayOutputStream() {
            override fun close() {
                throw IOException("disk full while closing")
            }
        }
        assertThrows(IOException::class.java) { writeImageExport(output) { true } }
    }
}
