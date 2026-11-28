package me.rerere.rikkahub.utils

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ImageExportIoTest {
    @Test
    fun copyClosesBothStreamsAndPreservesBytes() {
        var inputClosed = false
        var outputClosed = false
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun close() { inputClosed = true; super.close() }
        }
        val output = object : ByteArrayOutputStream() {
            override fun close() { outputClosed = true; super.close() }
        }
        copyImageExport(output) { input }
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        assertTrue(inputClosed)
        assertTrue(outputClosed)
    }

    @Test
    fun inputOpenFailureClosesOutputAndRollsBackPendingFile() {
        val root = Files.createTempDirectory("image-export-open").toFile()
        val pending = File(root, "pending.png").apply { writeBytes(byteArrayOf(1)) }
        var outputClosed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() { outputClosed = true; super.close() }
        }
        val failure = IOException("open failed")
        try {
            val actual = runCatching {
                withImageExportRollback(rollback = { pending.delete() }) {
                    copyImageExport(output) { throw failure }
                }
            }.exceptionOrNull()
            assertSame(failure, actual)
            assertTrue(outputClosed)
            assertFalse(pending.exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun writeFailureClosesBothStreamsAndRollbackKeepsOriginalFailure() {
        var inputClosed = false
        var outputClosed = false
        var rolledBack = false
        val failure = IOException("write failed")
        val cleanupFailure = IOException("rollback failed")
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun close() { inputClosed = true; super.close() }
        }
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) { throw failure }
            override fun close() { outputClosed = true; super.close() }
        }
        val actual = runCatching {
            withImageExportRollback(rollback = { rolledBack = true; throw cleanupFailure }) {
                copyImageExport(output) { input }
            }
        }.exceptionOrNull()
        assertSame(failure, actual)
        assertSame(cleanupFailure, actual!!.suppressed.single())
        assertTrue(inputClosed)
        assertTrue(outputClosed)
        assertTrue(rolledBack)
    }

    @Test
    fun outputCloseFailureRollsBackInsteadOfReportingSuccess() {
        val root = Files.createTempDirectory("image-export-close").toFile()
        val pending = File(root, "pending.png")
        val failure = IOException("close failed")
        val output = object : java.io.FileOutputStream(pending) {
            override fun close() { super.close(); throw failure }
        }
        try {
            val actual = runCatching {
                withImageExportRollback(rollback = { pending.delete() }) {
                    copyImageExport(output) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
                }
            }.exceptionOrNull()
            assertSame(failure, actual)
            assertFalse(pending.exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun fatalAndCancellationFailuresStillRollbackAndEscape() {
        for (failure in listOf(kotlinx.coroutines.CancellationException("cancel"), AssertionError("fatal"))) {
            var rolledBack = false
            val actual = runCatching {
                withImageExportRollback(rollback = { rolledBack = true }) { throw failure }
            }.exceptionOrNull()
            assertSame(failure, actual)
            assertTrue(rolledBack)
        }
    }
}
