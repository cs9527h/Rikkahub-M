package me.rerere.rikkahub.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.normalizeLocalFileReference
import me.rerere.rikkahub.data.files.normalizePrivateFilePath
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import java.io.File
import java.util.IdentityHashMap
import kotlin.uuid.Uuid

/** Runtime overlay only; a complete conversation replaces its persisted ownership, including old assistants. */
data class RuntimeConversationAttachmentReferences(
    val assistantId: Uuid,
    val sentUrls: Set<String>,
    val historyComplete: Boolean,
    val referencesComplete: Boolean = true,
)

data class RuntimeAttachmentReferences(
    val conversations: Map<Uuid, RuntimeConversationAttachmentReferences> = emptyMap(),
    val protectedUrls: Set<String> = emptySet(),
    val complete: Boolean = true,
) {
    val completeConversationIds: Set<Uuid>
        get() = conversations.filterValues { it.historyComplete && it.referencesComplete }.keys
}

internal data class SessionAttachmentReferences(
    val sent: RuntimeConversationAttachmentReferences,
    val protectedUrls: Set<String>,
    val referencesComplete: Boolean = true,
)

internal data class AttachmentUrlReferences(val urls: Set<String> = emptySet(), val complete: Boolean = true)

internal const val MAX_RUNTIME_ATTACHMENT_DEPTH = 128
internal const val MAX_RUNTIME_ATTACHMENT_PARTS = 100_000
internal const val MAX_RUNTIME_ATTACHMENT_URLS = 16_384
private const val MAX_RUNTIME_ATTACHMENT_URL_LENGTH = 8_192

private sealed interface AttachmentFrame {
    val depth: Int
    data class Typed(val parts: Iterator<UIMessagePart>, override val depth: Int) : AttachmentFrame
    data class Structured(val parts: Iterator<JsonElement>, override val depth: Int) : AttachmentFrame
}

/**
 * The synchronous scanner used by session, draft and preset protection. Only attachment fields and
 * structured tool output part arrays are visited: text/reasoning/input/metadata are never searched.
 * No JSON serialization, string parsing, coroutine or global registry is involved.
 */
@Suppress("DEPRECATION")
internal fun collectRuntimeAttachmentReferences(parts: Sequence<UIMessagePart>): AttachmentUrlReferences {
    val urls = linkedSetOf<String>()
    var complete = true
    var visited = 0
    val pending = ArrayDeque<AttachmentFrame>()
    pending.addLast(AttachmentFrame.Typed(parts.iterator(), 0))

    fun record(url: String) {
        // Remote references need no local deletion protection, even if very long.
        if (!url.startsWith("file:", ignoreCase = true)) return
        if (url.length > MAX_RUNTIME_ATTACHMENT_URL_LENGTH || normalizeLocalFileReference(url) == null) {
            complete = false
        } else if (urls.size < MAX_RUNTIME_ATTACHMENT_URLS || url in urls) {
            urls.add(url)
        } else {
            complete = false
        }
    }

    fun structured(element: JsonElement?, depth: Int) {
        val array = element as? JsonArray ?: return
        if (array.isEmpty()) return
        if (depth > MAX_RUNTIME_ATTACHMENT_DEPTH) {
            complete = false
        } else {
            pending.addLast(AttachmentFrame.Structured(array.iterator(), depth))
        }
    }

    while (pending.isNotEmpty()) {
        val frame = pending.last()
        val hasNext = when (frame) {
            is AttachmentFrame.Typed -> frame.parts.hasNext()
            is AttachmentFrame.Structured -> frame.parts.hasNext()
        }
        if (!hasNext) {
            pending.removeLast()
            continue
        }
        if (++visited > MAX_RUNTIME_ATTACHMENT_PARTS) {
            complete = false
            break
        }
        when (frame) {
            is AttachmentFrame.Typed -> when (val part = frame.parts.next()) {
                is UIMessagePart.Image -> record(part.url)
                is UIMessagePart.Video -> record(part.url)
                is UIMessagePart.Audio -> record(part.url)
                is UIMessagePart.Document -> record(part.url)
                is UIMessagePart.Tool -> if (part.output.isNotEmpty()) {
                    if (frame.depth >= MAX_RUNTIME_ATTACHMENT_DEPTH) {
                        complete = false
                    } else {
                        pending.addLast(AttachmentFrame.Typed(part.output.iterator(), frame.depth + 1))
                    }
                }
                is UIMessagePart.ServerTool -> structured(part.output, frame.depth + 1)
                is UIMessagePart.ToolResult -> structured(part.content, frame.depth + 1)
                else -> Unit
            }
            is AttachmentFrame.Structured -> {
                val part = frame.parts.next() as? JsonObject
                if (part == null) {
                    complete = false
                    continue
                }
                when (val type = (part["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
                    "image", "video", "audio", "document" -> {
                        val url = part["url"] as? JsonPrimitive
                        if (url?.isString == true) record(url.content) else complete = false
                    }
                    "tool", "server_tool", "tool_result" -> structured(
                        part[if (type == "tool_result") "content" else "output"],
                        frame.depth + 1,
                    )
                    else -> if (part.containsKey("url")) complete = false
                }
            }
        }
    }
    return AttachmentUrlReferences(urls, complete)
}

internal fun presetAttachmentReferences(settings: Settings): AttachmentUrlReferences {
    if (settings.init) return AttachmentUrlReferences(complete = false)
    return collectRuntimeAttachmentReferences(settings.assistants.asSequence().flatMap { assistant ->
        assistant.presetMessages.asSequence().flatMap { it.parts.asSequence() }
    })
}

internal fun mergeAttachmentUrlReferences(references: Sequence<AttachmentUrlReferences>): AttachmentUrlReferences {
    val urls = linkedSetOf<String>()
    var complete = true
    references.forEach { reference ->
        complete = complete && reference.complete
        reference.urls.forEach { url ->
            if (urls.size < MAX_RUNTIME_ATTACHMENT_URLS || url in urls) urls.add(url) else complete = false
        }
    }
    return AttachmentUrlReferences(urls, complete)
}

/** Session-owned, identity-keyed cache; it retains only nodes in the current history and URL sets. */
internal class SessionAttachmentReferenceCache {
    private var nodes = IdentityHashMap<MessageNode, AttachmentUrlReferences>()

    fun snapshot(
        conversation: Conversation,
        historyComplete: Boolean,
        historySubmitted: Boolean,
        inbox: InboxState,
        submitting: QueuedMessage?,
    ): SessionAttachmentReferences {
        val currentNodes = IdentityHashMap<MessageNode, AttachmentUrlReferences>()
        val history = mergeAttachmentUrlReferences(conversation.messageNodes.asSequence().map { node ->
            val references = nodes[node] ?: collectRuntimeAttachmentReferences(
                node.messages.asSequence().flatMap { it.parts.asSequence() },
            )
            currentNodes[node] = references
            references
        })
        nodes = currentNodes
        val queued = collectRuntimeAttachmentReferences(sequence {
            inbox.messages.forEach { yieldAll(it.parts) }
            submitting?.let { yieldAll(it.parts) }
        })
        val sent = if (historySubmitted) mergeAttachmentUrlReferences(sequenceOf(history, queued)) else queued
        val protected = mergeAttachmentUrlReferences(sequenceOf(history, queued))
        return SessionAttachmentReferences(
            sent = RuntimeConversationAttachmentReferences(
                assistantId = conversation.assistantId,
                // Before load the assistant in the placeholder may not be the persisted conversation's owner.
                sentUrls = if (historyComplete) sent.urls else emptySet(),
                historyComplete = historyComplete,
                referencesComplete = sent.complete && protected.complete,
            ),
            protectedUrls = protected.urls,
            referencesComplete = protected.complete,
        )
    }
}

/** Providers close the draft snapshotFlow scheduling gap; callers own registration until their VM is cleared. */
internal class DraftAttachmentReferenceSources {
    private val providers = mutableMapOf<Uuid, () -> List<UIMessagePart>>()
    private val _references = MutableStateFlow<Map<Uuid, AttachmentUrlReferences>>(emptyMap())
    val references: StateFlow<Map<Uuid, AttachmentUrlReferences>> = _references.asStateFlow()

    @Synchronized
    fun update(ownerId: Uuid, parts: List<UIMessagePart>) {
        _references.value = _references.value + (ownerId to collectRuntimeAttachmentReferences(parts.asSequence()))
    }

    @Synchronized
    fun register(ownerId: Uuid, provider: () -> List<UIMessagePart>) {
        val references = collectRuntimeAttachmentReferences(provider().asSequence())
        providers[ownerId] = provider
        _references.value = _references.value + (ownerId to references)
    }

    @Synchronized
    fun clear(ownerId: Uuid) {
        providers.remove(ownerId)
        _references.value = _references.value - ownerId
    }

    @Synchronized
    fun clearAll() {
        providers.clear()
        _references.value = emptyMap()
    }

    @Synchronized
    fun snapshot(): Collection<AttachmentUrlReferences> = _references.value.toMutableMap().apply {
        providers.forEach { (ownerId, provider) ->
            put(ownerId, collectRuntimeAttachmentReferences(provider().asSequence()))
        }
    }.values
}

internal fun mergeRuntimeAttachmentReferences(
    sessions: Map<Uuid, SessionAttachmentReferences>,
    drafts: Collection<AttachmentUrlReferences>,
    presetReferences: AttachmentUrlReferences,
): RuntimeAttachmentReferences {
    val protected = mergeAttachmentUrlReferences(sequence {
        sessions.values.forEach { yield(AttachmentUrlReferences(it.protectedUrls, it.referencesComplete)) }
        yieldAll(drafts)
        yield(presetReferences)
    })
    return RuntimeAttachmentReferences(
        conversations = sessions.mapValues { it.value.sent },
        protectedUrls = protected.urls,
        complete = protected.complete,
    )
}

internal fun observeRuntimeAttachmentReferences(
    sessions: StateFlow<Map<Uuid, ConversationSession>>,
    drafts: StateFlow<Map<Uuid, AttachmentUrlReferences>>,
    presets: Flow<AttachmentUrlReferences>,
): Flow<RuntimeAttachmentReferences> {
    val references = sessions.flatMapLatest { current ->
        if (current.isEmpty()) {
            flowOf(emptyMap<Uuid, SessionAttachmentReferences>())
        } else {
            combine(current.values.map { session ->
                combine(session.state, session.inbox.state, session.attachmentRevision) { _, _, _ ->
                    session.id to session.attachmentReferenceSnapshot()
                }.distinctUntilChanged()
            }) { snapshots -> snapshots.toMap() }
        }
    }
    return combine(references, drafts, presets) { current, pendingDrafts, presetReferences ->
        mergeRuntimeAttachmentReferences(current, pendingDrafts.values, presetReferences)
    }.distinctUntilChanged().flowOn(Dispatchers.Default)
}

/** Same normalized identity as catalog attribution; no basename or serialized-text matching. */
internal fun RuntimeAttachmentReferences.isLocalFileReferenced(filesDir: File, relativePath: String): Boolean {
    if (!complete) return true
    if (relativePath.isBlank() || File(relativePath).isAbsolute) return false
    val root = normalizePrivateFilePath(filesDir.absolutePath).trimEnd('/')
    val candidate = normalizePrivateFilePath(File(filesDir, relativePath).absolutePath)
    if (!candidate.startsWith("$root/")) return false
    return protectedUrls.any { normalizeLocalFileReference(it) == candidate }
}
