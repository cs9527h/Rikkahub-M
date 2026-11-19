package me.rerere.rikkahub.mate

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Private on-device Mate journal. No schema coupling with upstream RikkaHub or old Mate. */
internal data class MateMessage(
    val id: String,
    val requestId: String,
    val role: String,
    val body: String,
    val state: String,
    val createdAt: Long
)

internal class MateLocalHistory(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "mate_liyu_v1.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE messages (" +
                "id TEXT PRIMARY KEY, instance TEXT NOT NULL, request_id TEXT NOT NULL, " +
                "role TEXT NOT NULL CHECK (role IN ('me','bot')), " +
                "body TEXT NOT NULL, state TEXT NOT NULL, created_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_mate_messages_instance_time ON messages(instance,created_at DESC)")
        db.execSQL("CREATE INDEX idx_mate_messages_request ON messages(instance,request_id)")
        db.execSQL(
            "CREATE TABLE draft (instance TEXT PRIMARY KEY, body TEXT NOT NULL DEFAULT '')"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Migration required for Mate history; refusing destructive reset")
    }

    suspend fun recent(instance: String, limit: Int = 200): List<MateMessage> = withContext(Dispatchers.IO) {
        require(instance.matches(Regex("[0-9a-f]{32}")))
        val result = ArrayList<MateMessage>()
        readableDatabase.rawQuery(
            "SELECT id,request_id,role,body,state,created_at FROM messages " +
                "WHERE instance=? ORDER BY created_at DESC LIMIT ?",
            arrayOf(instance, limit.coerceIn(1, 500).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += MateMessage(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getString(4), cursor.getLong(5)
                )
            }
        }
        result.asReversed()
    }

    suspend fun olderThan(instance: String, before: Long, limit: Int = 100): List<MateMessage> =
        withContext(Dispatchers.IO) {
            require(instance.matches(Regex("[0-9a-f]{32}")) && before > 0)
            val result = ArrayList<MateMessage>()
            readableDatabase.rawQuery(
                "SELECT id,request_id,role,body,state,created_at FROM messages " +
                    "WHERE instance=? AND created_at<? ORDER BY created_at DESC LIMIT ?",
                arrayOf(instance, before.toString(), limit.coerceIn(1, 200).toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result += MateMessage(
                        cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getString(4), cursor.getLong(5)
                    )
                }
            }
            result.asReversed()
        }

    suspend fun count(instance: String): Int = withContext(Dispatchers.IO) {
        require(instance.matches(Regex("[0-9a-f]{32}")))
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE instance=?", arrayOf(instance)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    suspend fun deletePairLocally(instance: String, id: String): Unit = withContext(Dispatchers.IO) {
        require(instance.matches(Regex("[0-9a-f]{32}")))
        require(id.matches(Regex("[A-Za-z0-9_-]{8,80}")))
        writableDatabase.execSQL(
            "DELETE FROM messages WHERE instance=? AND request_id=? AND state NOT IN ('running','uncertain')",
            arrayOf(instance, id)
        )
    }

    suspend fun savePair(instance: String, id: String, prompt: String): Unit = withContext(Dispatchers.IO) {
        require(instance.matches(Regex("[0-9a-f]{32}")))
        require(id.matches(Regex("[A-Za-z0-9_-]{8,80}")))
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT INTO messages(id,instance,request_id,role,body,state,created_at) VALUES(?,?,?,?,?,?,?)",
                arrayOf<Any?>(id, instance, id, "me", prompt, "sent", now)
            )
            db.execSQL(
                "INSERT INTO messages(id,instance,request_id,role,body,state,created_at) VALUES(?,?,?,?,?,?,?)",
                arrayOf<Any?>(id + "_reply", instance, id, "bot", "", "running", now + 1L)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun updateReply(instance: String, id: String, body: String, state: String): Unit =
        withContext(Dispatchers.IO) {
            require(instance.matches(Regex("[0-9a-f]{32}")))
            require(body.length <= 200_000)
            writableDatabase.execSQL(
                "UPDATE messages SET body=?,state=? WHERE instance=? AND id=? AND role='bot'",
                arrayOf(body, state, instance, id + "_reply")
            )
        }

    suspend fun unresolved(instance: String): List<String> = withContext(Dispatchers.IO) {
        val ids = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT request_id FROM messages WHERE instance=? AND role='bot' AND state IN ('running','uncertain') ORDER BY created_at LIMIT 200",
            arrayOf(instance)
        ).use { cursor -> while (cursor.moveToNext()) ids += cursor.getString(0) }
        ids
    }

    suspend fun saveDraft(instance: String, draft: String): Unit = withContext(Dispatchers.IO) {
        require(instance.matches(Regex("[0-9a-f]{32}")))
        require(draft.length <= 12_000)
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO draft(instance,body) VALUES(?,?)", arrayOf(instance, draft)
        )
    }

    suspend fun draft(instance: String): String = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT body FROM draft WHERE instance=?", arrayOf(instance))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "" }
    }

    suspend fun clearMessages(instance: String): Unit = withContext(Dispatchers.IO) {
        require(instance.matches(Regex("[0-9a-f]{32}")))
        writableDatabase.execSQL("DELETE FROM messages WHERE instance=?", arrayOf(instance))
    }
}
