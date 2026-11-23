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

/** Room 39 → 40：新增任务历史表，已有记忆、任务和聊天数据必须原样保留。 */
@RunWith(AndroidJUnit4::class)
class MemoryTaskLogMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun addsTaskLogTableAndKeepsExistingRows() {
        val name = "memory-task-log-migration-test"
        val before = helper.createDatabase(name, 39)
        try {
            before.execSQL(
                "INSERT INTO memory_scope_state (scope, lastOrganizedAt, lastResult) " +
                    "VALUES ('scope-a', 1234, '新增 2 条 · 需要确认 1 条')",
            )
            before.execSQL(
                "INSERT INTO history_memory_job (scope, id, deviceConsent, modelId, providerFingerprint, " +
                    "sources, sourceCount, sourceIndex, nodeIndex, nodeId, textOffset, batches, estimatedTokens, " +
                    "status, failure, generation) " +
                    "VALUES ('scope-a', 'job-1', 'device-a', 'model-a', 'fingerprint-a', '[]', 3, 1, -1, '', 0, 1, 120, 'running', NULL, 2)",
            )
        } finally {
            before.close()
        }

        val after = helper.runMigrationsAndValidate(name, 40, true, Migration_39_40)
        try {
            after.query("SELECT lastOrganizedAt, lastResult FROM memory_scope_state WHERE scope = 'scope-a'").use {
                assertTrue(it.moveToFirst())
                assertEquals(1234L, it.getLong(0))
                assertEquals("新增 2 条 · 需要确认 1 条", it.getString(1))
            }
            after.query("SELECT status, sourceCount, sourceIndex, generation FROM history_memory_job WHERE id = 'job-1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("running", it.getString(0))
                assertEquals(3, it.getInt(1))
                assertEquals(1, it.getInt(2))
                assertEquals(2L, it.getLong(3))
            }

            // 新表可以直接写入，未提供的列走迁移里声明的默认值
            after.execSQL("INSERT INTO history_memory_task_log (id, scope) VALUES ('job-1', 'scope-a')")
            after.query(
                "SELECT status, sourceCount, processedCount, candidateCount, savedCount, skippedCount, " +
                    "inputTokens, outputTokens, rangeLabel, errorMessage, finishedAt " +
                    "FROM history_memory_task_log WHERE id = 'job-1'"
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("running", it.getString(0))
                assertEquals(0, it.getInt(1))
                assertEquals(0, it.getInt(2))
                assertEquals(0, it.getInt(3))
                assertEquals(0, it.getInt(4))
                assertEquals(0, it.getInt(5))
                assertEquals(0L, it.getLong(6))
                assertEquals(0L, it.getLong(7))
                assertEquals("", it.getString(8))
                assertTrue(it.isNull(9))
                assertEquals(0L, it.getLong(10))
            }
            after.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' " +
                    "AND name = 'index_history_memory_task_log_scope_createdAt'"
            ).use {
                assertTrue(it.moveToFirst())
            }
        } finally {
            after.close()
        }
    }
}
