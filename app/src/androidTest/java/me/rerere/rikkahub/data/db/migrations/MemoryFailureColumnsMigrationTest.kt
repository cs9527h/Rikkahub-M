package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 40 → 41：只给任务表和任务历史表加“失败阶段/错误码/跳过计数”列。
 *
 * 迁移必须是纯增列：已有的记忆、候选、任务游标、Token 计数和聊天数据一项都不能变，
 * 否则用户升级一次就会丢进度或丢候选。
 */
@RunWith(AndroidJUnit4::class)
class MemoryFailureColumnsMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun addsFailureColumnsAndKeepsExistingProgressAndCandidates() {
        val name = "memory-failure-columns-migration-test"
        helper.createDatabase(name, 40).use { before ->
            before.execSQL(
                "INSERT INTO memory_file (assistant_id, path, content, updated_at, deleted_at) " +
                    "VALUES ('scope-a', '/topics/history-1.md', 'Synthetic saved memory', 42, 0)",
            )
            before.execSQL(
                "INSERT INTO history_memory_job (scope, id, deviceConsent, modelId, providerFingerprint, sources, " +
                    "sourceCount, sourceIndex, nodeIndex, nodeId, textOffset, batches, estimatedTokens, status, " +
                    "failure, generation, createdAt, finishedAt, budgetAckTokens) " +
                    "VALUES ('scope-a', 'job-1', 'device-a', 'model-a', 'fingerprint-a', '[]', 7, 3, 2, 'node-1', 5, " +
                    "4, 900, 'failed', '读取历史或调用模型失败，可重试当前批次', 6, 1000, 2000, 800)",
            )
            before.execSQL(
                "INSERT INTO history_memory_task_log (id, scope, jobGeneration, createdAt, finishedAt, status, " +
                    "sourceCount, processedCount, candidateCount, savedCount, skippedCount, inputTokens, outputTokens, " +
                    "rangeLabel, errorMessage) " +
                    "VALUES ('job-1', 'scope-a', 6, 1000, 2000, 'failed', 7, 3, 5, 2, 1, 700, 200, " +
                    "'7 个会话 · 2025-01-02 ~ 2025-03-04', '读取历史或调用模型失败，可重试当前批次')",
            )
            before.execSQL(
                "INSERT INTO history_memory_candidate (jobId, `key`, scope, category, subject, fact, evidence, " +
                    "conversationId, messageId, sourceTime, status) " +
                    "VALUES ('job-1', 'key-1', 'scope-a', 'projects', 'Synthetic subject', 'Synthetic fact', " +
                    "'Synthetic evidence', 'conversation-1', 'message-1', '2025-01-02T00:00', 'pending')",
            )
        }

        val after = helper.runMigrationsAndValidate(name, 41, true, Migration_40_41)
        try {
            // 已有进度和计数必须原样保留
            after.query(
                "SELECT status, sourceIndex, batches, estimatedTokens, generation, failure, " +
                    "failureStage, failureCode, failureDetail, batchAttempts FROM history_memory_job WHERE id = 'job-1'"
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("failed", it.getString(0))
                assertEquals(3, it.getInt(1))
                assertEquals(4, it.getInt(2))
                assertEquals(900L, it.getLong(3))
                assertEquals(6L, it.getLong(4))
                assertEquals("读取历史或调用模型失败，可重试当前批次", it.getString(5))
                // 老任务没有阶段信息：留空，界面退回原来的单行原因
                assertTrue(it.isNull(6))
                assertTrue(it.isNull(7))
                assertTrue(it.isNull(8))
                assertEquals(0, it.getInt(9))
            }
            after.query(
                "SELECT status, processedCount, candidateCount, savedCount, skippedCount, inputTokens, outputTokens, " +
                    "errorMessage, errorStage, errorCode, errorDetail, skippedBatches, skippedSources " +
                    "FROM history_memory_task_log WHERE id = 'job-1'"
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("failed", it.getString(0))
                assertEquals(3, it.getInt(1))
                assertEquals(5, it.getInt(2))
                assertEquals(2, it.getInt(3))
                assertEquals(1, it.getInt(4))
                assertEquals(700L, it.getLong(5))
                assertEquals(200L, it.getLong(6))
                assertEquals("读取历史或调用模型失败，可重试当前批次", it.getString(7))
                assertTrue(it.isNull(8))
                assertTrue(it.isNull(9))
                assertTrue(it.isNull(10))
                assertEquals(0, it.getInt(11))
                assertEquals(0, it.getInt(12))
            }
            // 候选正文与状态不能被动过
            after.query(
                "SELECT subject, fact, evidence, status FROM history_memory_candidate WHERE `key` = 'key-1'"
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("Synthetic subject", it.getString(0))
                assertEquals("Synthetic fact", it.getString(1))
                assertEquals("Synthetic evidence", it.getString(2))
                assertEquals("pending", it.getString(3))
            }
            after.query("SELECT content FROM memory_file WHERE path = '/topics/history-1.md'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Synthetic saved memory", it.getString(0))
            }

            // 新列可写：阶段/错误码/跳过计数在这张表上是真实可用的
            after.execSQL(
                "UPDATE history_memory_job SET failureStage = 'model_call', failureCode = 'network', " +
                    "failureDetail = '阶段=model_call 错误码=network', batchAttempts = 2 WHERE id = 'job-1'",
            )
            after.execSQL(
                "UPDATE history_memory_task_log SET errorStage = 'model_call', errorCode = 'network', " +
                    "skippedBatches = 1, skippedSources = 2 WHERE id = 'job-1'",
            )
            after.query(
                "SELECT failureStage, failureCode, batchAttempts FROM history_memory_job WHERE id = 'job-1'"
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("model_call", it.getString(0))
                assertEquals("network", it.getString(1))
                assertEquals(2, it.getInt(2))
            }
            after.query(
                "SELECT errorStage, errorCode, skippedBatches, skippedSources FROM history_memory_task_log WHERE id = 'job-1'"
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("model_call", it.getString(0))
                assertEquals("network", it.getString(1))
                assertEquals(1, it.getInt(2))
                assertEquals(2, it.getInt(3))
            }
        } finally {
            after.close()
        }
    }
}
