package me.rerere.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal data class EpubLimits(
    val entryBytes: Long = DocumentLimits.MAX_XML_BYTES,
    val totalBytes: Long = 128L * 1024 * 1024,
    val outputChars: Int = DocumentLimits.MAX_OUTPUT_CHARS,
    val entries: Int = 10_000,
)

private class EpubBudget(
    val limits: EpubLimits,
    val checkCancellation: () -> Unit,
) {
    private var consumed = 0L

    fun open(zip: ZipFile, entry: ZipEntry): InputStream {
        checkCancellation()
        require(entry.size <= limits.entryBytes) { "EPUB entry too large: ${entry.name}" }
        return object : InputStream() {
            private val input = zip.getInputStream(entry)
            private var entryConsumed = 0L

            private fun count(count: Int) {
                checkCancellation()
                if (count > 0) {
                    entryConsumed += count
                    consumed += count
                    require(entryConsumed <= limits.entryBytes) { "EPUB entry too large" }
                    require(consumed <= limits.totalBytes) { "EPUB total content too large" }
                }
            }

            override fun read(): Int {
                checkCancellation()
                return input.read().also { count(if (it < 0) 0 else 1) }
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                checkCancellation()
                // Read only a bounded chunk, even if a parser requests a large buffer.
                return input.read(buffer, offset, minOf(length, 8192)).also { count(it) }
            }

            override fun close() = input.close()
        }
    }
}

private class EpubText(private val limit: Int) {
    private val text = StringBuilder()

    fun append(value: String) {
        require(value.length <= limit - text.length) { "EPUB output too large" }
        text.append(value)
    }

    override fun toString(): String = text.toString()
}

private data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String
)

object EpubParser {
    fun parse(file: File): String = parse(file) {
        if (Thread.currentThread().isInterrupted) throw CancellationException("EPUB parsing cancelled")
    }

    fun parse(file: File, checkCancellation: () -> Unit): String = parse(file, EpubLimits(), checkCancellation)

    internal fun parse(file: File, limits: EpubLimits, checkCancellation: () -> Unit): String {
        return try {
            checkCancellation()
            ZipFile(file).use { zip ->
                require(zip.size() <= limits.entries) { "Too many EPUB archive entries" }
                val budget = EpubBudget(limits, checkCancellation)
                val opfPath = findOpfPath(zip, budget)
                    ?: return "Unable to find OPF file in EPUB"
                val opfDir = opfPath.substringBeforeLast('/', "")

                val opfEntry = zip.getEntry(opfPath)
                    ?: return "Unable to read OPF file in EPUB"
                val (manifest, spine) = budget.open(zip, opfEntry).use { parseOpf(it, budget) }

                val result = EpubText(limits.outputChars)
                for (itemId in spine) {
                    checkCancellation()
                    val item = manifest[itemId] ?: continue
                    if (!item.mediaType.contains("html")) continue

                    val itemPath = if (opfDir.isEmpty()) item.href else "$opfDir/${item.href}"
                    val entry = zip.getEntry(itemPath) ?: continue
                    val content = budget.open(zip, entry).use { parseXhtml(it, budget) }
                    if (content.isNotBlank()) {
                        result.append(content)
                        result.append("\n\n")
                    }
                }

                result.toString().trim().ifEmpty { "No readable content found in EPUB file" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Error parsing EPUB file: ${e.message}"
        }
    }

    private fun disableDocDecl(parser: XmlPullParser) {
        // KXml reports unsupported features as false but rejects even setFeature(false).
        // Leave an already-disabled feature alone; fail if an enabled feature cannot be disabled.
        if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        }
    }

    private fun findOpfPath(zip: ZipFile, budget: EpubBudget): String? {
        val containerEntry = zip.getEntry("META-INF/container.xml") ?: return null
        return budget.open(zip, containerEntry).use { stream ->
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            disableDocDecl(parser)
            parser.setInput(stream, "UTF-8")

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                budget.checkCancellation()
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    return@use parser.getAttributeValue(null, "full-path")
                }
                parser.next()
            }
            null
        }
    }

    private fun parseOpf(inputStream: InputStream, budget: EpubBudget): Pair<Map<String, ManifestItem>, List<String>> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        disableDocDecl(parser)
        parser.setInput(inputStream, "UTF-8")

        val manifest = mutableMapOf<String, ManifestItem>()
        val spine = mutableListOf<String>()

        var references = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            budget.checkCancellation()
            if (parser.eventType == XmlPullParser.START_TAG) {
                if (parser.name == "item" || parser.name == "itemref") {
                    require(++references <= budget.limits.entries) { "Too many EPUB manifest/spine entries" }
                }
                when (parser.name) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id") ?: ""
                        val href = parser.getAttributeValue(null, "href") ?: ""
                        val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                        if (id.isNotEmpty()) {
                            manifest[id] = ManifestItem(id, href, mediaType)
                        }
                    }

                    "itemref" -> {
                        val idref = parser.getAttributeValue(null, "idref") ?: ""
                        if (idref.isNotEmpty()) {
                            spine.add(idref)
                        }
                    }
                }
            }
            parser.next()
        }

        return manifest to spine
    }

    private fun parseXhtml(inputStream: InputStream, budget: EpubBudget): String {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        disableDocDecl(parser)
        parser.setInput(inputStream, "UTF-8")

        val result = EpubText(budget.limits.outputChars)
        val tagStack = ArrayDeque<String>()
        var inBody = false
        var listCounter = 0

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            budget.checkCancellation()
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase()
                    tagStack.addLast(tag)

                    when (tag) {
                        "body" -> inBody = true
                        "ol" -> listCounter = 0
                        "li" -> {
                            val parentTag = tagStack.dropLast(1).lastOrNull()
                            if (parentTag == "ol") {
                                listCounter++
                                result.append("$listCounter. ")
                            } else {
                                result.append("- ")
                            }
                        }

                        "br" -> result.append("\n")
                        "img" -> {
                            if (inBody) {
                                val alt = parser.getAttributeValue(null, "alt")
                                if (!alt.isNullOrBlank()) {
                                    result.append("[image: $alt]")
                                }
                            }
                        }

                        "h1", "h2", "h3", "h4", "h5", "h6" -> {
                            if (inBody) {
                                val level = tag[1].digitToInt()
                                result.append("${"#".repeat(level)} ")
                            }
                        }

                        "strong", "b" -> {
                            if (inBody) result.append("**")
                        }

                        "em", "i" -> {
                            if (inBody) result.append("*")
                        }

                        "hr" -> {
                            if (inBody) result.append("\n---\n")
                        }

                        "blockquote" -> {
                            if (inBody) result.append("> ")
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    if (inBody) {
                        val text = parser.text
                            ?.replace('\n', ' ')
                            ?.replace('\r', ' ')
                            ?.replace("\\s+".toRegex(), " ")
                        if (!text.isNullOrBlank()) {
                            result.append(text)
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    val tag = parser.name.lowercase()
                    if (tagStack.isNotEmpty()) tagStack.removeLast()

                    when (tag) {
                        "body" -> inBody = false
                        "p", "div" -> {
                            if (inBody) result.append("\n\n")
                        }

                        "h1", "h2", "h3", "h4", "h5", "h6" -> {
                            if (inBody) result.append("\n\n")
                        }

                        "li" -> {
                            if (inBody) result.append("\n")
                        }

                        "ul", "ol" -> {
                            if (inBody) result.append("\n")
                        }

                        "br" -> {}
                        "strong", "b" -> {
                            if (inBody) result.append("**")
                        }

                        "em", "i" -> {
                            if (inBody) result.append("*")
                        }

                        "blockquote" -> {
                            if (inBody) result.append("\n")
                        }
                    }
                }
            }
            parser.next()
        }

        return result.toString()
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
