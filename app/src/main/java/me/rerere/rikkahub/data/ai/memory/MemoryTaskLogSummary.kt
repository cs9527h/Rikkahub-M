package me.rerere.rikkahub.data.ai.memory

import me.rerere.rikkahub.data.db.entity.HistoryMemoryTaskLog

/**
 * 任务历史的展示映射。
 *
 * 抽成纯函数只为了一件事：这些文案就是用户判断“这轮到底做成没有”的依据，
 * 必须有 JVM 单测盯着，而不是靠肉眼在 Compose 里核对。
 */
internal object MemoryTaskLogSummary {
    fun statusLabel(status: String): String = when (status) {
        "running" -> "正在整理"
        "paused" -> "已暂停"
        "finished" -> "已完成"
        "cancelled" -> "已取消"
        "failed" -> "失败"
        "interrupted" -> "被系统中断"
        else -> status.ifBlank { "状态未知" }
    }

    /** 只有没能正常跑完的任务才谈得上重试；已完成/已取消的只留记录。 */
    fun canRetry(status: String): Boolean = status in setOf("failed", "paused", "interrupted")

    /** 处理进度。旧记录没有计划会话数时不要硬凑一个分母。 */
    fun progressLabel(sourceCount: Int, processedCount: Int): String {
        val total = sourceCount.coerceAtLeast(0)
        val done = processedCount.coerceAtLeast(0).coerceAtMost(if (total > 0) total else Int.MAX_VALUE)
        return if (total > 0) "处理 $done/$total 个会话" else "处理 $done 个会话"
    }

    fun candidateLabel(candidateCount: Int, savedCount: Int, skippedCount: Int): String =
        "候选 ${candidateCount.coerceAtLeast(0)} 条 · 已保存 ${savedCount.coerceAtLeast(0)} 条 · " +
            "已跳过 ${skippedCount.coerceAtLeast(0)} 条"

    /** 没有 Token 记录时返回 null：宁可不写，也不要显示“约 0 Token”让人以为免费。 */
    fun tokenLabel(inputTokens: Long, outputTokens: Long): String? {
        val input = inputTokens.coerceAtLeast(0)
        val output = outputTokens.coerceAtLeast(0)
        val total = input + output
        if (total <= 0) return null
        return "约 $total Token（输入 $input · 输出 $output）"
    }

    fun headline(log: HistoryMemoryTaskLog): String =
        "${statusLabel(log.status)} · ${progressLabel(log.sourceCount, log.processedCount)}"
}
