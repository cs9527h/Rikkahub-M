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
import me.rerere.rikkahub.data.db.entity.HistoryMemorySeen
import me.rerere.rikkahub.data.db.entity.MemoryBulkUndo
import me.rerere.rikkahub.data.db.entity.MemoryRecallRecord
import me.rerere.rikkahub.data.db.entity.MemoryScopeState

@Dao
interface HistoryMemoryDAO {
    @Query("SELECT * FROM history_memory_job WHERE scope = :scope")
    fun observe(scope: String): Flow<HistoryMemoryJob?>

    @Query("SELECT * FROM history_memory_job WHERE scope = :scope")
    suspend fun job(scope: String): HistoryMemoryJob?

    @Query("SELECT * FROM history_memory_job WHERE status = 'running'")
    suspend fun running(): List<HistoryMemoryJob>

    @Upsert
    suspend fun put(job: HistoryMemoryJob)

    @Query("SELECT * FROM history_memory_candidate WHERE jobId = :id AND status = 'pending' ORDER BY key LIMIT 50")
    fun pending(id: String): Flow<List<HistoryMemoryCandidate>>

    /** 审核页需要一次看到全部待处理候选，不能像旧界面那样只给前 50 条。 */
    @Query("SELECT * FROM history_memory_candidate WHERE jobId = :id AND status = 'pending' ORDER BY category, `key`")
    fun pendingAll(id: String): Flow<List<HistoryMemoryCandidate>>

    @Query("SELECT * FROM history_memory_candidate WHERE jobId = :id ORDER BY category, `key`")
    fun candidatesOf(id: String): Flow<List<HistoryMemoryCandidate>>

    @Query("SELECT * FROM history_memory_candidate WHERE jobId = :id AND status = 'pending' ORDER BY category, `key` LIMIT :limit")
    suspend fun pendingSnapshot(id: String, limit: Int): List<HistoryMemoryCandidate>

    /** 按记忆库看某一种状态的候选：任务被替换后仍然能看到上一轮没处理完的内容。 */
    @Query(
        "SELECT * FROM history_memory_candidate WHERE scope = :scope AND status = :status " +
            "ORDER BY category, `key` LIMIT :limit"
    )
    fun candidatesOfStatus(scope: String, status: String, limit: Int = 2_000): Flow<List<HistoryMemoryCandidate>>

    @Query(
        "SELECT * FROM history_memory_candidate WHERE scope = :scope AND status = :status ORDER BY category, `key`"
    )
    suspend fun snapshotOfStatus(scope: String, status: String, limit: Int = 2_000): List<HistoryMemoryCandidate>

    @Query("SELECT COUNT(*) FROM history_memory_candidate WHERE scope = :scope AND status = :status")
    fun countOfStatus(scope: String, status: String): Flow<Int>

    @Query("UPDATE history_memory_candidate SET status = :status WHERE scope = :scope AND `key` IN (:keys)")
    suspend fun setStatusesOfScope(scope: String, keys: List<String>, status: String)

    @Query("SELECT COUNT(*) FROM history_memory_candidate WHERE jobId = :id AND status = :status")
    fun count(id: String, status: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM history_memory_candidate WHERE jobId = :id AND status = :status")
    suspend fun countNow(id: String, status: String): Int

    @Query("SELECT * FROM history_memory_candidate WHERE jobId = :id AND `key` = :key AND status = 'pending'")
    suspend fun candidate(id: String, key: String): HistoryMemoryCandidate?

    @Query("UPDATE history_memory_candidate SET status = :status, evidence = '', fact = '' WHERE jobId = :id AND `key` = :key")
    suspend fun resolve(id: String, key: String, status: String)

    /**
     * 只改状态，保留正文和证据：撤销批量操作、回看已处理的候选都要靠这些内容。
     * 旧实现会把 fact/evidence 清空，导致保存后无法复原，这里刻意改掉。
     */
    @Query("UPDATE history_memory_candidate SET status = :status WHERE jobId = :id AND `key` = :key")
    suspend fun setStatus(id: String, key: String, status: String)

    @Query("UPDATE history_memory_candidate SET status = :status WHERE jobId = :id AND `key` IN (:keys)")
    suspend fun setStatuses(id: String, keys: List<String>, status: String)

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

    /** 跨任务批次指纹：内容相同的一批聊天永远不会第二次计费。 */
    @Query("SELECT EXISTS(SELECT 1 FROM history_memory_seen WHERE digest = :digest)")
    suspend fun seen(digest: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markSeen(seen: HistoryMemorySeen)

    @Query("SELECT * FROM memory_scope_state WHERE scope = :scope")
    fun observeState(scope: String): Flow<MemoryScopeState?>

    @Query("SELECT * FROM memory_scope_state WHERE scope = :scope")
    suspend fun state(scope: String): MemoryScopeState?

    @Upsert
    suspend fun putState(state: MemoryScopeState)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addUndo(undo: MemoryBulkUndo)

    @Query("SELECT * FROM memory_bulk_undo WHERE scope = :scope ORDER BY createdAt DESC LIMIT 1")
    suspend fun lastUndo(scope: String): MemoryBulkUndo?

    @Query("DELETE FROM memory_bulk_undo WHERE id = :id")
    suspend fun clearUndo(id: String)

    @Query(
        "DELETE FROM memory_bulk_undo WHERE scope = :scope AND id NOT IN " +
            "(SELECT id FROM memory_bulk_undo WHERE scope = :scope ORDER BY createdAt DESC LIMIT 10)"
    )
    suspend fun trimUndo(scope: String)

    @Upsert
    suspend fun recordRecall(record: MemoryRecallRecord)

    @Query("SELECT * FROM memory_recall WHERE scope = :scope ORDER BY recordedAt DESC LIMIT 50")
    fun recalls(scope: String): Flow<List<MemoryRecallRecord>>

    @Query("DELETE FROM memory_recall WHERE scope = :scope AND id NOT IN (SELECT id FROM memory_recall WHERE scope = :scope ORDER BY recordedAt DESC LIMIT 50)")
    suspend fun trimRecalls(scope: String)

    @Query("DELETE FROM memory_recall WHERE scope = :scope")
    suspend fun clearRecalls(scope: String)
}
