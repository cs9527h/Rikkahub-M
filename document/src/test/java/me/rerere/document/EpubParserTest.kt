package me.rerere.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val container = """<container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>"""

    private fun opf(repeats: Int = 1): String = """
        <package><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
        <spine>${"<itemref idref=\"chapter\"/>".repeat(repeats)}</spine></package>
    """.trimIndent()

    private fun archive(
        containerXml: String = container,
        packageXml: String = opf(),
        chapter: String = "<html><body><p>Hello EPUB</p></body></html>",
        extras: Int = 0,
    ): File {
        val file = temporary.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            val entries = linkedMapOf(
                "META-INF/container.xml" to containerXml,
                "OPS/book.opf" to packageXml,
                "OPS/chapter.xhtml" to chapter,
            )
            repeat(extras) { entries["extra/$it"] = "" }
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    private fun parse(file: File, limits: EpubLimits): String = EpubParser.parse(file, limits) {}

    @Test
    fun normalDocumentsKeepFormattingAndReadingOrder() {
        val chapter = """
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Ignored</title></head>
            <body><h1>Title</h1><p>Hello <strong>reader</strong>.</p>
            <ol><li>one</li><li>two</li></ol><img alt="cover"/></body></html>
        """.trimIndent()
        val result = EpubParser.parse(archive(chapter = chapter))
        assertTrue(result.contains("# Title"))
        assertTrue(result.contains("Hello **reader**."))
        assertTrue(result.contains("1. one"))
        assertTrue(result.contains("2. two"))
        assertTrue(result.contains("[image: cover]"))
        assertTrue(!result.contains("Ignored"))
        assertEquals("Hello EPUB", EpubParser.parse(archive()))
    }

    @Test
    fun compressedExpansionIsBoundedForEveryXmlStage() {
        val padding = " ".repeat(32_000)
        val limits = EpubLimits(entryBytes = 1024)
        val files = listOf(
            archive(containerXml = "<container>$padding<rootfile full-path=\"OPS/book.opf\"/></container>"),
            archive(packageXml = "<package>$padding</package>"),
            archive(chapter = "<html><body>$padding</body></html>"),
        )
        files.forEach {
            assertTrue("compressed fixture should stay small", it.length() < 2048)
            assertTrue(parse(it, limits).contains("EPUB entry too large"))
        }
    }

    @Test
    fun declaredZipSizesDoNotReplaceActualReadLimits() {
        val padding = " ".repeat(32_000)
        val files = listOf(
            archive(containerXml = "<container>$padding<rootfile full-path=\"OPS/book.opf\"/></container>"),
            archive(packageXml = "<package>$padding</package>"),
            archive(chapter = "<html><body>$padding</body></html>"),
        )
        files.forEach { file ->
            val bytes = file.readBytes()
            // Central directory records (PK 01 02): declare one byte while retaining the deflate stream.
            for (offset in 0 until bytes.size - 28) {
                if (bytes[offset] == 0x50.toByte() && bytes[offset + 1] == 0x4b.toByte() &&
                    bytes[offset + 2] == 1.toByte() && bytes[offset + 3] == 2.toByte()
                ) {
                    bytes[offset + 24] = 1
                    bytes[offset + 25] = 0
                    bytes[offset + 26] = 0
                    bytes[offset + 27] = 0
                }
            }
            file.writeBytes(bytes)
            assertTrue(parse(file, EpubLimits(entryBytes = 1024)).contains("EPUB entry too large"))
        }
    }

    @Test
    fun cumulativeDecompressedBudgetIncludesContainerOpfAndRepeatedSpineReads() {
        val file = archive(packageXml = opf(10))
        val xmlTotal = container.toByteArray().size + opf(10).toByteArray().size
        val result = parse(file, EpubLimits(totalBytes = xmlTotal + 100L))
        assertTrue(result.contains("EPUB total content too large"))
        assertTrue(parse(archive(), EpubLimits(totalBytes = 50)).contains("EPUB total content too large"))
    }

    @Test
    fun xhtmlAndRepeatedSpineOutputAreBounded() {
        assertTrue(parse(
            archive(chapter = "<html><body>${"x".repeat(100)}</body></html>"),
            EpubLimits(outputChars = 40)
        ).contains("EPUB output too large"))
        assertTrue(
            parse(archive(packageXml = opf(30)), EpubLimits(outputChars = 100))
                .contains("EPUB output too large")
        )
    }

    @Test
    fun archiveAndOpfReferenceCountsAreBounded() {
        assertTrue(parse(archive(extras = 3), EpubLimits(entries = 5)).contains("Too many EPUB archive entries"))
        assertTrue(
            parse(archive(packageXml = opf(8)), EpubLimits(entries = 8))
                .contains("Too many EPUB manifest/spine entries")
        )
    }

    @Test
    fun cancellationEscapesInsteadOfBecomingAnErrorOrPartialResult() {
        val file = archive(packageXml = opf(50))
        var checks = 0
        assertThrows(CancellationException::class.java) {
            EpubParser.parse(file, EpubLimits()) {
                if (++checks == 25) throw CancellationException("test cancellation")
            }
        }
        assertTrue(checks >= 25)
        Thread.currentThread().interrupt()
        try {
            assertThrows(CancellationException::class.java) { EpubParser.parse(file) }
        } finally {
            Thread.interrupted()
        }
        assertEquals("Hello EPUB", EpubParser.parse(archive()))
    }
}
