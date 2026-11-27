package me.rerere.rikkahub.data.files

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.net.URI

/** The only private-storage alias Android guarantees for the primary user. Never match basenames. */
internal fun normalizePrivateFilePath(path: String): String {
    val normalized = File(path).toPath().normalize().toString()
    return if (normalized.startsWith("/data/data/")) {
        "/data/user/0/" + normalized.removePrefix("/data/data/")
    } else {
        normalized
    }
}

/** Read-only path identity, shared by catalog attribution and file-reference protection. */
internal fun normalizeLocalFileReference(url: String): String? {
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        return null
    }
    if (!uri.scheme.equals("file", ignoreCase = true) || !uri.authority.isNullOrEmpty() ||
        uri.query != null || uri.fragment != null
    ) {
        return null
    }
    val path = uri.path?.takeIf { it.startsWith('/') } ?: return null
    return try {
        normalizePrivateFilePath(path)
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal data class MessageFileReferences(val paths: Set<String>, val complete: Boolean)

/**
 * Reads the production List<UIMessage> representation without retaining long text/tool-input values.
 * Input chunks, JSON nesting (128), captured strings (8192) and distinct references (16384) are bounded.
 * Only attachment parts and structured tool outputs count, never text, metadata or tool inputs.
 * [acceptPath] lets callers retain only their candidate paths. Incomplete input is never negative proof.
 */
internal suspend fun scanMessageFileReferences(
    readChunk: suspend () -> String?,
    acceptPath: (String) -> Boolean = { true },
): MessageFileReferences = MessageReferenceReader(readChunk, acceptPath).read()

private class InvalidReferenceJson : IllegalArgumentException()

private class MessageReferenceReader(
    private val readChunk: suspend () -> String?,
    private val acceptPath: (String) -> Boolean,
) {
    private var chunk = ""
    private var offset = 0
    private var consumed = 0
    private var eof = false
    private val found = linkedSetOf<String>()

    private data class References(val paths: Set<String> = emptySet(), val complete: Boolean = true)

    suspend fun read(): MessageFileReferences {
        var complete = true
        try {
            expect('[')
            whitespace()
            if (peek() != ']') {
                while (true) {
                    val references = message(1)
                    addAll(found, references.paths)
                    complete = complete && references.complete
                    whitespace()
                    if (peek() != ',') break
                    take()
                }
            }
            expect(']')
            whitespace()
            if (peek() != null) throw InvalidReferenceJson()
        } catch (_: InvalidReferenceJson) {
            complete = false
        }
        return MessageFileReferences(found, complete)
    }

    private suspend fun message(depth: Int): References {
        checkDepth(depth)
        whitespace()
        if (peek() != '{') {
            skip(depth)
            return References(complete = false)
        }
        take()
        var references: References? = null
        members { key ->
            if (key == "parts") references = parts(depth + 1) else skip(depth + 1)
        }
        return references ?: References(complete = false)
    }

    private suspend fun parts(depth: Int): References {
        checkDepth(depth)
        whitespace()
        if (peek() != '[') {
            skip(depth)
            return References(complete = false)
        }
        take()
        val paths = linkedSetOf<String>()
        var complete = true
        whitespace()
        if (peek() != ']') {
            while (true) {
                val references = part(depth + 1)
                addAll(paths, references.paths)
                complete = complete && references.complete
                whitespace()
                if (peek() != ',') break
                take()
            }
        }
        expect(']')
        return References(paths, complete)
    }

    private suspend fun part(depth: Int): References {
        checkDepth(depth)
        whitespace()
        if (peek() != '{') {
            skip(depth)
            return References(complete = false)
        }
        take()
        var type: String? = null
        var url: String? = null
        var hasUrl = false
        var output = References()
        var content = References()
        members { key ->
            when (key) {
                "type" -> type = optionalString(depth + 1)
                "url" -> {
                    hasUrl = true
                    url = optionalString(depth + 1)
                }
                "output" -> {
                    whitespace()
                    if (peek() == '[') output = parts(depth + 1) else skip(depth + 1)
                }
                "content" -> {
                    whitespace()
                    if (peek() == '[') content = parts(depth + 1) else skip(depth + 1)
                }
                else -> skip(depth + 1)
            }
        }
        return when (type) {
            "image", "video", "audio", "document" -> {
                val value = url ?: return References(complete = false)
                val path = normalizeLocalFileReference(value)
                References(
                    paths = path?.takeIf(acceptPath)?.let(::setOf).orEmpty(),
                    complete = !value.startsWith("file:", ignoreCase = true) || path != null,
                )
            }
            "tool", "server_tool" -> output
            "tool_result" -> content
            null -> References(complete = false)
            else -> References(complete = !hasUrl)
        }
    }

    private suspend fun members(readValue: suspend (String?) -> Unit) {
        whitespace()
        if (peek() != '}') {
            while (true) {
                whitespace()
                val key = string(capture = true)
                expect(':')
                readValue(key)
                whitespace()
                if (peek() != ',') break
                take()
            }
        }
        expect('}')
    }

    private suspend fun optionalString(depth: Int): String? {
        whitespace()
        return if (peek() == '"') {
            string(capture = true)
        } else {
            skip(depth)
            null
        }
    }

    private suspend fun skip(depth: Int) {
        checkDepth(depth)
        whitespace()
        when (peek()) {
            '"' -> string(capture = false)
            '{' -> {
                take()
                members { skip(depth + 1) }
            }
            '[' -> {
                take()
                whitespace()
                if (peek() != ']') {
                    while (true) {
                        skip(depth + 1)
                        whitespace()
                        if (peek() != ',') break
                        take()
                    }
                }
                expect(']')
            }
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            '-', in '0'..'9' -> {
                val number = StringBuilder()
                while (peek()?.let { it in "0123456789+-.eE" } == true) {
                    if (number.length == 128) throw InvalidReferenceJson()
                    number.append(take())
                }
                if (!NUMBER.matches(number)) throw InvalidReferenceJson()
            }
            else -> throw InvalidReferenceJson()
        }
    }

    private suspend fun string(capture: Boolean): String? {
        if (take() != '"') throw InvalidReferenceJson()
        val value = if (capture) StringBuilder() else null
        var truncated = false
        while (true) {
            val char = take() ?: throw InvalidReferenceJson()
            if (char == '"') break
            val decoded = if (char == '\\') {
                when (val escaped = take()) {
                    '"', '\\', '/' -> escaped
                    'b' -> '\b'
                    'f' -> '\u000C'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> {
                        var code = 0
                        repeat(4) {
                            val digit = take()?.digitToIntOrNull(16) ?: throw InvalidReferenceJson()
                            code = code * 16 + digit
                        }
                        code.toChar()
                    }
                    else -> throw InvalidReferenceJson()
                }
            } else {
                if (char < ' ') throw InvalidReferenceJson()
                char
            }
            if (value != null) {
                if (value.length < 8192) value.append(decoded) else truncated = true
            }
        }
        return value?.takeUnless { truncated }?.toString()
    }

    private fun addAll(target: MutableSet<String>, paths: Set<String>) {
        for (path in paths) {
            if (path !in target && target.size == 16_384) throw InvalidReferenceJson()
            target.add(path)
        }
    }

    private fun checkDepth(depth: Int) {
        if (depth > 128) throw InvalidReferenceJson()
    }

    private suspend fun literal(value: String) {
        for (char in value) if (take() != char) throw InvalidReferenceJson()
    }

    private suspend fun whitespace() {
        while (peek()?.let { it == ' ' || it == '\n' || it == '\r' || it == '\t' } == true) take()
    }

    private suspend fun expect(char: Char) {
        whitespace()
        if (take() != char) throw InvalidReferenceJson()
    }

    private suspend fun peek(): Char? {
        while (offset == chunk.length && !eof) {
            currentCoroutineContext().ensureActive()
            val next = readChunk()
            if (next.isNullOrEmpty()) {
                eof = true
            } else {
                chunk = next
                offset = 0
            }
        }
        return if (eof) null else chunk[offset]
    }

    private suspend fun take(): Char? {
        if (++consumed % 1024 == 0) currentCoroutineContext().ensureActive()
        return peek()?.also { offset++ }
    }

    companion object {
        private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    }
}
