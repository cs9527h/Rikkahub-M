package me.rerere.rikkahub.data.ai.memory

import me.rerere.rikkahub.BuildConfig
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

    /** 失败阶段的中文名；旧记录没有阶段时返回 null，界面退回原来的单行原因。 */
    fun stageLabel(stageId: String?): String? =
        stageId?.takeIf { it.isNotBlank() }?.let { MemoryStage.from(it).label }

    /** 列表/详情里的“在哪一步失败”：阶段 + 面向用户的原因。 */
    fun failedLine(log: HistoryMemoryTaskLog): String? {
        val stage = stageLabel(log.errorStage) ?: return null
        val code = log.errorCode
        return if (code.isNullOrBlank()) {
            "在「$stage」阶段失败"
        } else {
            "在「$stage」阶段失败：${MemoryFailureText.message(code)}"
        }
    }

    /** 可执行建议：和写入失败时用的是同一份文案。 */
    fun failureAdvice(log: HistoryMemoryTaskLog): String? =
        log.errorCode?.takeIf { it.isNotBlank() }?.let { MemoryFailureText.advice(it) }

    /** 被跳过的批次/会话：让“进度不动”和“没有报错”都有据可查。 */
    fun skipLabel(log: HistoryMemoryTaskLog): String? {
        val batches = log.skippedBatches.coerceAtLeast(0)
        val sources = log.skippedSources.coerceAtLeast(0)
        if (batches == 0 && sources == 0) return null
        return buildList {
            if (batches > 0) add("跳过 $batches 个批次")
            if (sources > 0) add("跳过 $sources 个会话")
        }.joinToString(" · ")
    }

    /**
     * 可复制的诊断文本。
     *
     * 只包含阶段、错误码、异常类名、游标、Token 和时间这类结构性信息：
     * 不含聊天正文、候选正文、API Key、token 或带查询串的 URL，贴到 issue 里也不会泄漏内容。
     */
    fun diagnosticText(log: HistoryMemoryTaskLog): String = buildString {
        appendLine("整理任务诊断")
        appendLine("任务 ID: ${log.id}")
        appendLine("状态: ${statusLabel(log.status)}")
        stageLabel(log.errorStage)?.let { appendLine("失败阶段: ${log.errorStage}（$it）") }
        log.errorCode?.takeIf { it.isNotBlank() }?.let { appendLine("错误码: $it") }
        log.errorMessage?.takeIf { it.isNotBlank() }?.let { appendLine("原因: ${sanitizeFailureDetail(it, 160)}") }
        log.errorDetail?.takeIf { it.isNotBlank() }?.let { appendLine("技术摘要: $it") }
        appendLine(progressLabel(log.sourceCount, log.processedCount))
        appendLine(
            "候选 ${log.candidateCount.coerceAtLeast(0)} 条 · 已保存 ${log.savedCount.coerceAtLeast(0)} 条 · " +
                "已跳过 ${log.skippedCount.coerceAtLeast(0)} 条"
        )
        skipLabel(log)?.let { appendLine("自动跳过: $it") }
        tokenLabel(log.inputTokens, log.outputTokens)?.let { appendLine(it) }
        if (log.rangeLabel.isNotBlank()) appendLine("来源: ${log.rangeLabel}")
        appendLine("创建时间: ${log.createdAt}")
        if (log.finishedAt > 0) appendLine("结束时间: ${log.finishedAt}")
        append("版本: ${BuildConfig.VERSION_NAME} · 记忆任务历史")
    }
}
