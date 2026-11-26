package me.rerere.rikkahub.data.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class ManagedFileActionsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun assertPropagatedIOException(actual: Throwable?, original: IOException) {
        assertTrue("Expected IOException, received $actual", actual is IOException)
        assertEquals(original.message, actual?.message)
        // Coroutine stacktrace recovery may copy IOException at a dispatcher boundary. The original
        // instance must still be retained as the exception itself or in its cause chain.
        assertTrue(
            "Original IO failure missing from cause chain: $actual",
            generateSequence(actual) { it.cause }.take(16).any { it === original },
        )
    }

    @Test
    fun recognizesMimeAndCaseInsensitiveExtensions() {
        assertEquals(ManagedFilePreviewKind.IMAGE, managedFilePreviewKind("PHOTO.HEIC", "application/octet-stream"))
        assertEquals(ManagedFilePreviewKind.IMAGE, managedFilePreviewKind("blob", "IMAGE/PNG; charset=utf-8"))
        assertEquals(ManagedFilePreviewKind.PDF, managedFilePreviewKind("paper.PDF", "application/octet-stream"))
        assertEquals(ManagedFilePreviewKind.MARKDOWN, managedFilePreviewKind("README.md", "text/plain"))
        assertEquals(ManagedFilePreviewKind.CODE, managedFilePreviewKind("build.gradle.kts", "text/plain"))
        assertEquals(ManagedFilePreviewKind.CODE, managedFilePreviewKind("blob", "application/problem+json"))
        assertEquals(ManagedFilePreviewKind.TEXT, managedFilePreviewKind("note.log", "application/octet-stream"))
        assertEquals(ManagedFilePreviewKind.AUDIO, managedFilePreviewKind("voice.OPUS", "application/octet-stream"))
        assertEquals(ManagedFilePreviewKind.VIDEO, managedFilePreviewKind("movie", "video/mp4"))
        assertEquals(ManagedFilePreviewKind.OTHER, managedFilePreviewKind("unknown.xyz", "application/octet-stream"))
    }

    @Test
    fun htmlAndSvgAreSourceNotExecutableOrImagePreview() {
        assertEquals(ManagedFilePreviewKind.CODE, managedFilePreviewKind("page.HTML", "image/png"))
        assertEquals(ManagedFilePreviewKind.CODE, managedFilePreviewKind("image.svg", "image/svg+xml"))
        assertEquals(ManagedFilePreviewKind.CODE, managedFilePreviewKind("blob", "application/xhtml+xml"))
    }

    @Test
    fun boundedReadConsumesOnlyCapPlusOneWithoutDependingOnFileLength() = runBlocking {
        var readCount = 0
        val infiniteInput = object : InputStream() {
            override fun read(): Int {
                readCount++
                return 'a'.code
            }
        }
        val preview = readManagedFileTextPreview(infiniteInput, 10)
        assertEquals("aaaaaaaaaa", preview.text)
        assertEquals(10, preview.bytesRead)
        assertTrue(preview.truncated)
        assertEquals(11, readCount)
    }

    @Test
    fun exactCapIsNotReportedAsTruncated() = runBlocking {
        val preview = readManagedFileTextPreview(ByteArrayInputStream("12345".toByteArray()), 5)
        assertEquals("12345", preview.text)
        assertFalse(preview.truncated)
    }

    @Test
    fun truncatedUtf8DoesNotProduceBrokenLastCharacter() = runBlocking {
        val preview = readManagedFileTextPreview(ByteArrayInputStream("a中文".toByteArray()), 5)
        assertEquals("a中", preview.text)
        assertTrue(preview.truncated)
    }

    @Test
    fun supportsUtf8AndUtf16ByteOrderMarks() = runBlocking {
        val utf8 = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "你好".toByteArray()
        assertEquals("你好", readManagedFileTextPreview(ByteArrayInputStream(utf8), 32).text)
        val utf16 = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "你好".toByteArray(Charsets.UTF_16LE)
        assertEquals("你好", readManagedFileTextPreview(ByteArrayInputStream(utf16), 32).text)
    }

    @Test
    fun binaryDataIsReportedInsteadOfDisplayingNulBytes() = runBlocking {
        val error = runCatching {
            readManagedFileTextPreview(ByteArrayInputStream(byteArrayOf(1, 0, 2)), 32)
        }.exceptionOrNull()
        assertTrue(error is IOException)
    }

    @Test
    fun invalidPreviewLimitFailsBeforeAllocating() = runBlocking {
        assertTrue(runCatching {
            readManagedFileTextPreview(ByteArrayInputStream(byteArrayOf()), Int.MAX_VALUE)
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun exportCopiesAllBytesAndKeepsOriginal() = runBlocking {
        val source = temporaryFolder.newFile("source.bin")
        val bytes = ByteArray(30_000) { (it % 251).toByte() }
        source.writeBytes(bytes)
        val output = ByteArrayOutputStream()
        val count = exportManagedFileToStream(source) { output }
        assertEquals(bytes.size.toLong(), count)
        assertArrayEquals(bytes, output.toByteArray())
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test
    fun emptySourceSuccessfullyCreatesAnEmptyExport() = runBlocking {
        val source = temporaryFolder.newFile("empty.txt")
        val output = ByteArrayOutputStream()
        assertEquals(0L, exportManagedFileToStream(source) { output })
        assertEquals(0, output.size())
        assertTrue(source.isFile)
    }

    @Test
    fun missingSourceDoesNotOpenDestination() = runBlocking {
        var opened = false
        val source = java.io.File(temporaryFolder.root, "missing")
        val error = runCatching {
            exportManagedFileToStream(source) {
                opened = true
                ByteArrayOutputStream()
            }
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(opened)
    }

    @Test
    fun directoryIsNotExported() = runBlocking {
        var opened = false
        val error = runCatching {
            exportManagedFileToStream(temporaryFolder.root) {
                opened = true
                ByteArrayOutputStream()
            }
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(opened)
    }

    @Test
    fun nullProviderStreamFailsWithoutTouchingSource() = runBlocking {
        val source = temporaryFolder.newFile("source.txt").apply { writeText("keep") }
        val error = runCatching { exportManagedFileToStream(source) { null } }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals("keep", source.readText())
    }

    @Test
    fun outputWriteFailureClosesStreamAndPreservesOriginal() = runBlocking {
        val source = temporaryFolder.newFile("source.txt").apply { writeText("keep") }
        var closed = false
        val failure = IOException("disk full")
        val error = runCatching {
            exportManagedFileToStream(source) {
                object : OutputStream() {
                    override fun write(value: Int) {
                        throw failure
                    }
                    override fun close() {
                        closed = true
                    }
                }
            }
        }.exceptionOrNull()
        assertPropagatedIOException(error, failure)
        assertTrue(closed)
        assertEquals("keep", source.readText())
    }

    @Test
    fun flushFailureIsNotReportedAsSuccessfulExport() = runBlocking {
        val source = temporaryFolder.newFile("source.txt").apply { writeText("keep") }
        var closed = false
        val failure = IOException("flush failed")
        val error = runCatching {
            exportManagedFileToStream(source) {
                object : ByteArrayOutputStream() {
                    override fun flush() {
                        throw failure
                    }
                    override fun close() {
                        closed = true
                    }
                }
            }
        }.exceptionOrNull()
        assertPropagatedIOException(error, failure)
        assertTrue(closed)
        assertEquals("keep", source.readText())
    }

    @Test
    fun providerCloseFailureIsNotReportedAsSuccess() = runBlocking {
        val source = temporaryFolder.newFile("source.txt").apply { writeText("keep") }
        val failure = IOException("provider commit failed")
        val error = runCatching {
            exportManagedFileToStream(source) {
                object : ByteArrayOutputStream() {
                    override fun close() {
                        throw failure
                    }
                }
            }
        }.exceptionOrNull()
        assertPropagatedIOException(error, failure)
        assertEquals("keep", source.readText())
    }

    @Test
    fun cancellationPropagatesAndClosesOutputWithoutDeletingSource() = runBlocking {
        val source = temporaryFolder.newFile("source.bin").apply { writeBytes(ByteArray(30_000) { 42 }) }
        var closed = false
        val export = async {
            val job = currentCoroutineContext()[Job]!!
            exportManagedFileToStream(source) {
                object : OutputStream() {
                    override fun write(value: Int) {
                        job.cancel()
                    }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        job.cancel()
                    }
                    override fun close() {
                        closed = true
                    }
                }
            }
        }
        val error = runCatching { export.await() }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(closed)
        assertEquals(30_000L, source.length())
        assertArrayEquals(ByteArray(30_000) { 42 }, source.readBytes())
    }
}
