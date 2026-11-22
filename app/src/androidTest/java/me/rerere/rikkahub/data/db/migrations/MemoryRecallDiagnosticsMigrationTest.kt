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

/** Room 38 → 39：召回诊断字段只增列，旧记录保留并得到安全的默认值。 */
@RunWith(AndroidJUnit4::class)
class MemoryRecallDiagnosticsMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun validatesSchemaAndKeepsOldRecallRows() {
        val name = "memory-recall-diagnostics-migration-test"
        val before = helper.createDatabase(name, 38)
        try {
            before.execSQL(
                "INSERT INTO memory_recall (id, scope, conversationId, recordedAt, matches, injected, toolReads, tokens, elapsedMillis) " +
                    "VALUES ('r1', 'scope-a', 'conv-a', 1000, '[]', '[]', '[]', 12, 34)",
            )
        } finally {
            before.close()
        }

        val after = helper.runMigrationsAndValidate(name, 39, true, Migration_38_39)
        try {
            after.query("SELECT matches, tokens, elapsedMillis, query, considered, filtered, broad, filterReasons FROM memory_recall WHERE id = 'r1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("[]", it.getString(0))
                assertEquals(12, it.getInt(1))
                assertEquals(34L, it.getLong(2))
                assertEquals("", it.getString(3))
                assertEquals(0, it.getInt(4))
                assertEquals(0, it.getInt(5))
                assertEquals(0, it.getInt(6))
                assertEquals("{}", it.getString(7))
            }
        } finally {
            after.close()
        }
    }
}
