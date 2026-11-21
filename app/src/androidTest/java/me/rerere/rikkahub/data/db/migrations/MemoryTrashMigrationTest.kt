package me.rerere.rikkahub.data.db.migrations

import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Full Room 34→35 validation and synthetic long-history QUERY timing, not a UI/frame benchmark. */
@RunWith(AndroidJUnit4::class)
class MemoryTrashMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test fun validatesCompleteSchemaAndPreservesLongHistoryPromptsAndMemoryNamespaces() {
        val name = "memory-trash-full-migration-test"
        val conversation = "00000000-0000-0000-0000-000000000001"
        val assistant = "00000000-0000-0000-0000-000000000002"
        val payloads = (0 until 1000).map { index ->
            JsonInstant.encodeToString(listOf(
                UIMessage.user("Synthetic user message $index"),
                UIMessage.assistant("Synthetic reply $index"),
            ))
        }
        val query = "SELECT messages FROM message_node WHERE conversation_id = '$conversation' " +
            "ORDER BY node_index ASC LIMIT 50 OFFSET 950"
        val before = helper.createDatabase(name, 34)
        val beforeTimings: List<Long>
        try {
            before.beginTransaction()
            try {
                before.execSQL(
                    "INSERT INTO ConversationEntity " +
                        "(id, assistant_id, title, nodes, create_at, update_at, custom_system_prompt) " +
                        "VALUES (?, ?, ?, '[]', 1000, 1000, ?)",
                    arrayOf<Any?>(conversation, assistant, "Synthetic long history", "Synthetic exact prompt"),
                )
                payloads.forEachIndexed { index, messages ->
                    before.execSQL(
                        "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                            "VALUES (?, ?, ?, ?, 0)",
                        arrayOf<Any?>(
                            "00000000-0000-0000-0001-${index.toString().padStart(12, '0')}",
                            conversation,
                            index,
                            messages,
                        ),
                    )
                }
                for (namespace in listOf(assistant, "other-synthetic-assistant")) {
                    before.execSQL(
                        "INSERT INTO memory_file (assistant_id, path, content, updated_at) VALUES (?, ?, ?, ?)",
                        arrayOf<Any?>(namespace, "/profile.md", "Synthetic memory for $namespace", 1234),
                    )
                }
                before.setTransactionSuccessful()
            } finally {
                before.endTransaction()
            }
            beforeTimings = queryTimings(before, query)
        } finally {
            before.close()
        }
        val after = helper.runMigrationsAndValidate(name, 35, true, Migration_34_35)
        try {
            after.query("SELECT custom_system_prompt, title FROM ConversationEntity WHERE id = '$conversation'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Synthetic exact prompt", it.getString(0))
                assertEquals("Synthetic long history", it.getString(1))
            }
            after.query("SELECT node_index, messages FROM message_node ORDER BY node_index").use { cursor ->
                var count = 0
                while (cursor.moveToNext()) {
                    assertEquals(count, cursor.getInt(0))
                    assertEquals(payloads[count], cursor.getString(1))
                    count++
                }
                assertEquals(1000, count)
            }
            after.query("SELECT assistant_id, content, updated_at, deleted_at FROM memory_file").use { cursor ->
                var count = 0
                while (cursor.moveToNext()) {
                    assertEquals("Synthetic memory for ${cursor.getString(0)}", cursor.getString(1))
                    assertEquals(1234L, cursor.getLong(2))
                    assertEquals(0L, cursor.getLong(3))
                    count++
                }
                assertEquals(2, count)
            }
            after.query("EXPLAIN QUERY PLAN $query").use { cursor ->
                val plan = buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString()
                assertTrue(plan, plan.contains("index_message_node_conversation_id_node_index"))
                assertFalse(plan, plan.contains("TEMP B-TREE"))
            }
            val afterTimings = queryTimings(after, query)
            Log.i(
                "RkmLongChatPerf",
                "LONG_CHAT_DB_PERF scope=query_only rows=1000 messages=2000 window=50 samples=100 " +
                    "api=${Build.VERSION.SDK_INT} model=${Build.MODEL} abis=${Build.SUPPORTED_ABIS.joinToString()} " +
                    "before_median_us=${beforeTimings[50] / 1000} before_p95_us=${beforeTimings[94] / 1000} " +
                    "after_median_us=${afterTimings[50] / 1000} after_p95_us=${afterTimings[94] / 1000} " +
                    "process_pss_kb=${Debug.getPss()}"
            )
        } finally {
            after.close()
        }
    }

    private fun queryTimings(database: SupportSQLiteDatabase, query: String): List<Long> {
        repeat(10) { readWindow(database, query) }
        return List(100) {
            val start = SystemClock.elapsedRealtimeNanos()
            readWindow(database, query)
            SystemClock.elapsedRealtimeNanos() - start
        }.sorted()
    }

    private fun readWindow(database: SupportSQLiteDatabase, query: String) {
        database.query(query).use { cursor ->
            var count = 0
            while (cursor.moveToNext()) {
                assertTrue(cursor.getString(0).isNotEmpty())
                count++
            }
            assertEquals(50, count)
        }
    }
}
