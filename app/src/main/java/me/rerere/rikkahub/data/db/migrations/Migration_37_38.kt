package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 长期记忆 0.8.1：把 scope 冗余到候选上，避免任务被替换后旧候选失去归属。
 *
 * 只加列、加索引并回填，不删除任何候选：误跳过、未审核的候选都还能找回。
 */
val Migration_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE history_memory_candidate ADD COLUMN scope TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            """
            UPDATE history_memory_candidate
            SET scope = COALESCE((SELECT scope FROM history_memory_job WHERE id = history_memory_candidate.jobId), '')
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_history_memory_candidate_scope_status ON history_memory_candidate(scope, status)")
    }
}
