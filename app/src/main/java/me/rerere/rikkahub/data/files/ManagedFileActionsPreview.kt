package me.rerere.rikkahub.data.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

const val MANAGED_FILE_TEXT_PREVIEW_BYTES = 128 * 1024
const val MANAGED_FILE_MARKDOWN_PREVIEW_BYTES = 32 * 1024

enum class ManagedFilePreviewKind {
    IMAGE,
    TEXT,
    CODE,
    MARKDOWN,
    DOCUMENT,
    PDF,
    AUDIO,
    VIDEO,
    OTHER
}

private val codeExtensions = setOf(
    "html", "htm", "xhtml", "svg", "xml", "json", "jsonl", "ndjson", "yaml", "yml",
    "toml", "ini", "conf", "config", "properties", "csv", "tsv", "sql", "kt", "kts",
    "java", "py", "js", "jsx", "ts", "tsx", "css", "scss", "sh", "bash", "zsh",
    "c", "h", "cpp", "hpp", "rs", "go", "rb", "php", "swift", "dart", "vue", "svelte",
)

/** HTML/SVG always remain source text; no browser/WebView is used for these previews. */
fun managedFilePreviewKind(displayName: String, mimeType: String): ManagedFilePreviewKind {
    val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val mime = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
    return when {
        extension in setOf("html", "htm", "xhtml", "svg") ||
            mime in setOf("text/html", "application/xhtml+xml", "image/svg+xml") -> ManagedFilePreviewKind.CODE
        extension in setOf("md", "markdown", "mdown") ||
            mime in setOf("text/markdown", "text/x-markdown") -> ManagedFilePreviewKind.MARKDOWN
        managedDocumentFormat(displayName, mimeType) != null -> ManagedFilePreviewKind.DOCUMENT
        mime == "application/pdf" || extension == "pdf" -> ManagedFilePreviewKind.PDF
        mime.startsWith("image/") || extension in setOf(
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "avif"
        ) -> ManagedFilePreviewKind.IMAGE
        mime.startsWith("audio/") || extension in setOf(
            "mp3",
            "wav",
            "ogg",
            "opus",
            "flac",
            "m4a",
            "aac",
            "amr"
        ) -> ManagedFilePreviewKind.AUDIO
        mime.startsWith("video/") || extension in setOf(
            "mp4",
            "mkv",
            "webm",
            "mov",
            "avi",
            "m4v",
            "3gp"
        ) -> ManagedFilePreviewKind.VIDEO
        extension in codeExtensions || mime in setOf(
            "application/json",
            "application/ld+json",
            "application/xml",
            "application/javascript",
            "application/x-javascript",
            "application/yaml",
            "application/x-yaml",
            "text/yaml"
        ) || mime.endsWith("+json") || mime.endsWith("+xml") -> ManagedFilePreviewKind.CODE
        mime.startsWith("text/") || extension in setOf(
            "txt",
            "text",
            "log",
            "rst",
            "adoc",
            "srt",
            "vtt",
            "lrc"
        ) -> ManagedFilePreviewKind.TEXT
        else -> ManagedFilePreviewKind.OTHER
    }
}

data class ManagedFileTextPreview(
    val text: String,
    val truncated: Boolean,
    val bytesRead: Int,
)

internal fun requireReadableManagedFile(source: File) {
    if (!source.exists()) throw IOException("文件不存在，可能已被移动或清理")
    if (!source.isFile) throw IOException("所选项目不是普通文件")
    if (!source.canRead()) throw IOException("文件读取权限不可用")
}

/** Reads at most [maxBytes] plus one look-ahead byte, independent of metadata/file length. */
suspend fun readManagedFileTextPreview(
    source: File,
    maxBytes: Int = MANAGED_FILE_TEXT_PREVIEW_BYTES,
): ManagedFileTextPreview = withContext(Dispatchers.IO) {
    require(maxBytes in 1..MANAGED_FILE_TEXT_PREVIEW_BYTES)
    requireReadableManagedFile(source)
    source.inputStream().use { readManagedFileTextPreview(it, maxBytes) }
}

internal suspend fun readManagedFileTextPreview(
    input: InputStream,
    maxBytes: Int,
): ManagedFileTextPreview {
    require(maxBytes in 1..MANAGED_FILE_TEXT_PREVIEW_BYTES)
    val bytes = ByteArray(maxBytes + 1)
    var count = 0
    while (count < bytes.size) {
        currentCoroutineContext().ensureActive()
        val read = input.read(bytes, count, bytes.size - count)
        if (read < 0) break
        if (read == 0) {
            val next = input.read()
            if (next < 0) break
            bytes[count++] = next.toByte()
        } else {
            count += read
        }
    }
    currentCoroutineContext().ensureActive()
    val truncated = count > maxBytes
    val length = minOf(count, maxBytes)
    val (charset, offset) = when {
        length >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() &&
            bytes[2] == 0xbf.toByte() -> Charsets.UTF_8 to 3
        length >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE to 2
        length >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE to 2
        else -> Charsets.UTF_8 to 0
    }
    val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    val chars = CharBuffer.allocate(length + 1)
    // An incomplete last code point at the byte cap is omitted, not displayed as a broken glyph.
    decoder.decode(ByteBuffer.wrap(bytes, offset, length - offset), chars, !truncated)
    chars.flip()
    val text = chars.toString()
    if ('\u0000' in text) throw IOException("文件包含二进制数据，请导出或使用其他应用打开")
    return ManagedFileTextPreview(text = text, truncated = truncated, bytesRead = length)
}
