package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.ai.memory.MemoryRecallEngine
import me.rerere.rikkahub.data.ai.memory.RecallCandidate
import me.rerere.rikkahub.data.ai.memory.RecallResult
import me.rerere.rikkahub.data.ai.memory.filteringReason
import me.rerere.rikkahub.data.ai.memory.recallTerms
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryFile

sealed interface MemoryWriteResult {
    /** [file] 为 null 表示文件已被删除 */
    data class Success(val file: MemoryFile?) : MemoryWriteResult

    /** 版本对不上；[current] 是最新内容，为 null 表示文件不存在 */
    data class Conflict(val current: MemoryFile?) : MemoryWriteResult

    data class Rejected(val reason: String) : MemoryWriteResult
}

/**
 * 记忆库：每个助手（或全局）一组 Markdown 文件。
 *
 * 写操作都带 ifVersion 做乐观锁：传 [NEW_FILE_VERSION] 表示新建，传版本号表示基于该版本修改，
 * 传 null 则不校验（用户在界面里直接编辑）。
 */
class MemoryRepository(private val memoryDAO: MemoryDAO) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
        const val NEW_FILE_VERSION = "new"
    }

    // 读取、校验版本、写入必须一起完成，否则两个写入方会互相覆盖
    private val writeLock = Mutex()

    fun getFilesFlow(assistantId: String): Flow<List<MemoryFile>> =
        memoryDAO.getFilesOfAssistantFlow(assistantId).map { entities -> entities.map { it.toMemoryFile() } }

    fun observeFile(scope: String, path: String): Flow<MemoryFile?> =
        memoryDAO.observeFile(scope, path).map { it?.toMemoryFile() }

    /** 最近删除的文件；只在界面里展示，不参与检索、提示词或整理。 */
    fun getDeletedFilesFlow(assistantId: String): Flow<List<MemoryFile>> =
        memoryDAO.getDeletedFilesOfAssistantFlow(assistantId).map { entities -> entities.map { it.toMemoryFile() } }

    suspend fun getFiles(assistantId: String): List<MemoryFile> =
        memoryDAO.getFilesOfAssistant(assistantId).map { it.toMemoryFile() }

    internal suspend fun listForChat(scope: String, prefix: String, limit: Int): List<MemoryFile> =
        memoryDAO.listByPrefix(scope, prefix, limit).map { it.toMemoryFile() }

    internal suspend fun findImportedFact(scope: String, key: String): MemoryFile? =
        memoryDAO.findImportedFact(scope, key)?.toMemoryFile()

    internal suspend fun exactTextCandidates(scope: String, fact: String): List<MemoryFile> =
        memoryDAO.exactTextCandidates(scope, fact).map { it.toMemoryFile() }

    /** 兼容旧调用：只要命中的文件。 */
    suspend fun searchForRecall(assistantId: String, query: String): List<MemoryFile> =
        searchRecallWithDiagnostics(assistantId, query).files

    /**
     * 多阶段召回 + 诊断：每轮聊天既要知道“命中了什么”，也要知道“为什么只命中这些”。
     *
     * 仍然只读现有 (assistant_id, path) 索引分页扫描，不引入 Embedding API，
     * 也不需要额外数据库或网络。
     */
    internal suspend fun searchRecallWithDiagnostics(assistantId: String, query: String): RecallResult =
        withContext(Dispatchers.Default) {
            val recallQuery = MemoryRecallEngine.analyze(query)
            if (recallQuery.terms.isEmpty() && recallQuery.phrases.isEmpty() && !recallQuery.broad) {
                return@withContext RecallResult(emptyList(), 0, 0, query, false, emptyMap())
            }
            val candidates = mutableListOf<RecallCandidate>()
            val fallback = mutableListOf<MemoryFile>()
            val reasons = mutableMapOf<String, Int>()
            fun countReason(reason: String) {
                reasons[reason] = (reasons[reason] ?: 0) + 1
            }
            var considered = 0
            var filtered = 0
            var after = ""
            while (true) {
                val page = memoryDAO.recallPage(assistantId, after)
                if (page.isEmpty()) break
                for (entity in page) {
                    val file = entity.toMemoryFile()
                    considered++
                    file.filteringReason(recallQuery)?.let {
                        countReason(it)
                        filtered++
                        continue
                    }
                    if (recallQuery.broad && file.importance >= 2) fallback += file
                    val candidate = MemoryRecallEngine.score(file, recallQuery)
                    if (candidate == null) {
                        countReason("内容不相关")
                        filtered++
                    } else {
                        candidates += candidate
                    }
                }
                candidates.sortWith(
                    compareByDescending<RecallCandidate> { it.score }
                        .thenByDescending { it.file.updatedAt },
                )
                if (candidates.size > 24) candidates.subList(24, candidates.size).clear()
                after = page.last().path
            }
            // 宽泛问题（“你记得我以前问过什么吗”）命中太少时，用重要且较新的记忆兜底
            if (recallQuery.broad && candidates.size < 4) {
                fallback.asSequence()
                    .sortedWith(compareByDescending<MemoryFile> { it.importance }.thenByDescending { it.updatedAt })
                    .take(4 - candidates.size)
                    .forEach { file ->
                        if (candidates.none { it.file.path == file.path }) {
                            candidates += RecallCandidate(
                                file = file,
                                score = 6,
                                stage = 4,
                                reasons = listOf("宽泛问题：补充重要记忆"),
                            )
                            countReason("宽泛兜底命中")
                        }
                    }
            }
            RecallResult(
                files = candidates
                    .sortedWith(
                        compareByDescending<RecallCandidate> { it.score }
                            .thenByDescending { it.file.importance * 3 + MemoryRecallEngine.recencyBonus(it.file.updatedAt) },
                    )
                    .take(10)
                    .map { it.file },
                considered = considered,
                filtered = filtered,
                query = query,
                broad = recallQuery.broad,
                filterReasons = reasons,
            )
        }

    suspend fun getFile(assistantId: String, path: String): MemoryFile? {
        val normalized = MemoryFile.normalizePath(path) ?: return null
        return memoryDAO.getFile(assistantId, normalized)?.toMemoryFile()
    }

    /** 新建或整体覆盖 */
    suspend fun writeFile(
        assistantId: String,
        path: String,
        content: String,
        ifVersion: String? = null,
    ): MemoryWriteResult = update(assistantId, path, ifVersion, allowCreate = true) { Edit.Content(content) }

    /** 把唯一匹配的 [oldStr] 换成 [newStr]，[newStr] 为空即删除 */
    suspend fun replaceInFile(
        assistantId: String,
        path: String,
        oldStr: String,
        newStr: String,
        ifVersion: String?,
    ): MemoryWriteResult = update(assistantId, path, ifVersion) { current ->
        if (oldStr.isEmpty()) return@update Edit.Reject("old_str must not be empty.")
        val index = current.indexOf(oldStr)
        when {
            index < 0 -> Edit.Reject("old_str was not found in $path.")
            current.indexOf(oldStr, index + 1) >= 0 ->
                Edit.Reject(
                    "old_str matches more than one place in $path; include more surrounding text to make it unique."
                )

            else -> Edit.Content(current.replaceAt(index, oldStr.length, newStr))
        }
    }

    /** 在文件末尾追加一行 */
    suspend fun appendToFile(
        assistantId: String,
        path: String,
        line: String,
        ifVersion: String?,
    ): MemoryWriteResult = update(assistantId, path, ifVersion) { current ->
        val separator = if (current.isEmpty() || current.endsWith("\n")) "" else "\n"
        Edit.Content(current + separator + line.trimEnd('\n', '\r') + "\n")
    }

    /** 移入“最近删除”，检索立刻看不到；用户可在记忆页恢复或永久删除。 */
    suspend fun deleteFile(
        assistantId: String,
        path: String,
        ifVersion: String? = null,
    ): MemoryWriteResult = writeLock.withLock {
        val normalized = MemoryFile.normalizePath(path) ?: return invalidPath(path)
        val current = memoryDAO.getFile(assistantId, normalized)?.toMemoryFile()
        if (current == null || (ifVersion != null && ifVersion != current.version)) {
            return MemoryWriteResult.Conflict(current)
        }
        memoryDAO.markDeleted(assistantId, normalized, System.currentTimeMillis())
        MemoryWriteResult.Success(null)
    }

    /** 永久删除；只允许删除已经在“最近删除”里的文件，避免误删正在使用的记忆。 */
    suspend fun purgeFile(assistantId: String, path: String): Boolean = writeLock.withLock {
        val normalized = MemoryFile.normalizePath(path) ?: return@withLock false
        val current = memoryDAO.getFileIncludingDeleted(assistantId, normalized)
        if (current == null || current.deletedAt == 0L) return@withLock false
        memoryDAO.deleteFile(assistantId, normalized)
        true
    }

    suspend fun restoreFile(assistantId: String, path: String): MemoryWriteResult = writeLock.withLock {
        val normalized = MemoryFile.normalizePath(path) ?: return invalidPath(path)
        val current = memoryDAO.getFileIncludingDeleted(assistantId, normalized)?.toMemoryFile()
            ?: return MemoryWriteResult.Conflict(null)
        if (current.deletedAt == 0L) return MemoryWriteResult.Success(current)
        // 只恢复删除标记；不裁剪旧正文，也不改变原更新时间。
        memoryDAO.markRestored(assistantId, normalized)
        MemoryWriteResult.Success(current.copy(deletedAt = 0))
    }

    /** 撤销批量操作时需要连“最近删除”里的旧内容一起看。 */
    suspend fun getFileIncludingDeleted(assistantId: String, path: String): MemoryFile? {
        val normalized = MemoryFile.normalizePath(path) ?: return null
        return memoryDAO.getFileIncludingDeleted(assistantId, normalized)?.toMemoryFile()
    }

    /** 原样写回一份历史内容（撤销用），不经过用户编辑校验。 */
    suspend fun restoreSnapshot(assistantId: String, path: String, content: String, updatedAt: Long): Boolean {
        val normalized = MemoryFile.normalizePath(path) ?: return false
        if (content.toByteArray().size > MemoryFile.MAX_BYTES) return false
        return writeLock.withLock {
            memoryDAO.upsertFile(
                MemoryFileEntity(
                    assistantId = assistantId,
                    path = normalized,
                    content = content,
                    updatedAt = updatedAt,
                    deletedAt = 0,
                )
            )
            true
        }
    }

    /** 撤销“新建”时删除文件，但如果用户已经改过内容就保持不动。 */
    suspend fun purgeIfContentMatches(assistantId: String, path: String, content: String): Boolean = writeLock.withLock {
        val normalized = MemoryFile.normalizePath(path) ?: return@withLock false
        val current = memoryDAO.getFileIncludingDeleted(assistantId, normalized) ?: return@withLock true
        if (current.content != content) return@withLock false
        memoryDAO.deleteFile(assistantId, normalized)
        true
    }

    suspend fun purgeDeletedFiles(assistantId: String) {
        memoryDAO.purgeDeletedFilesOfAssistant(assistantId)
    }

    suspend fun deleteFilesOfAssistant(assistantId: String) {
        memoryDAO.deleteFilesOfAssistant(assistantId)
    }

    suspend fun copyFiles(fromAssistantId: String, toAssistantId: String) {
        val files = memoryDAO.getFilesOfAssistant(fromAssistantId)
        if (files.isEmpty()) return
        memoryDAO.upsertFiles(files.map { it.copy(assistantId = toAssistantId) })
    }

    private sealed interface Edit {
        data class Content(val content: String) : Edit
        data class Reject(val reason: String) : Edit
    }

    private suspend fun update(
        assistantId: String,
        path: String,
        ifVersion: String?,
        allowCreate: Boolean = false,
        edit: (current: String) -> Edit,
    ): MemoryWriteResult = writeLock.withLock {
        val normalized = MemoryFile.normalizePath(path) ?: return invalidPath(path)
        val existing = memoryDAO.getFileIncludingDeleted(assistantId, normalized)
        if (existing != null && existing.deletedAt != 0L) {
            return MemoryWriteResult.Rejected("该路径的记忆仍在最近删除中，请先恢复或永久删除；未覆盖原内容。")
        }
        val current = existing?.toMemoryFile()
        val versionMatches = when (ifVersion) {
            null -> current != null || allowCreate
            NEW_FILE_VERSION -> current == null && allowCreate
            else -> current?.version == ifVersion
        }
        if (!versionMatches) return MemoryWriteResult.Conflict(current)

        val content = when (val result = edit(current?.content.orEmpty())) {
            is Edit.Reject -> return MemoryWriteResult.Rejected(result.reason)
            is Edit.Content -> result.content
        }
        // 只拦会让文件变大的超限写入：迁移来的旧记忆可能本来就超限，得允许一点点删小
        val size = content.toByteArray().size
        if (size > MemoryFile.MAX_BYTES && size > current?.content?.toByteArray()?.size ?: 0) {
            return MemoryWriteResult.Rejected(
                "$normalized would exceed the ${MemoryFile.MAX_BYTES / 1024}KB limit. " +
                    "Merge duplicates and drop outdated details instead of adding more."
            )
        }
        val entity = MemoryFileEntity(
            assistantId = assistantId,
            path = normalized,
            content = content,
            updatedAt = System.currentTimeMillis(),
        )
        memoryDAO.upsertFile(entity)
        MemoryWriteResult.Success(entity.toMemoryFile())
    }

    private fun invalidPath(path: String) = MemoryWriteResult.Rejected(
        "Invalid path \"$path\". Use an absolute path ending in .md, e.g. /topics/food.md."
    )
}

/** 助手读写的记忆库：开启全局记忆后所有助手共用一份 */
val Assistant.memoryId: String
    get() = if (useGlobalMemory) MemoryRepository.GLOBAL_MEMORY_ID else id.toString()

private fun MemoryFileEntity.toMemoryFile() =
    MemoryFile(path = path, content = content, updatedAt = updatedAt, deletedAt = deletedAt)

// 删掉的是完整一行时连换行一起去掉，不留空行
private fun String.replaceAt(index: Int, length: Int, replacement: String): String {
    var start = index
    var end = index + length
    if (replacement.isEmpty()) {
        val atLineStart = start == 0 || this[start - 1] == '\n'
        val atLineEnd = end == this.length || this[end] == '\n'
        if (atLineStart && atLineEnd) {
            if (end < this.length) {
                end++
            } else if (start > 0) {
                start--
            }
        }
    }
    return substring(0, start) + replacement + substring(end)
}
