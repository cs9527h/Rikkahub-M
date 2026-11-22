package me.rerere.rikkahub.data.ai.memory

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.migrations.Migration_35_36
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryMemoryMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test fun preservesImportedThreadsAndExistingMemory() {
        val name = "history-memory-v35"
        helper.createDatabase(name, 35).use { db ->
            db.execSQL("INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at) VALUES ('demo', 'Synthetic', '[]', 1000, 1000)")
            db.execSQL("INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES ('node', 'demo', 0, '[]', 0)")
            db.execSQL("INSERT INTO memory_file (assistant_id, path, content, updated_at, deleted_at) VALUES ('demo', '/profile.md', 'Synthetic old memory', 42, 11)")
        }
        helper.runMigrationsAndValidate(name, 36, true, Migration_35_36).use { db ->
            db.query("SELECT title FROM ConversationEntity WHERE id = 'demo'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Synthetic", it.getString(0))
            }
            db.query("SELECT messages FROM message_node WHERE id = 'node'").use {
                assertTrue(it.moveToFirst())
                assertEquals("[]", it.getString(0))
            }
            db.query("SELECT content, deleted_at FROM memory_file WHERE assistant_id = 'demo'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Synthetic old memory", it.getString(0))
                assertEquals(11L, it.getLong(1))
            }
            db.query("SELECT COUNT(*) FROM history_memory_job").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        }
    }
}
