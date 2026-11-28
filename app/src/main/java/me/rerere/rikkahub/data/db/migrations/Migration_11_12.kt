package me.rerere.rikkahub.data.db.migrations

import android.database.sqlite.SQLiteBlobTooBigException
import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.util.formatExceptionForLog
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

private const val TAG = "Migration_11_12"

val Migration_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 11 to 12 (extracting message nodes to separate table)")
        DatabaseMigrationTracker.onMigrationStart(11, 12)
        db.beginTransaction()
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS message_node (
                    id TEXT NOT NULL PRIMARY KEY,
                    conversation_id TEXT NOT NULL,
                    node_index INTEGER NOT NULL,
                    messages TEXT NOT NULL,
                    select_index INTEGER NOT NULL,
                    FOREIGN KEY (conversation_id) REFERENCES ConversationEntity(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_message_node_conversation_id ON message_node(conversation_id)")

            var migratedCount = 0
            var nodeCount = 0
            var skippedCount = 0
            db.query("SELECT id FROM conversationentity").use { cursor ->
                while (cursor.moveToNext()) {
                    val conversationId = cursor.getString(0)
                    try {
                        db.query(
                            "SELECT nodes FROM conversationentity WHERE id = ?",
                            arrayOf(conversationId)
                        ).use { nodeCursor ->
                            if (!nodeCursor.moveToFirst()) return@use
                            val nodesJson = nodeCursor.getString(0)
                            // Validate all source nodes before inserting any or clearing the source.
                            // A malformed row must abort the transaction, never become empty history.
                            val nodesArray = legacyNodesForMigration(nodesJson)
                            nodesArray.forEachIndexed { index, nodeElement ->
                                val nodeObject = nodeElement as JsonObject
                                val messagesElement = nodeObject.getValue("messages") as JsonArray
                                val migratedMessages = migrateMessagesElement(messagesElement)
                                val messagesJson = JsonInstant.encodeToString(migratedMessages)
                                val selectIndex = runCatching {
                                    nodeObject["selectIndex"]?.jsonPrimitive?.int ?: 0
                                }.getOrDefault(0)
                                val nodeId = Uuid.random().toString()
                                db.execSQL(
                                    "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES (?, ?, ?, ?, ?)",
                                    arrayOf(nodeId, conversationId, index, messagesJson, selectIndex)
                                )
                                nodeCount++
                            }
                            db.execSQL(
                                "UPDATE conversationentity SET nodes = '[]' WHERE id = ?",
                                arrayOf(conversationId)
                            )
                            migratedCount++
                        }
                    } catch (e: SQLiteBlobTooBigException) {
                        // Keep the historical large-blob fallback: source nodes remain untouched.
                        skippedCount++
                        Log.w(TAG, formatExceptionForLog("migration11.readLargeNodes", e))
                    }
                }
            }

            db.setTransactionSuccessful()
            Log.i(
                TAG,
                "migrate: migrate from 11 to 12 success ($migratedCount conversations, $nodeCount nodes, $skippedCount skipped)"
            )
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
