package me.rerere.rikkahub.data.db.migrations

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite transactions, minimal source tables; not a claim of full historical Room-schema validation. */
@RunWith(AndroidJUnit4::class)
class MessageMigrationRetentionTest {
    @Test
    fun migration11FailureRollsBackEarlierRowsAndKeepsOriginalNodesAndVersion() {
        for (invalid in listOf("not-json", "{}", "[null]", "[{\"messages\":{}}]")) {
            database(11) { db ->
                db.execSQL("CREATE TABLE ConversationEntity (id TEXT NOT NULL PRIMARY KEY, nodes TEXT NOT NULL)")
            }.use { helper ->
                val db = helper.writableDatabase
                val valid = """[{"messages":[{"role":"ASSISTANT","parts":[{"type":"text","text":"keep me"}]}],"selectIndex":0}]"""
                db.execSQL("INSERT INTO ConversationEntity VALUES (?, ?)", arrayOf("a-valid", valid))
                db.execSQL("INSERT INTO ConversationEntity VALUES (?, ?)", arrayOf("z-invalid", invalid))
                assertThrows(Exception::class.java) { Migration_11_12.migrate(db) }
                assertEquals(11, db.version)
                assertEquals(mapOf("a-valid" to valid, "z-invalid" to invalid), rows(db, "ConversationEntity", "nodes"))
                db.query("SELECT count(*) FROM sqlite_master WHERE name = 'message_node'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0)) // CREATE and earlier INSERT/UPDATE rolled back too.
                }
            }
        }
    }

    @Test
    fun migration15CannotDeleteUnknownNodeWhenOtherNodesNeedMerging() {
        database(15, ::createMessageTable).use { helper ->
            val db = helper.writableDatabase
            val assistant = JsonInstant.encodeToString(listOf(UIMessage.assistant("assistant")))
            val tool = JsonInstant.encodeToString(listOf(UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.Text("result")))))
            val invalid = "not-json"
            insertNode(db, "assistant", 0, assistant)
            insertNode(db, "tool", 1, tool)
            insertNode(db, "invalid", 2, invalid)
            assertThrows(Exception::class.java) { Migration_15_16.migrate(db) }
            assertEquals(15, db.version)
            assertEquals(mapOf("assistant" to assistant, "tool" to tool, "invalid" to invalid), rows(db, "message_node", "messages"))
        }
    }

    @Test
    fun migration15StillMergesValidLegacyToolNodes() {
        database(15, ::createMessageTable).use { helper ->
            val db = helper.writableDatabase
            insertNode(db, "assistant", 0, JsonInstant.encodeToString(listOf(UIMessage.assistant("assistant"))))
            insertNode(db, "tool", 1, JsonInstant.encodeToString(listOf(UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.Text("result"))))))
            Migration_15_16.migrate(db)
            assertEquals(setOf("assistant"), rows(db, "message_node", "messages").keys)
        }
    }

    private fun createMessageTable(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE message_node (
                id TEXT NOT NULL PRIMARY KEY, conversation_id TEXT NOT NULL,
                node_index INTEGER NOT NULL, messages TEXT NOT NULL, select_index INTEGER NOT NULL
            )
        """.trimIndent())
    }

    private fun insertNode(db: SupportSQLiteDatabase, id: String, index: Int, messages: String) {
        db.execSQL("INSERT INTO message_node VALUES (?, ?, ?, ?, ?)", arrayOf<Any>(id, "conversation", index, messages, 0))
    }

    private fun rows(db: SupportSQLiteDatabase, table: String, column: String): Map<String, String> =
        db.query("SELECT id, $column FROM $table").use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
        }

    private fun database(version: Int, create: (SupportSQLiteDatabase) -> Unit): SupportSQLiteOpenHelper =
        RequerySQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(
            InstrumentationRegistry.getInstrumentation().targetContext
        ).name(null).callback(object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) = create(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                error("Tests invoke production migrations explicitly")
            }
        }).build())
}
