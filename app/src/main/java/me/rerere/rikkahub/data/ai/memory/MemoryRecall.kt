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

internal fun memoryRelevance(file: MemoryFile, terms: Set<String>): Int = memoryMatch(file, terms).score

internal data class MemoryMatch(
    /** 标题、路径、别名的命中次数 */
    val headingHits: Int,
    /** 正文命中次数 */
    val bodyHits: Int,
    /** 查询原句出现在标题或正文里 */
    val phrase: Boolean,
) {
    val score: Int get() = headingHits * 6 + bodyHits * 2

    /**
     * 一条记忆值不值得召回。
     *
     * 旧实现只要有一个共同词就算命中，于是“阅读软件”会匹配到所有带“阅读”两字的记忆。
     * 现在要求至少两个不同关键词，或者整句出现/标题命中加一个正文佐证。
     */
    val relevant: Boolean
        get() = phrase ||
            headingHits >= 1 && headingHits + bodyHits >= 2 ||
            bodyHits >= 3
}

internal fun memoryMatch(file: MemoryFile, terms: Set<String>, query: String = ""): MemoryMatch {
    val heading = listOf(file.title, file.description, file.path, file.aliases.joinToString(" "))
        .joinToString(" ").lowercase(Locale.ROOT)
    val body = file.recallBody.lowercase(Locale.ROOT)
    var headingHits = 0
    var bodyHits = 0
    for (term in terms) {
        val inHeading = term in heading
        val inBody = !inHeading && term in body
        if (inHeading) headingHits++
        if (inBody) bodyHits++
    }
    val phrase = query.trim().lowercase(Locale.ROOT).let {
        it.length >= 4 && (it in heading || it in body)
    }
    return MemoryMatch(headingHits, bodyHits, phrase)
}

@Serializable
internal data class RecallExcerpt(val path: String, val content: String)

internal fun recallExcerpts(files: List<MemoryFile>, maxChars: Int = 4_000): List<RecallExcerpt> {
    var remaining = maxChars
    return files.mapNotNull { file ->
        if (remaining < 60) return@mapNotNull null
        val source = file.recallBody.trim()
        if (source.isBlank()) return@mapNotNull null
        val allowance = minOf(1_250, remaining - file.path.length - 20)
        if (allowance <= 0) return@mapNotNull null
        val text = source.take(allowance).trimEnd().trimEnd { it.isHighSurrogate() }
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
