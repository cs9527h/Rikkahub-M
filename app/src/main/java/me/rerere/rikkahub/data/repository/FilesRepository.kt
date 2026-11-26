package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.CatalogFile
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

    /** Live attachment ownership, independent of the currently selected assistant. */
    fun observeCatalog(folder: String, filesDir: File): Flow<List<CatalogFile>> =
        ManagedFileCatalog(dao, filesDir).observe(dao, folder)

    suspend fun deleteById(id: Long): Int = dao.deleteById(id)

    suspend fun deleteByPath(relativePath: String): Int = dao.deleteByPath(relativePath)

    suspend fun deleteByFolder(folder: String): Int = dao.deleteByFolder(folder)
}
