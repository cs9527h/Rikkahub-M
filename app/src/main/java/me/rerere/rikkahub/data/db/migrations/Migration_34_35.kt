package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 记忆文件支持“最近删除”：新增 deleted_at（0 表示仍在用）。
 *
 * 只加一列并给旧行填 0，不改内容；旧版本写回时需要忽略这一列（Room 允许读旧 schema 的额外列）。
 */
val Migration_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `memory_file` ADD COLUMN `deleted_at` INTEGER NOT NULL DEFAULT 0")
    }
}
