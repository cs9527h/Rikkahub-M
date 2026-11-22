package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS history_memory_job (
                scope TEXT NOT NULL PRIMARY KEY, id TEXT NOT NULL, deviceConsent TEXT NOT NULL,
                modelId TEXT NOT NULL, providerFingerprint TEXT NOT NULL, sources TEXT NOT NULL,
                sourceCount INTEGER NOT NULL, sourceIndex INTEGER NOT NULL, nodeIndex INTEGER NOT NULL,
                nodeId TEXT NOT NULL, textOffset INTEGER NOT NULL, batches INTEGER NOT NULL,
                estimatedTokens INTEGER NOT NULL, status TEXT NOT NULL, failure TEXT, generation INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS history_memory_candidate (
                jobId TEXT NOT NULL, `key` TEXT NOT NULL, category TEXT NOT NULL, subject TEXT NOT NULL,
                fact TEXT NOT NULL, evidence TEXT NOT NULL, conversationId TEXT NOT NULL,
                messageId TEXT NOT NULL, sourceTime TEXT NOT NULL, status TEXT NOT NULL,
                PRIMARY KEY(jobId, `key`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_history_memory_candidate_jobId_status ON history_memory_candidate(jobId, status)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS history_memory_batch (
                jobId TEXT NOT NULL, digest TEXT NOT NULL, PRIMARY KEY(jobId, digest)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS memory_recall (
                id TEXT NOT NULL PRIMARY KEY, scope TEXT NOT NULL, conversationId TEXT NOT NULL,
                recordedAt INTEGER NOT NULL, matches TEXT NOT NULL, injected TEXT NOT NULL,
                toolReads TEXT NOT NULL, tokens INTEGER NOT NULL, elapsedMillis INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_recall_scope_recordedAt ON memory_recall(scope, recordedAt)")
    }
}
