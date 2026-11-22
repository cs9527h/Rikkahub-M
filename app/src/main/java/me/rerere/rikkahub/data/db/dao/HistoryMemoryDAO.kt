package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.HistoryMemoryBatch
import me.rerere.rikkahub.data.db.entity.HistoryMemoryCandidate
import me.rerere.rikkahub.data.db.entity.HistoryMemoryJob
import me.rerere.rikkahub.data.db.entity.MemoryRecallRecord

@Dao
interface HistoryMemoryDAO {
    @Query("SELECT * FROM history_memory_job WHERE scope = :scope")
    fun observe(scope: String): Flow<HistoryMemoryJob?>

    @Query("SELECT * FROM history_memory_job WHERE scope = :scope")
    suspend fun job(scope: String): HistoryMemoryJob?

    @Upsert
    suspend fun put(job: HistoryMemoryJob)

    @Query("SELECT * FROM history_memory_candidate WHERE jobId = :id AND status = 'pending' ORDER BY key LIMIT 50")
    fun pending(id: String): Flow<List<HistoryMemoryCandidate>>

    @Query("SELECT COUNT(*) FROM history_memory_candidate WHERE jobId = :id AND status = :status")
    fun count(id: String, status: String): Flow<Int>

    @Query("SELECT * FROM history_memory_candidate WHERE jobId = :id AND `key` = :key AND status = 'pending'")
    suspend fun candidate(id: String, key: String): HistoryMemoryCandidate?

    @Query("UPDATE history_memory_candidate SET status = :status, evidence = '' WHERE jobId = :id AND `key` = :key")
    suspend fun resolve(id: String, key: String, status: String)

    @Query("DELETE FROM history_memory_candidate WHERE jobId = :id")
    suspend fun clearCandidates(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addCandidates(candidates: List<HistoryMemoryCandidate>)

    @Query("SELECT EXISTS(SELECT 1 FROM history_memory_batch WHERE jobId = :id AND digest = :digest)")
    suspend fun processed(id: String, digest: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markProcessed(batch: HistoryMemoryBatch)

    @Query("DELETE FROM history_memory_batch WHERE jobId = :id")
    suspend fun clearBatches(id: String)

    @Upsert
    suspend fun recordRecall(record: MemoryRecallRecord)

    @Query("SELECT * FROM memory_recall WHERE scope = :scope ORDER BY recordedAt DESC LIMIT 50")
    fun recalls(scope: String): Flow<List<MemoryRecallRecord>>

    @Query("DELETE FROM memory_recall WHERE scope = :scope AND id NOT IN (SELECT id FROM memory_recall WHERE scope = :scope ORDER BY recordedAt DESC LIMIT 50)")
    suspend fun trimRecalls(scope: String)

    @Query("DELETE FROM memory_recall WHERE scope = :scope")
    suspend fun clearRecalls(scope: String)
}
