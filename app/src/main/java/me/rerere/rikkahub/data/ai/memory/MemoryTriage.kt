package me.rerere.rikkahub.data.ai.memory

import me.rerere.rikkahub.data.db.entity.HistoryMemoryCandidate
import me.rerere.rikkahub.data.model.MemoryFile
import java.text.Normalizer
import java.util.Locale

/** 本地审核建议。只根据候选和已有记忆的字面内容判断，不调用模型、不产生费用。 */
enum class MemorySuggestion {
    /** 事实明确、有来源证据、之前没记过 */
    SAVE,

    /** 和已有记忆主题相同，但内容更新 */
    UPDATE,

    /** 和已有记忆高度重合，适合合并进去 */
    MERGE,

    /** 涉及身份、人物关系、冲突或信息不足，交给用户确认 */
    CONFIRM,

    /** 没有长期价值或已经记过 */
    IGNORE,
}

data class MemoryTriage(
    val suggestion: MemorySuggestion,
    /** UPDATE / MERGE 时要写入的已有记忆路径 */
    val targetPath: String? = null,
    /** 给用户看的理由，一句话 */
    val reason: String = "",
    /** 0..100，仅表示字面相似度，不代表事实真实性 */
    val overlap: Int = 0,
)

/**
 * 纯本地审核：同样的输入永远得到同样结果，方便单元测试。
 *
 * 刻意把“重要个人事实”和“有冲突”的候选留在 CONFIRM，不拿相似度当事实依据。
 */
object MemoryTriageEngine {
    private const val MERGE_THRESHOLD = 0.80
    private const val UPDATE_THRESHOLD = 0.50

    // 冲突判定比“相关”更敏感：说法相反的两条宁可让用户确认，也不要静默合并
    private const val CONFLICT_THRESHOLD = 0.32

    // 这类候选默认不自动保存：涉及身份、关系、长期承诺，猜错的代价比省几次点击大
    private val carefulCategories = setOf("people", "profile", "decisions")

    fun evaluate(candidate: HistoryMemoryCandidate, memories: List<MemoryFile>): MemoryTriage {
        val fact = candidate.fact.cleanFact()
        if (!hasLongTermValue(fact, candidate.evidence)) {
            return MemoryTriage(MemorySuggestion.IGNORE, reason = "内容太短或没有可长期使用的信息")
        }
        val key = factKey(fact)
        // 完全相同的句子已经记过：直接忽略，避免同一条事实反复堆积
        val duplicate = memories.firstOrNull { memory -> memory.statedKeys().contains(key) }
        if (duplicate != null) {
            return MemoryTriage(
                MemorySuggestion.IGNORE,
                targetPath = duplicate.path,
                reason = "已经记住过同样的事实",
                overlap = 100
            )
        }

        val grams = gramsOf(fact)
        var best: MemoryFile? = null
        var bestScore = 0.0
        for (memory in memories) {
            if (memory.isDeleted || memory.isOutdated) continue
            val score = containment(grams, gramsOf(memory.title + " " + memory.description + " " + memory.recallBody))
            if (score > bestScore) {
                bestScore = score
                best = memory
            }
        }
        val target = best
        val overlap = (bestScore * 100).toInt()
        // 冲突优先于合并：说法相反的两条不能被“相似度高”吞掉
        if (target != null && bestScore >= CONFLICT_THRESHOLD && conflicts(fact, target)) {
            return MemoryTriage(
                MemorySuggestion.CONFIRM,
                target.path,
                "和「${target.title}」的说法互相矛盾，请确认哪条有效",
                overlap,
            )
        }
        if (target != null && bestScore >= MERGE_THRESHOLD) {
            return MemoryTriage(MemorySuggestion.MERGE, target.path, "和「${target.title}」几乎重复，可以合并", overlap)
        }
        if (target != null && bestScore >= UPDATE_THRESHOLD) {
            return MemoryTriage(MemorySuggestion.UPDATE, target.path, "和「${target.title}」是同一件事，可以更新进去", overlap)
        }
        if (candidate.category in carefulCategories) {
            return MemoryTriage(
                MemorySuggestion.CONFIRM,
                reason = when (candidate.category) {
                    "people" -> "涉及人物和关系，请确认后再记"
                    "decisions" -> "涉及已经做过的决定，请确认后再记"
                    else -> "涉及你的个人情况，请确认后再记"
                },
                overlap = overlap,
            )
        }
        return MemoryTriage(MemorySuggestion.SAVE, reason = "明确、有来源，可以记住", overlap = overlap)
    }

    /** 一句话能不能长期使用：太短、纯寒暄、纯提问都不值得存。 */
    fun hasLongTermValue(fact: String, evidence: String): Boolean {
        val text = fact.trim()
        if (text.length !in 8..600) return false
        if (evidence.trim().length < 4) return false
        val meaningful = text.count { it.isLetterOrDigit() }
        if (meaningful < 6) return false
        if (text.endsWith("?") || text.endsWith("？")) return false
        return true
    }

    /** 同一主题但说法相反：至少一方出现否定词，另一方没有。 */
    internal fun conflicts(fact: String, memory: MemoryFile): Boolean =
        negationOf(fact) != negationOf(memory.recallBody)

    /** 明确的多字否定词组。 */
    private val negations = listOf(
        "不再", "没有", "还没", "尚未", "不用", "不想", "不喜欢", "不是", "不要", "不能",
        "不会", "从未", "从没", "无需", "不需", "别",
    )

    /**
     * 带「不/没/未」但**不是**否定的常见词。
     * 直接拿单字判断会把「不过」「不同」「不错」「未来」算成否定，两条同主题的候选就被判成互相矛盾，
     * 批量按建议永远只能逐条确认。
     */
    private val nonNegatingWords = listOf(
        "不过", "不同", "不错", "不少", "不但", "不仅", "不管", "不论", "不久", "不断",
        "不然", "不定", "未来", "未知", "没法", "没事",
    )

    private fun negationOf(text: String): Boolean {
        val sample = text.take(600)
        if (negations.any { it in sample }) return true
        var stripped = sample
        nonNegatingWords.forEach { stripped = stripped.replace(it, "") }
        return "不" in stripped || "没" in stripped || "未" in stripped
    }

    internal fun gramsOf(text: String): Set<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val grams = linkedSetOf<String>()
        val han = StringBuilder()
        val word = StringBuilder()
        fun flushHan() {
            // 中文用相邻两字做特征，单字重码太高会把不相关的记忆连在一起
            if (han.length == 1) grams += han.toString()
            for (i in 0 until han.length - 1) grams += han.substring(i, i + 2)
            han.setLength(0)
        }
        fun flushWord() {
            if (word.length >= 2) grams += word.toString()
            word.setLength(0)
        }
        for (ch in normalized) {
            when {
                ch.isLetterOrDigit() && Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN -> {
                    flushWord()
                    han.append(ch)
                }
                ch.isLetterOrDigit() -> {
                    flushHan()
                    word.append(ch)
                }
                else -> {
                    flushHan()
                    flushWord()
                }
            }
        }
        flushHan()
        flushWord()
        return grams
    }

    internal fun containment(left: Set<String>, right: Set<String>): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val common = left.count { it in right }
        return common.toDouble() / left.size
    }

    private fun MemoryFile.statedKeys(): Set<String> = body.lineSequence()
        .filter { it.startsWith("- [stated] ") }
        .map { factKey(it.removePrefix("- [stated] ")) }
        .toSet()
}
