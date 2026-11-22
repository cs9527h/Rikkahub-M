package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity

internal class FakeMemoryDAO : MemoryDAO {
    private val files = MutableStateFlow<List<MemoryFileEntity>>(emptyList())

    override suspend fun getAllFiles(): List<MemoryFileEntity> =
        files.value.sortedWith(compareBy({ it.assistantId }, { it.path }))

    override suspend fun deleteAllFiles() {
        files.value = emptyList()
    }

    override fun getFilesOfAssistantFlow(assistantId: String): Flow<List<MemoryFileEntity>> =
        files.map { all ->
            all.filter { it.assistantId == assistantId && it.deletedAt == 0L }.sortedBy { it.path }
        }

    override suspend fun getFilesOfAssistant(assistantId: String): List<MemoryFileEntity> =
        files.value.filter { it.assistantId == assistantId && it.deletedAt == 0L }.sortedBy { it.path }

    override suspend fun recallPage(assistantId: String, after: String): List<MemoryFileEntity> =
        getFilesOfAssistant(assistantId).filter { it.path > after }.take(16)

    override fun getDeletedFilesOfAssistantFlow(assistantId: String): Flow<List<MemoryFileEntity>> =
        files.map { all ->
            all.filter { it.assistantId == assistantId && it.deletedAt != 0L }
                .sortedWith(compareByDescending<MemoryFileEntity> { it.deletedAt }.thenBy { it.path })
        }

    override fun observeFile(scope: String, path: String): Flow<MemoryFileEntity?> =
        files.map { all -> all.find { it.assistantId == scope && it.path == path && it.deletedAt == 0L } }

    override suspend fun getFile(assistantId: String, path: String): MemoryFileEntity? =
        files.value.find { it.assistantId == assistantId && it.path == path && it.deletedAt == 0L }

    override suspend fun getFileIncludingDeleted(assistantId: String, path: String): MemoryFileEntity? =
        files.value.find { it.assistantId == assistantId && it.path == path }

    override suspend fun listByPrefix(scope: String, prefix: String, limit: Int): List<MemoryFileEntity> =
        getFilesOfAssistant(scope).filter { it.path.startsWith(prefix) }.take(limit)

    override suspend fun findImportedFact(scope: String, key: String): MemoryFileEntity? =
        files.value.filter { it.assistantId == scope && it.path.endsWith("/history-$key.md") }
            .minWithOrNull(compareBy({ it.deletedAt }, { it.path }))

    override suspend fun exactTextCandidates(scope: String, fact: String): List<MemoryFileEntity> =
        files.value.filter { it.assistantId == scope && it.content.contains(fact, ignoreCase = true) }
            .sortedWith(compareBy({ it.deletedAt }, { it.path })).take(24)

    override suspend fun upsertFile(file: MemoryFileEntity) = upsertFiles(listOf(file))

    override suspend fun upsertFiles(files: List<MemoryFileEntity>) {
        val replaced = files.map { it.assistantId to it.path }.toSet()
        this.files.value = this.files.value.filterNot { (it.assistantId to it.path) in replaced } + files
    }

    override suspend fun markDeleted(assistantId: String, path: String, deletedAt: Long): Int {
        var changed = 0
        files.value = files.value.map {
            if (it.assistantId == assistantId && it.path == path) {
                changed++
                it.copy(deletedAt = deletedAt)
            } else {
                it
            }
        }
        return changed
    }

    override suspend fun markRestored(assistantId: String, path: String): Int {
        var changed = 0
        files.value = files.value.map {
            if (it.assistantId == assistantId && it.path == path) {
                changed++
                it.copy(deletedAt = 0)
            } else {
                it
            }
        }
        return changed
    }

    override suspend fun deleteFile(assistantId: String, path: String) {
        files.value = files.value.filterNot { it.assistantId == assistantId && it.path == path }
    }

    override suspend fun purgeDeletedFilesOfAssistant(assistantId: String) {
        files.value = files.value.filterNot { it.assistantId == assistantId && it.deletedAt != 0L }
    }

    override suspend fun deleteFilesOfAssistant(assistantId: String) {
        files.value = files.value.filterNot { it.assistantId == assistantId }
    }
}
