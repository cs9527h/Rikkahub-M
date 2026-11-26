package me.rerere.rikkahub.data.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PptxParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile

internal enum class ManagedDocumentFormat { DOCX, PPTX, EPUB }

internal fun managedDocumentFormat(displayName: String, mimeType: String): ManagedDocumentFormat? {
    val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val mime = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
    return when {
        extension == "docx" || mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ManagedDocumentFormat.DOCX
        extension == "pptx" || mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ManagedDocumentFormat.PPTX
        extension == "epub" || mime == "application/epub+zip" -> ManagedDocumentFormat.EPUB
        else -> null
    }
}

/** Stricter preview-only budgets complement the document module's own parser limits. */
internal data class ManagedDocumentPreviewLimits(
    val archiveBytes: Long = 32L * 1024 * 1024,
    val entries: Int = 1024,
    val entryBytes: Long = 8L * 1024 * 1024,
    val totalBytes: Long = 16L * 1024 * 1024,
    val xmlBytes: Long = 1024L * 1024,
    val totalXmlBytes: Long = 4L * 1024 * 1024,
    val tableElements: Int = 4096,
)

private val tableElement = Regex("<(?:[^\\s<>/:]+:)?(?:tr|tc)(?=[\\s>/])")

/**
 * Existing parsers return strings rather than typed errors. Keep their resource-limit/error sentinels
 * out of the successful preview body; the UI retains external-open/export for every failure.
 */
suspend fun readManagedFileDocumentPreview(
    source: File,
    displayName: String,
    mimeType: String,
): ManagedFileTextPreview = withContext(Dispatchers.IO) {
    requireReadableManagedFile(source)
    val format = managedDocumentFormat(displayName, mimeType) ?: throw IOException("此文档格式暂不支持内置文字预览")
    val context = currentCoroutineContext()
    checkManagedDocumentArchive(source, ManagedDocumentPreviewLimits()) { context.ensureActive() }
    context.ensureActive()
    val parsed = when (format) {
        ManagedDocumentFormat.DOCX -> DocxParser.parse(source)
        ManagedDocumentFormat.PPTX -> PptxParser.parse(source)
        ManagedDocumentFormat.EPUB -> EpubParser.parse(source) { context.ensureActive() }
    }
    context.ensureActive()
    managedDocumentPreviewText(parsed)
}

/** Validate actual inflated bytes, not only ZIP headers; also bound sparse-table expansion. */
internal fun checkManagedDocumentArchive(
    source: File,
    limits: ManagedDocumentPreviewLimits,
    checkCancellation: () -> Unit,
) {
    checkCancellation()
    if (source.length() > limits.archiveBytes) throw IOException("文档超过内置预览大小上限，请使用其他应用打开或导出。")
    ZipFile(source).use { zip ->
        if (zip.size() > limits.entries) throw IOException("文档压缩包条目过多，请使用其他应用打开或导出。")
        var totalBytes = 0L
        var totalXmlBytes = 0L
        var tableElements = 0
        val entries = zip.entries()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (entries.hasMoreElements()) {
            checkCancellation()
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val extension = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val isXml = extension in setOf("xml", "opf", "xhtml", "html", "htm")
            val entryLimit = if (isXml) minOf(limits.xmlBytes, limits.entryBytes) else limits.entryBytes
            if (entry.size > entryLimit) throw IOException("文档内容超过内置预览上限，请使用其他应用打开或导出。")
            val xml = if (isXml) ByteArrayOutputStream() else null
            var entryBytes = 0L
            zip.getInputStream(entry).use { input ->
                while (true) {
                    checkCancellation()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    entryBytes += read
                    totalBytes += read
                    if (isXml) totalXmlBytes += read
                    if (entryBytes > entryLimit || totalBytes > limits.totalBytes || totalXmlBytes > limits.totalXmlBytes) {
                        throw IOException("文档解压内容超过内置预览上限，请使用其他应用打开或导出。")
                    }
                    xml?.write(buffer, 0, read)
                }
            }
            if (xml != null) {
                checkCancellation()
                // The parsers consume UTF-8 XML. Row/cell count caps their markdown-table padding.
                val tags = tableElement.findAll(xml.toString(Charsets.UTF_8.name()))
                for (ignored in tags) {
                    checkCancellation()
                    tableElements++
                    if (tableElements > limits.tableElements) {
                        throw IOException("文档表格过于复杂，请使用其他应用打开或导出。")
                    }
                }
            }
        }
    }
}

internal suspend fun managedDocumentPreviewText(parsed: String): ManagedFileTextPreview {
    val errorLine = parsed.lineSequence().map { it.trim() }.firstOrNull { line ->
        line.startsWith("Error parsing ") || line == "Document content too large" ||
            line == "Unable to find document content in DOCX file" || line == "No slides found in PPTX file" ||
            line == "Unable to find OPF file in EPUB" || line == "Unable to read OPF file in EPUB" ||
            line == "No readable content found in EPUB file" || line == "[truncated]"
    }
    if (errorLine != null) throw IOException("文档解析失败或触达解析上限，请使用其他应用打开或导出。")
    // Bound the intermediate UTF-8 allocation as well as the final displayed content.
    val prefix = parsed.take(MANAGED_FILE_TEXT_PREVIEW_BYTES + 1)
    return readManagedFileTextPreview(
        ByteArrayInputStream(prefix.toByteArray(Charsets.UTF_8)),
        MANAGED_FILE_TEXT_PREVIEW_BYTES,
    )
}
