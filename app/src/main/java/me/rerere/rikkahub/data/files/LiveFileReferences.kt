package me.rerere.rikkahub.data.files

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessagePart

/** A read-only view of accepted input/history, never the editor's unsent draft. */
internal data class LiveConversationFileReferences(
    val conversationId: String,
    /** null until initialization resolves the real conversation's assistant, not the selected one. */
    val assistantId: String?,
    val paths: Set<String>,
    /** An initialized session's history supersedes the corresponding persisted nodes. */
    val replacesPersistedHistory: Boolean,
    val complete: Boolean = true,
)

/** Traverses already materialized parts without serializing/copying potentially huge text values. */
internal suspend fun collectLiveFileReferences(parts: Sequence<UIMessagePart>): MessageFileReferences {
    val paths = linkedSetOf<String>()
    var complete = true
    val pending = ArrayDeque<Pair<Iterator<UIMessagePart>, Int>>()
    pending.addLast(parts.iterator() to 0)
    fun record(url: String) {
        if (url.length > 8192) {
            complete = false
            return
        }
        val path = normalizeLocalFileReference(url)
        if (path != null) {
            if (paths.size < 16_384 || path in paths) paths.add(path) else complete = false
        } else if (url.startsWith("file:", ignoreCase = true)) {
            complete = false
        }
    }

    // Server/legacy tools contain arbitrary JSON, so visit only structured attachment part arrays.
    suspend fun structured(element: JsonElement?) {
        val arrays = ArrayDeque<Pair<Iterator<JsonElement>, Int>>()
        (element as? JsonArray)?.let { arrays.addLast(it.iterator() to 0) }
        while (arrays.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (iterator, depth) = arrays.last()
            if (!iterator.hasNext()) {
                arrays.removeLast()
                continue
            }
            val part = iterator.next() as? JsonObject
            if (part == null) {
                complete = false
                continue
            }
            when ((part["type"] as? JsonPrimitive)?.content) {
                "image", "video", "audio", "document" -> {
                    val url = part["url"] as? JsonPrimitive
                    if (url?.isString == true) record(url.content) else complete = false
                }
                "tool", "server_tool", "tool_result" -> {
                    val output = part[if ((part["type"] as? JsonPrimitive)?.content ==
                        "tool_result") {
                        "content"
                    } else {
                        "output"
                    }]
                    if (depth >= 128) {
                        complete = false
                    } else {
                        (output as? JsonArray)?.let { arrays.addLast(it.iterator() to depth + 1) }
                    }
                }
                else -> if (part.containsKey("url")) complete = false
            }
        }
    }
    while (pending.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val (iterator, depth) = pending.last()
        if (!iterator.hasNext()) {
            pending.removeLast()
            continue
        }
        when (val part = iterator.next()) {
            is UIMessagePart.Image -> record(part.url)
            is UIMessagePart.Video -> record(part.url)
            is UIMessagePart.Audio -> record(part.url)
            is UIMessagePart.Document -> record(part.url)
            is UIMessagePart.Tool -> {
                if (depth >= 128) complete = false else pending.addLast(part.output.iterator() to depth + 1)
            }
            is UIMessagePart.ServerTool -> structured(part.output)
            is UIMessagePart.ToolResult -> structured(part.content)
            else -> Unit
        }
    }
    return MessageFileReferences(paths, complete)
}
