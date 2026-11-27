package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.CatalogFile
import me.rerere.rikkahub.data.files.LiveConversationFileReferences
import me.rerere.rikkahub.data.files.ManagedFileCatalog
import java.io.File

class FilesRepository(
    private val dao: ManagedFileDAO,
) {
    suspend fun insert(file: ManagedFileEntity): ManagedFileEntity {
        val id = dao.insert(file)
        return file.copy(id = id)
    }

    suspend fun getById(id: Long): ManagedFileEntity? = dao.getById(id)

    suspend fun getByPath(relativePath: String): ManagedFileEntity? = dao.getByPath(relativePath)

    fun listByFolder(folder: String): Flow<List<ManagedFileEntity>> = dao.listByFolder(folder)

    /** Persisted attachments; the page supplies its lifecycle-owned accepted/queued overlay below. */
    fun observeCatalog(folder: String, filesDir: File): Flow<List<CatalogFile>> =
        observeCatalog(folder, filesDir, flowOf(emptyList()))

    internal fun observeCatalog(
        folder: String,
        filesDir: File,
        liveReferences: Flow<List<LiveConversationFileReferences>>,
    ): Flow<List<CatalogFile>> = ManagedFileCatalog(dao, filesDir).observe(dao, folder, liveReferences)

    suspend fun deleteById(id: Long): Int = dao.deleteById(id)

    suspend fun deleteByPath(relativePath: String): Int = dao.deleteByPath(relativePath)

    suspend fun deleteByFolder(folder: String): Int = dao.deleteByFolder(folder)
}
