package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 长期记忆 2.0：增量整理、跨任务去重和批量撤销。
 *
 * 只新增列和表，不删除任何已有数据：已保存记忆、待审核候选、任务游标都原样保留。
 */
val Migration_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE history_memory_job ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE history_memory_job ADD COLUMN finishedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE history_memory_job ADD COLUMN budgetAckTokens INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memory_scope_state (
                scope TEXT NOT NULL,
                lastOrganizedAt INTEGER NOT NULL,
                lastResult TEXT NOT NULL,
                dailyTokens INTEGER NOT NULL DEFAULT 0,
                dailyDay TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(scope)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS history_memory_seen (
                digest TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                PRIMARY KEY(digest)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memory_bulk_undo (
                id TEXT NOT NULL,
                scope TEXT NOT NULL,
                jobId TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                summary TEXT NOT NULL,
                payload TEXT NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_bulk_undo_scope_createdAt ON memory_bulk_undo(scope, createdAt)")
        // 已经完成过的整理任务意味着当时的全部历史都已处理过：把水位设成现在，
        // 升级后的第一次“整理”不会再把这些旧聊天送一遍、再收一次费。
        db.execSQL(
            """
            INSERT OR IGNORE INTO memory_scope_state(scope, lastOrganizedAt, lastResult)
            SELECT scope, CAST(strftime('%s','now') AS INTEGER) * 1000, ''
            FROM history_memory_job WHERE status = 'finished'
            """.trimIndent()
        )
    }
}
