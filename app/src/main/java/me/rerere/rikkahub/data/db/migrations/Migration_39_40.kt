package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 长期记忆 0.8.5：新增任务历史表。
 *
 * 只新建表和索引，不碰已有的记忆、候选、聊天数据；升级后旧任务的进度照常继续，
 * 只是升级之前已经跑过的轮次没有历史记录（当时没采集，补不出来）。
 */
val Migration_39_40 = object : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `history_memory_task_log` (" +
                "`id` TEXT NOT NULL, " +
                "`scope` TEXT NOT NULL, " +
                "`jobGeneration` INTEGER NOT NULL DEFAULT 0, " +
                "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                "`finishedAt` INTEGER NOT NULL DEFAULT 0, " +
                "`status` TEXT NOT NULL DEFAULT 'running', " +
                "`sourceCount` INTEGER NOT NULL DEFAULT 0, " +
                "`processedCount` INTEGER NOT NULL DEFAULT 0, " +
                "`candidateCount` INTEGER NOT NULL DEFAULT 0, " +
                "`savedCount` INTEGER NOT NULL DEFAULT 0, " +
                "`skippedCount` INTEGER NOT NULL DEFAULT 0, " +
                "`inputTokens` INTEGER NOT NULL DEFAULT 0, " +
                "`outputTokens` INTEGER NOT NULL DEFAULT 0, " +
                "`rangeLabel` TEXT NOT NULL DEFAULT '', " +
                "`errorMessage` TEXT, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_history_memory_task_log_scope_createdAt` " +
                "ON `history_memory_task_log` (`scope`, `createdAt`)"
        )
    }
}
