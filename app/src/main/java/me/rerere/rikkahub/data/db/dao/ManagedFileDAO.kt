package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity

@Dao
interface ManagedFileDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: ManagedFileEntity): Long

    @Query("SELECT * FROM managed_files WHERE id = :id")
    suspend fun getById(id: Long): ManagedFileEntity?

    @Query("SELECT * FROM managed_files WHERE relative_path = :relativePath")
    suspend fun getByPath(relativePath: String): ManagedFileEntity?

    @Query("SELECT * FROM managed_files WHERE folder = :folder ORDER BY created_at DESC")
    fun listByFolder(folder: String): Flow<List<ManagedFileEntity>>

    // Cheap invalidation token, not a content version. Room emits for either table even if
    // this value stays unchanged (e.g. moving a conversation); do not distinctUntilChanged.
    @Query("SELECT EXISTS(SELECT 1 FROM message_node) + EXISTS(SELECT 1 FROM ConversationEntity)")
    fun observeCatalogReferences(): Flow<Long>

    // Keyset pagination avoids OFFSET's repeated walks and never loads an entire history.
    @Query("""
        SELECT n.rowid AS rowId, c.assistant_id AS assistantId,
            CASE WHEN length(n.messages) <= 262144 THEN n.messages ELSE NULL END AS messages,
            (SELECT COALESCE(MAX(rowid), 0) FROM message_node) AS scanEndRowId
        FROM message_node n INNER JOIN ConversationEntity c ON c.id = n.conversation_id
        WHERE n.rowid > :afterRowId ORDER BY n.rowid LIMIT :limit
    """)
    suspend fun catalogReferences(afterRowId: Long, limit: Int): List<ManagedFileCatalogReference>

    @Query("DELETE FROM managed_files WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM managed_files WHERE relative_path = :relativePath")
    suspend fun deleteByPath(relativePath: String): Int

    @Query("DELETE FROM managed_files WHERE folder = :folder")
    suspend fun deleteByFolder(folder: String): Int
}

/** Read-only projection; deliberately not a Room entity or an additional schema table. */
data class ManagedFileCatalogReference(
    val rowId: Long,
    val assistantId: String,
    /** null means an oversized node, not an absence of file references. */
    val messages: String?,
    /** Captured on the first page so continuous inserts cannot make a scan endless. */
    val scanEndRowId: Long = Long.MAX_VALUE,
)
