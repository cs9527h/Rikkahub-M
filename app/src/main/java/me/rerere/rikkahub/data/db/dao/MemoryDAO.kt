package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity

@Dao
interface MemoryDAO {
    @Query("SELECT * FROM memory_file ORDER BY assistant_id, path")
    suspend fun getAllFiles(): List<MemoryFileEntity>

    /** Used only on a validated staged database during settings-memory restore. */
    @Query("DELETE FROM memory_file")
    suspend fun deleteAllFiles()

    @Query("SELECT * FROM memory_file WHERE assistant_id = :assistantId AND deleted_at = 0 ORDER BY path")
    fun getFilesOfAssistantFlow(assistantId: String): Flow<List<MemoryFileEntity>>

    @Query("SELECT * FROM memory_file WHERE assistant_id = :assistantId AND deleted_at = 0 ORDER BY path")
    suspend fun getFilesOfAssistant(assistantId: String): List<MemoryFileEntity>

    @Query(
        "SELECT * FROM memory_file WHERE assistant_id = :assistantId AND deleted_at != 0 ORDER BY deleted_at DESC, path"
    )
    fun getDeletedFilesOfAssistantFlow(assistantId: String): Flow<List<MemoryFileEntity>>

    @Query("SELECT * FROM memory_file WHERE assistant_id = :assistantId AND path = :path AND deleted_at = 0")
    suspend fun getFile(assistantId: String, path: String): MemoryFileEntity?

    @Query("SELECT * FROM memory_file WHERE assistant_id = :assistantId AND path = :path")
    suspend fun getFileIncludingDeleted(assistantId: String, path: String): MemoryFileEntity?

    @Upsert
    suspend fun upsertFile(file: MemoryFileEntity)

    @Upsert
    suspend fun upsertFiles(files: List<MemoryFileEntity>)

    /** Keep the row so the user can restore it; retrieval ignores it while deleted_at is set. */
    @Query("UPDATE memory_file SET deleted_at = :deletedAt WHERE assistant_id = :assistantId AND path = :path")
    suspend fun markDeleted(assistantId: String, path: String, deletedAt: Long): Int

    @Query("UPDATE memory_file SET deleted_at = 0 WHERE assistant_id = :assistantId AND path = :path")
    suspend fun markRestored(assistantId: String, path: String): Int

    @Query("DELETE FROM memory_file WHERE assistant_id = :assistantId AND path = :path")
    suspend fun deleteFile(assistantId: String, path: String)

    @Query("DELETE FROM memory_file WHERE assistant_id = :assistantId AND deleted_at != 0")
    suspend fun purgeDeletedFilesOfAssistant(assistantId: String)

    @Query("DELETE FROM memory_file WHERE assistant_id = :assistantId")
    suspend fun deleteFilesOfAssistant(assistantId: String)
}
