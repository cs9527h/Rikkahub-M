package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "memory_file", primaryKeys = ["assistant_id", "path"])
data class MemoryFileEntity(
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("path")
    val path: String,
    @ColumnInfo("content")
    val content: String = "",
    @ColumnInfo("updated_at")
    val updatedAt: Long = 0,
    /** 0 表示仍在记忆库中；非 0 是移入“最近删除”的时间。检索与提示词只读 0。 */
    @ColumnInfo("deleted_at", defaultValue = "0")
    val deletedAt: Long = 0,
)
