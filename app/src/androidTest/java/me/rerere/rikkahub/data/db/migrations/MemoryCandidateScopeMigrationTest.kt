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
 * Room 37 → 38：候选新增 scope 冗余列，并按任务表回填。
 *
 * 这一步是“误跳过恢复”的基础：任务行被替换后，旧候选仍然找得到归属。
 * 迁移不得删除或改写候选正文、证据与来源。
 */
@RunWith(AndroidJUnit4::class)
class MemoryCandidateScopeMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun validatesSchemaAndBackfillsScopeWithoutLosingCandidates() {
        val name = "memory-candidate-scope-migration-test"
        val scope = "00000000-0000-0000-0000-0000000000c1"
        val jobId = "scope-job"
        val before = helper.createDatabase(name, 37)
        try {
            before.execSQL(
                "INSERT INTO history_memory_job " +
                    "(scope, id, deviceConsent, modelId, providerFingerprint, sources, sourceCount, " +
                    "sourceIndex, nodeIndex, nodeId, textOffset, batches, estimatedTokens, status, failure, generation, " +
                    "createdAt, finishedAt, budgetAckTokens) " +
                    "VALUES (?, ?, 'device', 'model', 'fingerprint', '[]', 1, 1, -1, '', 0, 2, 100, 'finished', NULL, 1, 1, 2, 0)",
                arrayOf<Any?>(scope, jobId),
            )
            before.execSQL(
                "INSERT INTO history_memory_candidate " +
                    "(jobId, `key`, category, subject, fact, evidence, conversationId, messageId, sourceTime, status) " +
                    "VALUES (?, 'k1', 'preferences', '阅读', '我喜欢晚上看小说', '我喜欢晚上看小说', " +
                    "'00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-0000000000c3', " +
                    "'2026-01-01T00:00:00Z', 'discarded')",
                arrayOf<Any?>(jobId),
            )
        } finally {
            before.close()
        }

        val after = helper.runMigrationsAndValidate(name, 38, true, Migration_37_38)
        try {
            after.query(
                "SELECT scope, fact, evidence, status FROM history_memory_candidate WHERE jobId = '$jobId'"
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals(scope, it.getString(0))
                assertEquals("我喜欢晚上看小说", it.getString(1))
                assertEquals("我喜欢晚上看小说", it.getString(2))
                assertEquals("discarded", it.getString(3))
            }
        } finally {
            after.close()
        }
    }
}
