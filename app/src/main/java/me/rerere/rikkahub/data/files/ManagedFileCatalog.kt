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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.db.dao.ManagedFileCatalogReference
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Ownership means a persisted conversation actually references this attachment, in any branch. */
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
 * Room invalidations. One scan per emission, not one scan per file. Only a small page of raw JSON
 * and one node's parse tree are retained, alongside the catalog and its ownership sets.
 * [readReferences] makes the bounded scanner testable without Android/Room.
 */
class ManagedFileCatalog(
    private val filesDir: File,
    private val readReferences: suspend (afterRowId: Long, limit: Int) -> List<ManagedFileCatalogReference>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(dao: ManagedFileDAO, filesDir: File) : this(filesDir, dao::catalogReferences)

    fun observe(dao: ManagedFileDAO, folder: String): Flow<List<CatalogFile>> = flow {
        val revision = AtomicLong()
        emitAll(combine(dao.listByFolder(folder), dao.observeCatalogReferences()) { files, _ ->
            files to revision.incrementAndGet()
        }.conflate().map { (files, startedAtRevision) ->
            val result = read(files)
            // Keep delivering during streaming writes, but never classify a non-snapshot scan
            // as definitely unassigned when an invalidation arrived while it was running.
            if (startedAtRevision == revision.get()) {
                result
            } else {
                result.map { it.copy(ownershipComplete = false) }
            }
        })
    }.flowOn(ioDispatcher)

    suspend fun read(files: List<ManagedFileEntity>): List<CatalogFile> = withContext(ioDispatcher) {
        val index = ManagedFileCatalogIndex(filesDir, files)
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
                    index.add(reference.assistantId, reference.messages)
                    afterRowId = reference.rowId
                }
                if (page.last().rowId >= scanEnd) break
            }
        }
        index.files()
    }

    companion object {
        internal const val REFERENCE_PAGE_SIZE = 16
    }
}

/** Package-private pure classification logic. Parsing failures do not invent an owner. */
internal class ManagedFileCatalogIndex(filesDir: File, private val entities: List<ManagedFileEntity>) {
    private val root = normalizePrivatePath(filesDir.absolutePath).trimEnd('/')
    private val paths = entities.associateBy { entity ->
        val path = try {
            normalizePrivatePath(File(filesDir, entity.relativePath).absolutePath)
        } catch (_: IllegalArgumentException) {
            null
        }
        path?.takeIf { !File(entity.relativePath).isAbsolute && it.startsWith("$root/") }
    }.filterKeys { it != null }
    private val owners = mutableMapOf<String, MutableSet<String>>()
    private var ownershipComplete = paths.size == entities.size

    suspend fun add(assistantId: String, messages: String?) {
        currentCoroutineContext().ensureActive()
        // Mirrors the SQL CASE ceiling, also protecting injected/test sources.
        if (assistantId.isBlank() || messages == null || messages.length > MAX_MESSAGE_CHARS) {
            ownershipComplete = false
            return
        }
        if (!hasBoundedNesting(messages)) {
            ownershipComplete = false
            return
        }
        val parsed = try {
            Json.parseToJsonElement(messages) as? JsonArray
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (parsed == null) {
            ownershipComplete = false
            return
        }
        for (message in parsed) {
            currentCoroutineContext().ensureActive()
            val parts = (message as? JsonObject)?.get("parts") as? JsonArray
            if (parts == null) ownershipComplete = false else visitParts(parts, assistantId)
        }
    }

    private suspend fun visitParts(parts: JsonArray, assistantId: String) {
        // Iterative traversal also handles nested tools without adding recursion to the JVM stack.
        val pending = ArrayDeque<Iterator<JsonElement>>()
        pending.addLast(parts.iterator())
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val iterator = pending.last()
            if (!iterator.hasNext()) {
                pending.removeLast()
                continue
            }
            val part = iterator.next() as? JsonObject
            if (part == null) {
                ownershipComplete = false
                continue
            }
            when ((part["type"] as? JsonPrimitive)?.content) {
                "image", "video", "audio", "document" -> {
                    val url = part["url"] as? JsonPrimitive
                    if (url != null && url.isString) {
                        record(url.content, assistantId)
                    } else {
                        ownershipComplete = false
                    }
                }
                "tool", "server_tool" -> (part["output"] as? JsonArray)?.let { pending.addLast(it.iterator()) }
                "tool_result" -> (part["content"] as? JsonArray)?.let { pending.addLast(it.iterator()) }
                // Future attachment types are not attribution evidence, nor proof of no owner.
                else -> if (part.containsKey("url")) ownershipComplete = false
            }
        }
    }

    private suspend fun hasBoundedNesting(json: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        json.forEachIndexed { index, char ->
            if (index % 1024 == 0) currentCoroutineContext().ensureActive()
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '[', '{' -> if (++depth > 128) return false
                    ']', '}' -> depth--
                }
            }
        }
        return true
    }

    private fun record(url: String, assistantId: String) {
        val uri = try {
            URI(url)
        } catch (_: IllegalArgumentException) {
            return
        } catch (_: java.net.URISyntaxException) {
            if (url.startsWith("file:", ignoreCase = true)) ownershipComplete = false
            return
        }
        if (!uri.scheme.equals("file", ignoreCase = true) ||
            !uri.authority.isNullOrEmpty() || uri.query != null || uri.fragment != null
        ) {
            return
        }
        val path = uri.path?.takeIf { it.startsWith('/') } ?: return
        val normalized = try {
            normalizePrivatePath(path)
        } catch (_: IllegalArgumentException) {
            ownershipComplete = false
            return
        }
        val entity = paths[normalized] ?: return
        owners.getOrPut(entity.relativePath) { linkedSetOf() }.add(assistantId)
    }

    fun files(): List<CatalogFile> = entities.map { entity ->
        CatalogFile(entity, owners[entity.relativePath]?.toSet().orEmpty(), ownershipComplete = ownershipComplete)
    }

    companion object {
        internal const val MAX_MESSAGE_CHARS = 262_144
    }
}

/** Only the equivalent Android user-0 private-data roots are aliased; never match a basename. */
private fun normalizePrivatePath(path: String): String {
    val normalized = File(path).toPath().normalize().toString()
    return if (normalized.startsWith("/data/data/")) {
        "/data/user/0/" + normalized.removePrefix("/data/data/")
    } else {
        normalized
    }
}
