package me.rerere.rikkahub.data.ai.memory

import me.rerere.rikkahub.data.model.MemoryFile
import java.text.Normalizer
import java.util.Locale

/**
 * 多阶段召回：
 * 1. 精确短语 / 别名命中；
 * 2. 中文关键词（二元组）命中；
 * 3. 语义近似与主题扩展（不依赖任何 Embedding API）；
 * 4. 按重要程度、更新时间重排，交给上层按 Token 预算截取。
 *
 * 纯本地、可离线、结果可解释；每条命中都会给出原因，供使用记录展示。
 */
internal data class RecallQuery(
    val raw: String,
    /** 中文二元组 / 英文单词 */
    val terms: Set<String>,
    /** 整句或分句短语，用于第一阶段精确命中 */
    val phrases: List<String>,
    /** 宽泛问题：没有具体主题词，改为按重要程度和最近更新组织 */
    val broad: Boolean,
    /** 中文二元组，用于语义近似 */
    val bigrams: Set<String>,
)

internal data class RecallCandidate(
    val file: MemoryFile,
    val score: Int,
    val stage: Int,
    val reasons: List<String>,
)

internal data class RecallResult(
    val files: List<MemoryFile>,
    val considered: Int,
    val filtered: Int,
    val query: String,
    val broad: Boolean,
    /** 过滤原因计数，例如「低相关 120 条」 */
    val filterReasons: Map<String, Int>,
)

internal object MemoryRecallEngine {
    private const val MIN_SCORE = 8

    private val phraseSplit = Regex("""[，。！？；,!?;\n]+""")
    private val broadHints = listOf(
        "记得", "记不记得", "还记得", "以前", "之前", "曾经", "聊过", "问过",
        "说过", "什么", "哪些", "有没有", "忘了", "回忆",
    )
    private val latinStop = setOf(
        "the", "and", "for", "what", "how", "can", "you", "that", "with", "about", "this",
        "remember", "before", "did", "does", "have", "your", "was", "were", "are",
    )

    fun analyze(text: String): RecallQuery {
        val raw = text.take(2_000).trim()
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val phrases = phraseSplit.split(normalized)
            .map { it.trim() }
            .filter { it.length in 3..80 }
            .distinct()
            .take(4)
        val terms = recallTerms(raw)
        val broad = terms.size < 3 || broadHints.any { it in normalized } &&
            !terms.any { term -> term.length >= 3 && term !in broadHints }
        return RecallQuery(
            raw = raw,
            terms = terms,
            phrases = phrases,
            broad = broad,
            bigrams = cjkBigrams(normalized),
        )
    }

    fun rank(files: List<MemoryFile>, query: RecallQuery, limit: Int = 10): List<RecallCandidate> =
        files.asSequence()
            .mapNotNull { score(it, query) }
            .sortedWith(
                compareByDescending<RecallCandidate> { it.score }
                    .thenByDescending { it.file.importance * 3 + recencyBonus(it.file.updatedAt) }
                    .thenBy { it.file.path },
            )
            .distinctBy { it.file.title.lowercase(Locale.ROOT) }
            .take(limit)
            .toList()

    fun score(file: MemoryFile, query: RecallQuery): RecallCandidate? {
        if (file.isOutdated || file.isDeleted) return null
        val heading = listOf(file.title, file.description, file.aliases.joinToString(" "), file.path)
            .joinToString(" ").lowercase(Locale.ROOT)
        val body = file.recallBody.lowercase(Locale.ROOT)
        val reasons = mutableListOf<String>()
        var score = 0
        var stage = 3

        // 第一阶段：整句短语、别名
        for (phrase in query.phrases) {
            when {
                phrase in heading -> {
                    score += 36 + phrase.length
                    stage = 1
                    reasons += "标题命中「$phrase」"
                }
                phrase in body -> {
                    score += 20 + phrase.length
                    stage = 1
                    reasons += "正文命中原句"
                }
            }
        }
        for (alias in file.aliases) {
            val normalizedAlias = alias.trim().lowercase(Locale.ROOT)
            if (normalizedAlias.length >= 2 && normalizedAlias in query.raw.lowercase(Locale.ROOT)) {
                score += 18
                stage = 1
                reasons += "别名「$alias」"
            }
        }

        // 第二阶段：关键词
        var headingHits = 0
        var bodyHits = 0
        for (term in query.terms) {
            if (term in heading) {
                headingHits++
                score += 8
            } else if (term in body) {
                bodyHits++
                score += 3
            }
        }
        if (headingHits > 0 && stage > 2) stage = 2
        if (headingHits + bodyHits > 0) {
            reasons += "标题命中 $headingHits 个 · 正文命中 $bodyHits 个关键词"
        }

        // 第三阶段：语义近似（中文二元组包含度）与主题扩展
        var fuzzy = 0.0
        if (query.bigrams.isNotEmpty()) {
            val textBigrams = bigramsOf(file)
            if (textBigrams.isNotEmpty()) {
                fuzzy = query.bigrams.count { it in textBigrams }.toDouble() / query.bigrams.size
            }
        }
        if (fuzzy >= 0.25) {
            score += (fuzzy * 26).toInt()
            if (stage > 2) stage = 2
            reasons += "语义近似 ${(fuzzy * 100).toInt()}%"
        }

        val specific = score > 0
        // 宽泛问题：没有具体命中时，用重要程度与近期更新兜底，而不是机械要求两个关键词
        if (!specific && query.broad) {
            val bonus = recencyBonus(file.updatedAt)
            if (file.importance >= 3 || bonus >= 2) {
                return RecallCandidate(
                    file = file,
                    score = 10 + file.importance * 2 + bonus,
                    stage = 4,
                    reasons = listOfNotNull(
                        "广泛问题：按重要程度与最近更新召回",
                        "重要程度 ${file.importance}".takeIf { file.importance > 0 },
                    ),
                )
            }
            return null
        }

        // 相关性门槛：与旧实现相比，要求「短语/别名」或「标题命中且至少两个信号」或「正文三次」或「语义近似」
        val relevant = query.phrases.any { it in heading || it in body } ||
            file.aliases.any { it.trim().length >= 2 && it.lowercase(Locale.ROOT) in query.raw.lowercase(Locale.ROOT) } ||
            headingHits >= 1 && headingHits + bodyHits >= 2 ||
            bodyHits >= 3 ||
            fuzzy >= 0.35
        if (!relevant || score < MIN_SCORE) return null

        score += file.importance * 3
        if (file.importance > 0) reasons += "重要程度 ${file.importance}"
        val recency = recencyBonus(file.updatedAt)
        score += recency
        if (recency > 0) reasons += "最近更新"

        return RecallCandidate(file, score, stage, reasons)
    }

    fun recencyBonus(updatedAt: Long): Int {
        val age = System.currentTimeMillis() - updatedAt
        return when {
            age < 24L * 60 * 60 * 1000 -> 4
            age < 7L * 24 * 60 * 60 * 1000 -> 2
            age < 30L * 24 * 60 * 60 * 1000 -> 1
            else -> 0
        }
    }

    private fun cjkBigrams(text: String): Set<String> {
        val result = LinkedHashSet<String>()
        val run = StringBuilder()
        fun flush() {
            if (run.length == 1) result += run.toString()
            for (i in 0 until run.length - 1) result += run.substring(i, i + 2)
            run.setLength(0)
        }
        for (ch in text) {
            if (Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN) {
                run.append(ch)
            } else {
                flush()
            }
        }
        flush()
        return result
    }

    private fun bigramsOf(file: MemoryFile): Set<String> {
        val key = "${file.path}:${file.updatedAt}:${file.version}"
        BigramCache.get(key)?.let { return it }
        val value = cjkBigrams(
            (file.title + " " + file.description + " " + file.aliases.joinToString(" ") + " " + file.recallBody)
                .lowercase(Locale.ROOT),
        )
        BigramCache.put(key, value)
        return value
    }

    /** 小容量 LRU：同一批记忆在连续多轮聊天中不必重复切词。 */
    private object BigramCache {
        private const val MAX = 2_048
        private val map = object : LinkedHashMap<String, Set<String>>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Set<String>>?): Boolean =
                size > MAX
        }

        @Synchronized
        fun get(key: String): Set<String>? = map[key]

        @Synchronized
        fun put(key: String, value: Set<String>) {
            if (value.isNotEmpty()) map[key] = value
        }
    }
}

/** 过滤原因统计，用于“为什么这轮只有 3 条”。 */
internal fun MemoryFile.filteringReason(query: RecallQuery): String? = when {
    isDeleted -> "已删除"
    isOutdated -> "已过时"
    else -> null
}
