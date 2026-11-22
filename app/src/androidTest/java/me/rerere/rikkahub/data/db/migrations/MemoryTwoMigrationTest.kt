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
 * Room 36 → 37：长期记忆 2.0 只允许新增列和表。
 * 升级后待审核候选、任务游标和已保存记忆必须一条不少，已经整理完的记忆库不能被要求重新付费。
 */
@RunWith(AndroidJUnit4::class)
class MemoryTwoMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun validatesSchemaAndKeepsPendingCandidatesAndFinishedJob() {
        val name = "memory-two-migration-test"
        val scope = "00000000-0000-0000-0000-000000000010"
        val finishedScope = "00000000-0000-0000-0000-000000000011"
        val jobId = "job-synthetic"
        val before = helper.createDatabase(name, 36)
        try {
            before.execSQL(
                "INSERT INTO history_memory_job " +
                    "(scope, id, deviceConsent, modelId, providerFingerprint, sources, sourceCount, " +
                    "sourceIndex, nodeIndex, nodeId, textOffset, batches, estimatedTokens, status, failure, generation) " +
                    "VALUES (?, ?, 'device', 'model', 'fingerprint', '[]', 3, 2, -1, '', 0, 4, 900, 'running', NULL, 1)",
                arrayOf<Any?>(scope, jobId),
            )
            before.execSQL(
                "INSERT INTO history_memory_job " +
                    "(scope, id, deviceConsent, modelId, providerFingerprint, sources, sourceCount, " +
                    "sourceIndex, nodeIndex, nodeId, textOffset, batches, estimatedTokens, status, failure, generation) " +
                    "VALUES (?, 'job-done', 'device', 'model', 'fingerprint', '[]', 5, 5, -1, '', 0, 9, 2048, 'finished', NULL, 2)",
                arrayOf<Any?>(finishedScope),
            )
            before.execSQL(
                "INSERT INTO history_memory_candidate " +
                    "(jobId, `key`, category, subject, fact, evidence, conversationId, messageId, sourceTime, status) " +
                    "VALUES (?, 'k1', 'preferences', '阅读', '我喜欢晚上看小说', '我喜欢晚上看小说', " +
                    "'00000000-0000-0000-0000-000000000020', '00000000-0000-0000-0000-000000000021', " +
                    "'2026-01-01T00:00:00Z', 'pending')",
                arrayOf<Any?>(jobId),
            )
            before.execSQL(
                "INSERT INTO memory_file (assistant_id, path, content, updated_at, deleted_at) " +
                    "VALUES (?, '/profile.md', '已保存的记忆正文', 1000, 0)",
                arrayOf<Any?>(scope),
            )
        } finally {
            before.close()
        }

        val after = helper.runMigrationsAndValidate(name, 37, true, Migration_36_37)
        try {
            after.query("SELECT fact, evidence, status FROM history_memory_candidate WHERE jobId = '$jobId'").use {
                assertTrue(it.moveToFirst())
                assertEquals("我喜欢晚上看小说", it.getString(0))
                assertEquals("我喜欢晚上看小说", it.getString(1))
                assertEquals("pending", it.getString(2))
            }
            after.query("SELECT sourceIndex, status, createdAt, finishedAt, budgetAckTokens FROM history_memory_job WHERE scope = '$scope'").use {
                assertTrue(it.moveToFirst())
                assertEquals(2, it.getInt(0))
                assertEquals("running", it.getString(1))
                assertEquals(0L, it.getLong(2))
                assertEquals(0L, it.getLong(3))
                assertEquals(0L, it.getLong(4))
            }
            // 已完成的整理任务迁移后写入水位，第一次“整理我的记忆”不会把旧聊天再发一遍
            after.query("SELECT lastOrganizedAt FROM memory_scope_state WHERE scope = '$finishedScope'").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.getLong(0) > 0L)
            }
            after.query("SELECT lastOrganizedAt FROM memory_scope_state WHERE scope = '$scope'").use {
                assertEquals(0, it.count)
            }
            after.query("SELECT content FROM memory_file WHERE assistant_id = '$scope'").use {
                assertTrue(it.moveToFirst())
                assertEquals("已保存的记忆正文", it.getString(0))
            }
            // 新表必须可用：撤销日志与跨任务批次指纹
            after.execSQL(
                "INSERT INTO memory_bulk_undo (id, scope, jobId, createdAt, summary, payload) VALUES ('u1', ?, ?, 1, 's', '{}')",
                arrayOf<Any?>(scope, jobId),
            )
            after.execSQL(
                "INSERT INTO history_memory_seen (digest, createdAt) VALUES ('digest-1', 1)",
            )
            after.query("SELECT COUNT(*) FROM memory_bulk_undo").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
        } finally {
            after.close()
        }
    }
}
