package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 长期记忆 0.8.6：失败阶段化。
 *
 * 只给两张已有的表加列，不重建、不删表、不改已有列：
 * 升级后旧的整理任务、任务历史、候选、已保存的记忆和聊天数据都保持原样，
 * 旧历史行的 `errorStage/errorCode` 为 NULL，界面会退回原来的单行失败原因。
 */
val Migration_40_41 = object : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // history_memory_job：当前这一轮任务的失败信息与批次尝试计数
        db.execSQL("ALTER TABLE `history_memory_job` ADD COLUMN `failureStage` TEXT")
        db.execSQL("ALTER TABLE `history_memory_job` ADD COLUMN `failureCode` TEXT")
        db.execSQL("ALTER TABLE `history_memory_job` ADD COLUMN `failureDetail` TEXT")
        db.execSQL("ALTER TABLE `history_memory_job` ADD COLUMN `batchAttempts` INTEGER NOT NULL DEFAULT 0")

        // history_memory_task_log：历史行也要能解释“在哪一步、为什么、跳过了多少”
        db.execSQL("ALTER TABLE `history_memory_task_log` ADD COLUMN `errorStage` TEXT")
        db.execSQL("ALTER TABLE `history_memory_task_log` ADD COLUMN `errorCode` TEXT")
        db.execSQL("ALTER TABLE `history_memory_task_log` ADD COLUMN `errorDetail` TEXT")
        db.execSQL("ALTER TABLE `history_memory_task_log` ADD COLUMN `skippedBatches` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `history_memory_task_log` ADD COLUMN `skippedSources` INTEGER NOT NULL DEFAULT 0")
    }
}
