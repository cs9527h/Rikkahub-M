package me.rerere.rikkahub.data.ai.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.MemoryToolNames
import me.rerere.rikkahub.data.model.MemoryFile
import java.util.Locale

private val words = Regex("[\\p{IsHan}]+|[\\p{L}\\p{N}_-]+")
private val stopWords = setOf("the", "and", "for", "what", "how", "can", "you", "that", "with", "about", "this",
    "我的", "你的", "什么", "怎么", "一下", "这个", "那个", "我想", "帮我", "知道", "是否", "之前", "现在")

internal fun recallTerms(query: String): Set<String> =
    words.findAll(query.take(2_000).lowercase(Locale.ROOT)).flatMap { match ->
        val text = match.value
        if (Character.UnicodeScript.of(text[0].code) == Character.UnicodeScript.HAN && text.length >= 2) {
            text.windowed(2).asSequence()
        } else {
            sequenceOf(text)
        }
    }.filter { it.length >= 2 && it !in stopWords }.distinct().take(48).toSet()

internal fun memoryRelevance(file: MemoryFile, terms: Set<String>): Int {
    val heading = listOf(file.title, file.description, file.path, file.aliases.joinToString(" "))
        .joinToString(" ").lowercase(Locale.ROOT)
    val body = file.body.substringBefore("<!-- history-source ").lowercase(Locale.ROOT)
    val hits = terms.sumOf { term ->
        when {
            term in heading -> 5
            term in body -> 2
            else -> 0
        }
    }
    return hits
}

@Serializable
internal data class RecallExcerpt(val path: String, val content: String)

internal fun recallExcerpts(files: List<MemoryFile>, maxChars: Int = 4_000): List<RecallExcerpt> {
    var remaining = maxChars
    return files.mapNotNull { file ->
        if (remaining < 60) return@mapNotNull null
        val source = file.body.substringBefore("<!-- history-source ").trim()
        if (source.isBlank()) return@mapNotNull null
        val text = source.take(minOf(1_250, remaining - file.path.length - 20)).trimEnd()
        if (text.isBlank()) return@mapNotNull null
        remaining -= text.length + file.path.length + 20
        RecallExcerpt(file.path, text)
    }
}

internal fun UIMessagePart.Tool.recalledFiles(): List<RecallExcerpt> {
    if (toolName != MemoryToolNames.READ || !isExecuted) return emptyList()
    val result = runCatching {
        Json.parseToJsonElement(output.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
    }.getOrNull() as? JsonObject ?: return emptyList()
    return (result["files"] as? JsonArray).orEmpty().mapNotNull { entry ->
        val obj = entry as? JsonObject ?: return@mapNotNull null
        val path = (obj["path"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val content = (obj["content"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        RecallExcerpt(path, content)
    }
}
