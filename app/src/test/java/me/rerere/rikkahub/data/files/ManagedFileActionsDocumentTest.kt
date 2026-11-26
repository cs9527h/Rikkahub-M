package me.rerere.rikkahub.data.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ManagedFileActionsDocumentTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun archive(vararg entries: Pair<String, String>): File = temporaryFolder.newFile().apply {
        ZipOutputStream(outputStream()).use { output ->
            for ((name, body) in entries) {
                output.putNextEntry(ZipEntry(name))
                output.write(body.toByteArray())
                output.closeEntry()
            }
        }
    }

    @Test
    fun supportedOfficeDocumentsUseNativeTextPreviewButLegacyBinariesDoNot() {
        assertEquals(ManagedFilePreviewKind.DOCUMENT, managedFilePreviewKind("report.DOCX", "application/octet-stream"))
        assertEquals(ManagedFilePreviewKind.DOCUMENT, managedFilePreviewKind("slides.pptx", "application/octet-stream"))
        assertEquals(ManagedFilePreviewKind.DOCUMENT, managedFilePreviewKind("blob", "application/epub+zip"))
        assertEquals(ManagedDocumentFormat.EPUB, managedDocumentFormat("book.EPUB", "application/octet-stream"))
        assertEquals(ManagedFilePreviewKind.OTHER, managedFilePreviewKind("report.doc", "application/msword"))
        assertEquals(ManagedFilePreviewKind.OTHER, managedFilePreviewKind("sheet.xls", "application/vnd.ms-excel"))
    }

    @Test
    fun archiveRejectsOversizedInflatedXmlEvenWhenCompressedFileIsTiny() {
        val source = archive("word/document.xml" to "x".repeat(16_384))
        assertTrue(source.length() < 4096)
        val error = runCatching {
            checkManagedDocumentArchive(source, ManagedDocumentPreviewLimits(xmlBytes = 4096)) {}
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(source.isFile)
    }

    @Test
    fun aggregateInflatedBudgetIsSharedAcrossArchiveEntries() {
        val source = archive("a.xml" to "a".repeat(100), "b.xml" to "b".repeat(100))
        assertTrue(runCatching {
            checkManagedDocumentArchive(source, ManagedDocumentPreviewLimits(totalBytes = 150)) {}
        }.exceptionOrNull() is IOException)
    }

    @Test
    fun archiveEntryCountIsBoundedBeforeParsing() {
        val source = archive("a.xml" to "a", "b.xml" to "b")
        assertTrue(runCatching {
            checkManagedDocumentArchive(source, ManagedDocumentPreviewLimits(entries = 1)) {}
        }.exceptionOrNull() is IOException)
    }

    @Test
    fun sparseTableExpansionIsBoundedForNamespacedAndUnqualifiedTags() {
        val source = archive("word/document.xml" to "<w:tr><w:tc/><w:tc/></w:tr><tr><tc/></tr>")
        assertTrue(runCatching {
            checkManagedDocumentArchive(source, ManagedDocumentPreviewLimits(tableElements = 4)) {}
        }.exceptionOrNull() is IOException)
        checkManagedDocumentArchive(source, ManagedDocumentPreviewLimits(tableElements = 5)) {}
    }

    @Test
    fun archiveValidationPropagatesCancellation() {
        val source = archive("word/document.xml" to "<document>hello</document>")
        var checks = 0
        val error = runCatching {
            checkManagedDocumentArchive(source, ManagedDocumentPreviewLimits()) {
                if (++checks >= 3) throw CancellationException("cancelled")
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(source.isFile)
    }

    @Test
    fun parserErrorsAndLimitMarkersNeverBecomeSuccessfulDocumentBody() = runBlocking {
        val failures = listOf(
            "Document content too large",
            "Error parsing DOCX file: bad ZIP",
            "Error parsing document XML: Document content too large",
            "## Slide 1\n\nError parsing slide XML: Document content too large\n",
            "Error parsing EPUB file: EPUB output too large",
            "Unable to find document content in DOCX file",
            "No slides found in PPTX file",
            "Unable to find OPF file in EPUB",
            "Unable to read OPF file in EPUB",
            "No readable content found in EPUB file",
            "# Title\n\npartial content\n[truncated]",
        )
        for (failure in failures) {
            assertTrue(failure, runCatching { managedDocumentPreviewText(failure) }.exceptionOrNull() is IOException)
        }
    }

    @Test
    fun extractedTextHasASeparateBoundedPreviewAndTruncationFlag() = runBlocking {
        val preview = managedDocumentPreviewText("a".repeat(MANAGED_FILE_TEXT_PREVIEW_BYTES + 100))
        assertEquals(MANAGED_FILE_TEXT_PREVIEW_BYTES, preview.bytesRead)
        assertEquals(MANAGED_FILE_TEXT_PREVIEW_BYTES, preview.text.length)
        assertTrue(preview.truncated)
        val short = managedDocumentPreviewText("## Slide 1\n\nhello")
        assertEquals("## Slide 1\n\nhello", short.text)
        assertFalse(short.truncated)
    }

    @Test
    fun unsupportedLegacyBinaryFailsWithoutTryingZipParser() = runBlocking {
        val source = temporaryFolder.newFile("report.doc").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val error = runCatching {
            readManagedFileDocumentPreview(source, "report.doc", "application/msword")
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error?.message?.contains("此文档格式") == true)
        assertEquals(3L, source.length())
    }
}
