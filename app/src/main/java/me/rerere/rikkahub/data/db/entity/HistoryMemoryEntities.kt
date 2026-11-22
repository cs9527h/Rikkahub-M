package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "history_memory_job")
data class HistoryMemoryJob(
    @PrimaryKey val scope: String,
    val id: String,
    val deviceConsent: String,
    val modelId: String,
    val providerFingerprint: String,
    val sources: String,
    val sourceCount: Int,
    val sourceIndex: Int = 0,
    val nodeIndex: Int = -1,
    val nodeId: String = "",
    val textOffset: Int = 0,
    val batches: Int = 0,
    val estimatedTokens: Long = 0,
    val status: String = "running",
    val failure: String? = null,
    val generation: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val finishedAt: Long = 0,
    /** 用户点“继续”时已经用掉的 Token，单次预算从这里重新计算。 */
    @ColumnInfo(defaultValue = "0")
    val budgetAckTokens: Long = 0,
)

@Entity(
    tableName = "history_memory_candidate",
    primaryKeys = ["jobId", "key"],
    indices = [Index("jobId", "status"), Index("scope", "status")],
)
data class HistoryMemoryCandidate(
    val jobId: String,
    val key: String,
    /**
     * 候选属于哪个记忆库。任务行会被新任务替换，只有把 scope 冗余到候选上，
     * 上一轮没审完的候选才不会跟着任务一起“消失”。
     */
    @ColumnInfo(defaultValue = "''")
    val scope: String = "",
    val category: String,
    val subject: String,
    val fact: String,
    val evidence: String,
    val conversationId: String,
    val messageId: String,
    val sourceTime: String,
    val status: String = "pending",
)

@Entity(tableName = "history_memory_batch", primaryKeys = ["jobId", "digest"])
data class HistoryMemoryBatch(val jobId: String, val digest: String)

/** 每个记忆库最近一次整理的时间与结果，用于增量整理和首页概况。 */
@Entity(tableName = "memory_scope_state")
data class MemoryScopeState(
    @PrimaryKey val scope: String,
    val lastOrganizedAt: Long = 0,
    /** 最近一次整理的简短结果说明，直接展示在记忆首页。 */
    val lastResult: String = "",
    /** 当天已用的估算 Token，用于每日预算（跨天自动清零）。 */
    @ColumnInfo(defaultValue = "0")
    val dailyTokens: Long = 0,
    /** 上面这个数字属于哪一天，ISO 日期。 */
    @ColumnInfo(defaultValue = "''")
    val dailyDay: String = "",
)

/** 跨任务的已处理批次指纹：同一段聊天换一个任务也不会重复付费。 */
@Entity(tableName = "history_memory_seen")
data class HistoryMemorySeen(
    @PrimaryKey val digest: String,
    val createdAt: Long,
)

/** 批量保存的可撤销记录：撤销时恢复文件内容并把候选放回待审核。 */
@Entity(tableName = "memory_bulk_undo", indices = [Index("scope", "createdAt")])
data class MemoryBulkUndo(
    @PrimaryKey val id: String,
    val scope: String,
    val jobId: String,
    val createdAt: Long,
    val summary: String,
    val payload: String,
)

@Entity(tableName = "memory_recall", indices = [Index("scope", "recordedAt")])
data class MemoryRecallRecord(
    @PrimaryKey val id: String,
    val scope: String,
    val conversationId: String,
    val recordedAt: Long,
    val matches: String,
    val injected: String,
    val toolReads: String,
    val tokens: Int,
    val elapsedMillis: Long,
)
