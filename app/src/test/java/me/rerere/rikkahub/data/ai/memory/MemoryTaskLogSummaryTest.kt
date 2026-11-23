package me.rerere.rikkahub.data.ai.memory

import me.rerere.rikkahub.data.db.entity.HistoryMemoryTaskLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 任务历史的文案与数字口径：这些字符串就是用户判断“这轮做成没有”的依据。 */
class MemoryTaskLogSummaryTest {
    @Test
    fun everyStoredStatusHasAReadableLabel() {
        assertEquals("正在整理", MemoryTaskLogSummary.statusLabel("running"))
        assertEquals("已暂停", MemoryTaskLogSummary.statusLabel("paused"))
        assertEquals("已完成", MemoryTaskLogSummary.statusLabel("finished"))
        assertEquals("已取消", MemoryTaskLogSummary.statusLabel("cancelled"))
        assertEquals("失败", MemoryTaskLogSummary.statusLabel("failed"))
        assertEquals("被系统中断", MemoryTaskLogSummary.statusLabel("interrupted"))
        // 未来新增状态或空串不能显示成空白项
        assertEquals("queued", MemoryTaskLogSummary.statusLabel("queued"))
        assertEquals("状态未知", MemoryTaskLogSummary.statusLabel(""))
    }

    @Test
    fun onlyUnfinishedRunsOfferRetry() {
        assertTrue(MemoryTaskLogSummary.canRetry("failed"))
        assertTrue(MemoryTaskLogSummary.canRetry("paused"))
        assertTrue(MemoryTaskLogSummary.canRetry("interrupted"))
        assertFalse(MemoryTaskLogSummary.canRetry("finished"))
        assertFalse(MemoryTaskLogSummary.canRetry("cancelled"))
        assertFalse(MemoryTaskLogSummary.canRetry("running"))
        assertFalse(MemoryTaskLogSummary.canRetry(""))
    }

    @Test
    fun progressNeverExceedsPlannedSources() {
        assertEquals("处理 3/5 个会话", MemoryTaskLogSummary.progressLabel(5, 3))
        // 游标偶尔会先走一步（读完最后一个会话后才写 finished），不能显示 6/5
        assertEquals("处理 5/5 个会话", MemoryTaskLogSummary.progressLabel(5, 6))
        // 旧记录没有计划会话数时不硬凑分母
        assertEquals("处理 2 个会话", MemoryTaskLogSummary.progressLabel(0, 2))
        assertEquals("处理 0 个会话", MemoryTaskLogSummary.progressLabel(0, 0))
        // 负数（不应该出现，但历史数据来自旧版本）也不能显示成负进度
        assertEquals("处理 0/3 个会话", MemoryTaskLogSummary.progressLabel(3, -1))
    }

    @Test
    fun candidateCountsAreAlwaysShownSeparately() {
        assertEquals(
            "候选 8 条 · 已保存 3 条 · 已跳过 1 条",
            MemoryTaskLogSummary.candidateLabel(8, 3, 1),
        )
        assertEquals(
            "候选 0 条 · 已保存 0 条 · 已跳过 0 条",
            MemoryTaskLogSummary.candidateLabel(-1, -5, -2),
        )
    }

    @Test
    fun missingTokenRecordShowsNothingInsteadOfZero() {
        assertNull(MemoryTaskLogSummary.tokenLabel(0, 0))
        assertNull(MemoryTaskLogSummary.tokenLabel(-3, -1))
        assertEquals(
            "约 1300 Token（输入 1000 · 输出 300）",
            MemoryTaskLogSummary.tokenLabel(1000, 300),
        )
        assertEquals("约 20 Token（输入 20 · 输出 0）", MemoryTaskLogSummary.tokenLabel(20, 0))
    }

    @Test
    fun headlineCombinesStatusAndProgress() {
        val log = HistoryMemoryTaskLog(
            id = "job-1",
            scope = "scope-a",
            status = "failed",
            sourceCount = 4,
            processedCount = 2,
        )
        assertEquals("失败 · 处理 2/4 个会话", MemoryTaskLogSummary.headline(log))
    }
}
