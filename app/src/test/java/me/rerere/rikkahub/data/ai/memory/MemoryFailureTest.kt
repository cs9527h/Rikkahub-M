package me.rerere.rikkahub.data.ai.memory

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.serialization.SerializationException
import me.rerere.rikkahub.data.db.entity.HistoryMemoryTaskLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败分类、脱敏和诊断文案的口径。
 *
 * 这些字符串会直接出现在历史页和用户复制的诊断里：分类错一次，用户就会被引导去做没用的操作；
 * 脱敏漏一次，聊天正文或 Key 就会跟着诊断被贴出去。所以必须有单测盯着。
 */
class MemoryFailureTest {
    @Test
    fun stageIdsAreStableAndUnknownValuesDegrade() {
        assertEquals(MemoryStage.READING_HISTORY, MemoryStage.from("reading_history"))
        assertEquals(MemoryStage.MODEL_CALL, MemoryStage.from("model_call"))
        assertEquals(MemoryStage.RESPONSE_PARSE, MemoryStage.from("response_parse"))
        assertEquals(MemoryStage.DB_WRITE, MemoryStage.from("db_write"))
        assertEquals(MemoryStage.WORKER_SCHEDULE, MemoryStage.from("worker_schedule"))
        // 旧版本记录、脏数据、空串都必须能读，不能抛异常也不能显示空白
        assertEquals(MemoryStage.UNKNOWN, MemoryStage.from(null))
        assertEquals(MemoryStage.UNKNOWN, MemoryStage.from(""))
        assertEquals(MemoryStage.UNKNOWN, MemoryStage.from("something_new"))
        assertEquals("未知阶段", MemoryStage.from("something_new").label)
        // 只有真正发出去过请求的阶段才算“可能已计费”
        assertTrue(MemoryStage.MODEL_CALL.isBillable)
        assertTrue(MemoryStage.RESPONSE_PARSE.isBillable)
        assertTrue(MemoryStage.DB_WRITE.isBillable)
        assertFalse(MemoryStage.MODEL_CONFIG.isBillable)
        assertFalse(MemoryStage.READING_HISTORY.isBillable)
        assertFalse(MemoryStage.WORKER_SCHEDULE.isBillable)
    }

    @Test
    fun timeoutsAndNetworkErrorsAreClassifiedSeparately() {
        assertEquals(
            MemoryFailureCode.TIMEOUT,
            memoryFailure(MemoryStage.MODEL_CALL, SocketTimeoutException("timeout")).code,
        )
        assertEquals(
            MemoryFailureCode.TIMEOUT,
            memoryFailure(MemoryStage.MODEL_CALL, InterruptedIOException("timeout")).code,
        )
        assertEquals(
            MemoryFailureCode.NETWORK,
            memoryFailure(MemoryStage.MODEL_CALL, UnknownHostException("api.example.com")).code,
        )
        assertEquals(
            MemoryFailureCode.NETWORK,
            memoryFailure(MemoryStage.MODEL_CALL, IOException("connection reset")).code,
        )
        // 包装在别的异常里也要能认出根本原因
        assertEquals(
            MemoryFailureCode.NETWORK,
            memoryFailure(MemoryStage.MODEL_CALL, IllegalStateException(IOException("broken pipe"))).code,
        )
    }

    @Test
    fun httpStatusDecidesAuthQuotaContextAndServerErrors() {
        assertEquals(
            MemoryFailureCode.AUTH,
            memoryFailure(MemoryStage.MODEL_CALL, RuntimeException("HTTP 401 Unauthorized")).code,
        )
        assertEquals(
            MemoryFailureCode.AUTH,
            memoryFailure(MemoryStage.MODEL_CALL, RuntimeException("403 forbidden")).code,
        )
        assertEquals(
            MemoryFailureCode.QUOTA,
            memoryFailure(MemoryStage.MODEL_CALL, RuntimeException("429 Too Many Requests")).code,
        )
        assertEquals(
            MemoryFailureCode.CONTEXT,
            memoryFailure(MemoryStage.MODEL_CALL, RuntimeException("400 maximum context length is 8192 tokens")).code,
        )
        assertEquals(
            MemoryFailureCode.NETWORK,
            memoryFailure(MemoryStage.MODEL_CALL, RuntimeException("503 Service Unavailable")).code,
        )
        assertEquals(
            MemoryFailureCode.REQUEST,
            memoryFailure(MemoryStage.MODEL_CALL, RuntimeException("400 invalid model")).code,
        )
        // 数字里带 4xx 片段但不是状态码时不能误判
        assertNull(httpStatusOf(RuntimeException("token count 4096 exceeds budget 8192")))
    }

    @Test
    fun parseAndDatabaseFailuresUseTheirOwnCodes() {
        assertEquals(
            MemoryFailureCode.PARSE,
            memoryFailure(MemoryStage.RESPONSE_PARSE, SerializationException("unexpected json")).code,
        )
        assertEquals(
            MemoryFailureCode.PARSE,
            memoryFailure(MemoryStage.RESPONSE_PARSE, IllegalArgumentException("not json")).code,
        )
        assertEquals(
            MemoryFailureCode.DATABASE,
            memoryFailure(MemoryStage.DB_WRITE, SQLiteDiskIOException("disk I/O")).code,
        )
        // 类名带 SQLite 时，即使阶段判断不出来也按数据库问题解释
        assertEquals(
            MemoryFailureCode.DATABASE,
            memoryFailure(MemoryStage.MODEL_CALL, SQLiteDiskIOException("disk I/O")).code,
        )
        assertEquals(
            MemoryFailureCode.CONFIG,
            memoryFailure(MemoryStage.MODEL_CONFIG, IllegalStateException("provider missing")).code,
        )
        assertEquals(
            MemoryFailureCode.UNKNOWN,
            memoryFailure(MemoryStage.UNKNOWN, RuntimeException("???")).code,
        )
    }

    @Test
    fun secretsChatTextAndUrlQueriesNeverReachTheDetail() {
        val raw = "POST https://api.example.com/v1/chat?key=SECRET&model=x failed: " +
            "Authorization: Bearer abc.def.ghi123 sk-proj-ABCDEF123456 " +
            "api_key=topsecret123 用户说：我的身份证号是 110101199001011234 这句话是聊天正文"
        val detail = sanitizeFailureDetail(raw)
        assertFalse(detail.contains("SECRET"))
        assertFalse(detail.contains("abc.def.ghi123"))
        assertFalse(detail.contains("sk-proj-ABCDEF123456"))
        assertFalse(detail.contains("topsecret123"))
        // URL 去掉查询串，只留 host 和 path
        assertTrue(detail.contains("https://api.example.com/v1/chat"))
        assertFalse(detail.contains("?key="))
        // 正文再长也会被截断成一行
        assertFalse(detail.contains("\n"))
        assertTrue(detail.length <= 201)
    }

    @Test
    fun longMessagesAreTruncatedAndDefinitionStaysShort() {
        val long = "x".repeat(5_000)
        val sanitized = sanitizeFailureDetail(long, limit = 120)
        assertTrue(sanitized.endsWith("…"))
        assertTrue(sanitized.length <= 121)

        val failure = memoryFailure(MemoryStage.MODEL_CALL, IOException("boom $long"))
        assertTrue(failure.detail.length <= 400)
        assertTrue(failure.detail.contains("阶段=model_call"))
        assertTrue(failure.detail.contains("错误码=network"))
        assertTrue(failure.detail.contains("IOException"))
        // 用户文案是“一句话 + 建议”，不能带原始异常
        assertFalse(failure.userText.contains("boom"))
    }

    @Test
    fun taskLogShowsStageAdviceSkipCountsAndSafeDiagnostics() {
        val log = HistoryMemoryTaskLog(
            id = "job-1",
            scope = "scope-a",
            createdAt = 1_700_000_000_000,
            finishedAt = 1_700_000_100_000,
            status = "failed",
            sourceCount = 12,
            processedCount = 3,
            candidateCount = 5,
            savedCount = 2,
            skippedCount = 1,
            inputTokens = 700,
            outputTokens = 200,
            errorMessage = "调用模型时网络中断，或模型服务返回了服务端错误。点“从这一批继续”……",
            errorStage = "model_call",
            errorCode = "network",
            errorDetail = "阶段=model_call 错误码=network 异常=java.io.IOException 状态码=- 摘要=connection reset",
            skippedBatches = 1,
            skippedSources = 2,
        )
        assertEquals("调用模型", MemoryTaskLogSummary.stageLabel(log.errorStage))
        assertEquals(
            "在「调用模型」阶段失败：调用模型时网络中断，或模型服务返回了服务端错误。",
            MemoryTaskLogSummary.failedLine(log),
        )
        assertEquals(MemoryFailureText.advice("network"), MemoryTaskLogSummary.failureAdvice(log))
        assertEquals("跳过 1 个批次 · 跳过 2 个会话", MemoryTaskLogSummary.skipLabel(log))

        val diagnostic = MemoryTaskLogSummary.diagnosticText(log)
        assertTrue(diagnostic.contains("失败阶段: model_call（调用模型）"))
        assertTrue(diagnostic.contains("错误码: network"))
        assertTrue(diagnostic.contains("任务 ID: job-1"))
        assertTrue(diagnostic.contains("处理 3/12 个会话"))
        assertTrue(diagnostic.contains("约 900 Token（输入 700 · 输出 200）"))
        // 诊断里只有结构性信息，没有候选/聊天内容
        assertFalse(diagnostic.contains("fact"))
        assertFalse(diagnostic.contains("身份证"))
    }

    @Test
    fun oldRowsWithoutStageKeepTheOriginalReason() {
        val log = HistoryMemoryTaskLog(
            id = "job-old",
            scope = "scope-a",
            status = "failed",
            sourceCount = 4,
            processedCount = 1,
            errorMessage = "读取历史或调用模型失败，可重试当前批次",
        )
        assertNull(MemoryTaskLogSummary.failedLine(log))
        assertNull(MemoryTaskLogSummary.failureAdvice(log))
        assertNull(MemoryTaskLogSummary.skipLabel(log))
        assertTrue(MemoryTaskLogSummary.diagnosticText(log).contains("原因: 读取历史或调用模型失败"))
    }

    /** 不依赖 Android 运行时：类名带 SQLite 就按数据库异常处理。 */
    private class SQLiteDiskIOException(message: String) : RuntimeException(message)
}
