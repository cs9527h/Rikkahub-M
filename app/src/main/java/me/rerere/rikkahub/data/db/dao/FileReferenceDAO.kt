package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query

/** Read-only queries over the existing schema; no migration or new reference table. */
@Dao
interface FileReferenceDAO {
    // Search the basename too: JSON may contain escaped slashes or encoded file URIs.
    // LIKE is ASCII-case-insensitive for percent escapes without allocating lower(huge message JSON).
    // Wildcards in unusual names only retain extra files; they never permit an unsafe deletion.
    @Query("""
        SELECT EXISTS(SELECT 1 FROM message_node WHERE instr(messages, :fileName) > 0
            OR messages LIKE '%' || :encodedFileName || '%')
            OR EXISTS(SELECT 1 FROM favorites WHERE instr(snapshot_json, :fileName) > 0
                OR instr(ref_json, :fileName) > 0 OR instr(meta_json, :fileName) > 0
                OR snapshot_json LIKE '%' || :encodedFileName || '%'
                OR ref_json LIKE '%' || :encodedFileName || '%'
                OR meta_json LIKE '%' || :encodedFileName || '%')
    """)
    suspend fun messageReferencesFile(fileName: String, encodedFileName: String): Boolean
}
