package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 长期记忆 0.8.2：召回诊断字段。
 *
 * 只加列、不删除历史记录；旧记录的新字段按默认值解释为“未采集”。
 */
val Migration_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE memory_recall ADD COLUMN query TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE memory_recall ADD COLUMN considered INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE memory_recall ADD COLUMN filtered INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE memory_recall ADD COLUMN broad INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE memory_recall ADD COLUMN filterReasons TEXT NOT NULL DEFAULT '{}'")
    }
}
