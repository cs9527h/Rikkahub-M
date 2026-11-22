package me.rerere.rikkahub.data.db.entity

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
)

@Entity(tableName = "history_memory_candidate", primaryKeys = ["jobId", "key"], indices = [Index("jobId", "status")])
data class HistoryMemoryCandidate(
    val jobId: String,
    val key: String,
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
