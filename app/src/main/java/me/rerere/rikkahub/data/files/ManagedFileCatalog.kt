package me.rerere.rikkahub.data.files

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.ManagedFileCatalogReference
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Ownership is a real history/accepted-queue reference, in any branch; editor drafts do not count. */
data class CatalogFile(
    val entity: ManagedFileEntity,
    val assistantIds: Set<String>,
    val type: ManagedFileType = managedFileType(entity),
    val ownershipComplete: Boolean = true,
) {
    val isUnassigned: Boolean get() = ownershipComplete && assistantIds.isEmpty()
    val isShared: Boolean get() = assistantIds.size > 1
}

enum class ManagedFileType { IMAGE, VIDEO, AUDIO, DOCUMENT, OTHER }

sealed interface CatalogAssistantFilter {
    data object All : CatalogAssistantFilter
    data object Unassigned : CatalogAssistantFilter
    data object Unknown : CatalogAssistantFilter
    data object Shared : CatalogAssistantFilter
    data class Assistant(val id: String) : CatalogAssistantFilter
}

fun List<CatalogFile>.filterCatalog(
    type: ManagedFileType? = null,
    assistant: CatalogAssistantFilter = CatalogAssistantFilter.All,
): List<CatalogFile> = filter { file ->
    (type == null || file.type == type) && when (assistant) {
        CatalogAssistantFilter.All -> true
        CatalogAssistantFilter.Unassigned -> file.isUnassigned
        CatalogAssistantFilter.Unknown -> !file.ownershipComplete
        CatalogAssistantFilter.Shared -> file.isShared
        is CatalogAssistantFilter.Assistant -> assistant.id in file.assistantIds
    }
}

fun managedFileType(entity: ManagedFileEntity): ManagedFileType {
    val mime = entity.mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
    return when {
        mime.startsWith("image/") -> ManagedFileType.IMAGE
        mime.startsWith("video/") -> ManagedFileType.VIDEO
        mime.startsWith("audio/") -> ManagedFileType.AUDIO
        mime.startsWith("text/") || mime in documentMimeTypes ||
            mime.startsWith("application/vnd.openxmlformats-officedocument.") ||
            mime.startsWith("application/vnd.oasis.opendocument.") -> ManagedFileType.DOCUMENT
        // A generic MIME is common for historic/imported files. Only use its name for type,
        // never for ownership; a specific MIME always wins over the extension.
        mime.isBlank() || mime == "application/octet-stream" || mime == "*/*" -> when (
            entity.displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        ) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg" -> ManagedFileType.IMAGE
            "mp4", "webm", "mkv", "mov", "avi", "m4v", "3gp" -> ManagedFileType.VIDEO
            "mp3", "wav", "ogg", "m4a", "aac", "flac", "opus" -> ManagedFileType.AUDIO
            "pdf", "txt", "md", "json", "csv", "xml", "html", "htm", "log", "yaml", "yml",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub", "rtf", "odt", "ods", "odp" -> ManagedFileType.DOCUMENT
            else -> ManagedFileType.OTHER
        }
        else -> ManagedFileType.OTHER
    }
}

private val documentMimeTypes = setOf(
    "application/pdf", "application/json", "application/xml", "application/rtf", "application/epub+zip",
    "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
    "application/javascript", "application/yaml", "application/x-yaml",
)

/**
 * No denormalized ownership/schema migration: deletion and conversation moves are reflected by
 * Room invalidations and accepted runtime input. One scan per emission, not per file. A small
 * page/chunk of JSON is retained; text is skipped rather than building an unbounded parse tree.
 * [readReferences] makes the bounded scanner testable without Android/Room.
 */
class ManagedFileCatalog(
    private val filesDir: File,
    private val readReferences: suspend (afterRowId: Long, limit: Int) -> List<ManagedFileCatalogReference>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val readReferenceChunk: (suspend (rowId: Long, start: Long, length: Int) -> String?)? = null,
) {
    constructor(dao: ManagedFileDAO, filesDir: File) : this(
        filesDir,
        dao::catalogReferences,
        readReferenceChunk = dao::catalogReferenceChunk,
    )

    fun observe(dao: ManagedFileDAO, folder: String): Flow<List<CatalogFile>> =
        observe(dao, folder, flowOf(emptyList()))

    internal fun observe(
        dao: ManagedFileDAO,
        folder: String,
        liveReferences: Flow<List<LiveConversationFileReferences>>,
    ): Flow<List<CatalogFile>> = flow {
        val revision = AtomicLong()
        emitAll(combine(dao.listByFolder(folder), dao.observeCatalogReferences(), liveReferences) { files, _, live ->
            Triple(files, live, revision.incrementAndGet())
        }.conflate().map { (files, live, startedAtRevision) ->
            val result = read(files, live)
            // Keep delivering during streaming writes, but never classify a non-snapshot scan
            // as definitely unassigned when an invalidation arrived while it was running.
            if (startedAtRevision == revision.get()) {
                result
            } else {
                result.map { it.copy(ownershipComplete = false) }
            }
        })
    }.flowOn(ioDispatcher)

    suspend fun read(files: List<ManagedFileEntity>): List<CatalogFile> = read(files, emptyList())

    internal suspend fun read(
        files: List<ManagedFileEntity>,
        liveReferences: List<LiveConversationFileReferences>,
    ): List<CatalogFile> = withContext(ioDispatcher) {
        val index = ManagedFileCatalogIndex(filesDir, files)
        val liveHistories = liveReferences.filter { it.replacesPersistedHistory && it.complete }.mapTo(
            hashSetOf()
        ) { it.conversationId }
        for (reference in liveReferences) {
            index.addPaths(reference.assistantId, reference.paths, reference.complete)
        }
        if (files.isNotEmpty()) {
            var afterRowId = Long.MIN_VALUE
            var endRowId: Long? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = readReferences(afterRowId, REFERENCE_PAGE_SIZE)
                if (page.isEmpty()) break
                val scanEnd = endRowId ?: page.first().scanEndRowId.also { endRowId = it }
                for (reference in page) {
                    if (reference.rowId > scanEnd) break
                    currentCoroutineContext().ensureActive()
                    check(reference.rowId > afterRowId) { "Catalog references must be ordered by increasing rowId" }
                    if (reference.conversationId in liveHistories) {
                        afterRowId = reference.rowId
                        continue
                    }
                    if (reference.messages != null || readReferenceChunk == null || reference.messageChars <= 0) {
                        index.add(reference.assistantId, reference.messages)
                    } else {
                        var start = 1L
                        var complete = true
                        index.addChunks(reference.assistantId) {
                            if (start > reference.messageChars) {
                                null
                            } else {
                                val length = minOf(
                                    REFERENCE_CHUNK_CHARS.toLong(),
                                    reference.messageChars - start + 1
                                ).toInt()
                                val chunk = readReferenceChunk.invoke(reference.rowId, start, length)
                                if (chunk == null || chunk.codePointCount(0, chunk.length) != length) complete = false
                                start += length
                                chunk
                            }
                        }
                        if (!complete) index.markIncomplete()
                    }
                    afterRowId = reference.rowId
                }
                if (page.last().rowId >= scanEnd) break
            }
        }
        index.files()
    }

    companion object {
        internal const val REFERENCE_PAGE_SIZE = 16
        internal const val REFERENCE_CHUNK_CHARS = 32_768
    }
}

/** Package-private pure classification logic. Parsing failures do not invent an owner. */
internal class ManagedFileCatalogIndex(filesDir: File, private val entities: List<ManagedFileEntity>) {
    private val root = normalizePrivateFilePath(filesDir.absolutePath).trimEnd('/')
    private val paths = entities.associateBy { entity ->
        val path = try {
            normalizePrivateFilePath(File(filesDir, entity.relativePath).absolutePath)
        } catch (_: IllegalArgumentException) {
            null
        }
        path?.takeIf { !File(entity.relativePath).isAbsolute && it.startsWith("$root/") }
    }.filterKeys { it != null }
    private val owners = mutableMapOf<String, MutableSet<String>>()
    private var ownershipComplete = paths.size == entities.size

    suspend fun add(assistantId: String, messages: String?) {
        currentCoroutineContext().ensureActive()
        if (messages == null || messages.length > MAX_MESSAGE_CHARS * 2 ||
            messages.codePointCount(0, messages.length) > MAX_MESSAGE_CHARS
        ) {
            markIncomplete()
            return
        }
        var supplied = false
        addChunks(assistantId) {
            if (supplied) null else messages.also { supplied = true }
        }
    }

    internal suspend fun addChunks(assistantId: String, nextChunk: suspend () -> String?) {
        if (assistantId.isBlank()) {
            markIncomplete()
            return
        }
        val references = scanMessageFileReferences(nextChunk) { it in paths }
        addPaths(assistantId, references.paths, references.complete)
    }

    internal fun addPaths(assistantId: String?, referencedPaths: Set<String>, complete: Boolean) {
        if (!complete || (assistantId.isNullOrBlank() && referencedPaths.isNotEmpty())) markIncomplete()
        if (assistantId.isNullOrBlank()) return
        referencedPaths.forEach { path ->
            val entity = paths[path] ?: return@forEach
            owners.getOrPut(entity.relativePath) { linkedSetOf() }.add(assistantId)
        }
    }

    internal fun markIncomplete() {
        ownershipComplete = false
    }

    fun files(): List<CatalogFile> = entities.map { entity ->
        CatalogFile(entity, owners[entity.relativePath]?.toSet().orEmpty(), ownershipComplete = ownershipComplete)
    }

    companion object {
        internal const val MAX_MESSAGE_CHARS = 262_144
    }
}
