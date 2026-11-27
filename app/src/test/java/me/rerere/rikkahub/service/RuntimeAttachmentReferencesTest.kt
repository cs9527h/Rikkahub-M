package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.files.collectLiveFileReferences
import me.rerere.rikkahub.data.files.normalizeLocalFileReference
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

class RuntimeAttachmentReferencesTest {
    private val image = "file:///data/user/0/test.app/files/upload/shared%20image.png"
    private fun parts(url: String = image) = listOf(UIMessagePart.Image(url))
    private fun message(url: String = image) = UIMessage.user("").copy(parts = parts(url))

    private class Rig {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = FakeConversationStore()
        val manager = ConversationSessionManager(scope, store, { Conversation.ofId(it) }, { _, _ -> })
        val drafts = DraftAttachmentReferenceSources()
        val flow = observeRuntimeAttachmentReferences(manager.activeSessions, drafts.references, flowOf(AttachmentUrlReferences()))

        fun close() {
            manager.cleanup()
            drafts.clearAll()
            scope.cancel()
        }
    }

    @Test
    fun `queued image belongs to actual conversation assistant without saving or generating`() = runBlocking {
        val rig = Rig()
        try {
            val id = Uuid.random()
            val assistant = Uuid.random()
            val session = rig.manager.getOrCreate(id)
            session.initialize(load = { null }, create = { Conversation.ofId(id, assistantId = assistant) })
            session.inbox.pause()
            session.inbox.enqueue(parts())
            val snapshot = withTimeout(5_000) { rig.flow.first { image in it.conversations[id]?.sentUrls.orEmpty() } }
            assertEquals(assistant, snapshot.conversations.getValue(id).assistantId)
            assertTrue(id in snapshot.completeConversationIds)
            assertTrue(image in snapshot.protectedUrls)
            assertTrue(rig.store.writes.isEmpty())
            assertFalse(session.isGenerating)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `inbox to submitting to unsaved history handoff keeps sent ownership and protection`() = runBlocking {
        val rig = Rig()
        try {
            val id = Uuid.random()
            val session = rig.manager.getOrCreate(id)
            session.initialize(load = { null }, create = { Conversation.ofId(id) })
            session.inbox.enqueue(parts())
            assertTrue(image in session.attachmentReferenceSnapshot().sent.sentUrls)
            synchronized(session) {
                val turn = session.inbox.takeNext() as InboxTurn.Message
                session.submittingMessage = turn.message
            }
            assertTrue(session.inbox.state.value.messages.isEmpty())
            assertTrue(image in session.attachmentReferenceSnapshot().sent.sentUrls)
            assertTrue(image in session.attachmentReferenceSnapshot().protectedUrls)
            // Deliberately do not flush: runtime ownership must not rely on message_node writes.
            session.updateSubmittedHistory { it.updateCurrentMessages(listOf(message())) }
            session.submittingMessage = null
            val final = session.attachmentReferenceSnapshot()
            assertTrue(image in final.sent.sentUrls)
            assertTrue(image in final.protectedUrls)
            assertTrue(rig.store.writes.isEmpty())
        } finally {
            rig.close()
        }
    }

    @Test
    fun `withdrawal and cancellation before history remove pending ownership without inventing history`() = runBlocking {
        val rig = Rig()
        try {
            val id = Uuid.random()
            val session = rig.manager.getOrCreate(id)
            session.initialize(load = { null }, create = { Conversation.ofId(id) })
            session.inbox.enqueue(parts())
            session.inbox.remove(session.inbox.state.value.messages.single().id)
            assertTrue(session.attachmentReferenceSnapshot().sent.sentUrls.isEmpty())
            session.inbox.enqueue(parts())
            synchronized(session) {
                session.submittingMessage = (session.inbox.takeNext() as InboxTurn.Message).message
            }
            session.submittingMessage = null // same as a cancelled job's completion cleanup
            val snapshot = session.attachmentReferenceSnapshot()
            assertTrue(snapshot.sent.sentUrls.isEmpty())
            assertTrue(snapshot.protectedUrls.isEmpty())
            assertTrue(session.state.value.messageNodes.isEmpty())
        } finally {
            rig.close()
        }
    }

    @Test
    fun `draft providers synchronously protect new attachments before async invalidation and never claim ownership`() {
        val drafts = DraftAttachmentReferenceSources()
        val owner = Uuid.random()
        var current = emptyList<UIMessagePart>()
        drafts.register(owner) { current }
        current = parts() // snapshotFlow has not run yet
        val snapshot = mergeRuntimeAttachmentReferences(emptyMap(), drafts.snapshot(), AttachmentUrlReferences())
        assertTrue(image in snapshot.protectedUrls)
        assertTrue(snapshot.conversations.isEmpty())
        drafts.update(owner, current)
        assertTrue(image in drafts.references.value.getValue(owner).urls)
        drafts.clear(owner)
        assertTrue(drafts.snapshot().isEmpty())
    }

    @Test
    fun `opened presets protect but are not sent until a user really submits history`() = runBlocking {
        val rig = Rig()
        try {
            val id = Uuid.random()
            val preset = Conversation.ofId(id).updateCurrentMessages(listOf(message()))
            val session = rig.manager.getOrCreate(id)
            session.initialize(load = { null }, create = { preset })
            assertTrue(session.attachmentReferenceSnapshot().sent.sentUrls.isEmpty())
            assertTrue(image in session.attachmentReferenceSnapshot().protectedUrls)
            session.updateSubmittedHistory { it.updateCurrentMessages(it.currentMessages + UIMessage.user("send")) }
            assertTrue(image in session.attachmentReferenceSnapshot().sent.sentUrls)
            assertTrue(rig.store.writes.isEmpty())
        } finally {
            rig.close()
        }
    }

    @Test
    fun `unloaded placeholder never assigns queued files to unrelated selected assistant`() = runBlocking {
        val rig = Rig()
        try {
            val id = Uuid.random()
            val actualAssistant = Uuid.random()
            val session = rig.manager.getOrCreate(id)
            session.inbox.enqueue(parts())
            val before = session.attachmentReferenceSnapshot()
            assertFalse(before.sent.historyComplete)
            assertTrue(before.sent.sentUrls.isEmpty())
            assertTrue(image in before.protectedUrls)
            session.initialize(load = { Conversation.ofId(id, assistantId = actualAssistant) })
            assertEquals(actualAssistant, session.attachmentReferenceSnapshot().sent.assistantId)
            assertTrue(image in session.attachmentReferenceSnapshot().sent.sentUrls)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `all branches and typed nested tool outputs count but long strings and tool input do not`() = runBlocking {
        val typed = "file:///data/user/0/test.app/files/upload/tool.png"
        val fake = "file:///data/user/0/test.app/files/upload/not-an-attachment.png"
        val tool = UIMessagePart.Tool(
            toolCallId = "outer", toolName = "tool", input = fake,
            output = listOf(UIMessagePart.Tool(
                toolCallId = "inner", toolName = "tool", input = fake, output = parts(typed),
            )),
        )
        val conversation = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(
            MessageNode(messages = listOf(
                UIMessage.user("plain text $fake".repeat(1000)),
                message(),
                UIMessage.assistant("").copy(parts = listOf(tool, UIMessagePart.Reasoning(fake))),
            )),
        ))
        val cache = SessionAttachmentReferenceCache()
        val snapshot = cache.snapshot(conversation, true, true, InboxState(), null)
        assertEquals(setOf(image, typed), snapshot.sent.sentUrls)
        assertFalse(fake in snapshot.protectedUrls)
        // Reuse the exact same immutable node: the cache returns the same URL content without scanning strings.
        assertEquals(snapshot, cache.snapshot(conversation.copy(title = "new title"), true, true, InboxState(), null))
    }

    @Test
    fun `shared image keeps both conversations then move and removal update runtime overlay`() = runBlocking {
        val rig = Rig()
        try {
            val a = Uuid.random()
            val b = Uuid.random()
            val moved = Uuid.random()
            val firstId = Uuid.random()
            val secondId = Uuid.random()
            val first = rig.manager.getOrCreate(firstId)
            val second = rig.manager.getOrCreate(secondId)
            first.initialize(load = { Conversation.ofId(firstId, assistantId = a).updateCurrentMessages(listOf(message())) })
            second.initialize(load = { Conversation.ofId(secondId, assistantId = b).updateCurrentMessages(listOf(message())) })
            val shared = withTimeout(5_000) { rig.flow.first { it.conversations.size == 2 } }
            assertEquals(setOf(a, b), shared.conversations.values.map { it.assistantId }.toSet())
            assertTrue(shared.conversations.values.all { image in it.sentUrls })
            first.update { it.copy(assistantId = moved) }
            val afterMove = withTimeout(5_000) { rig.flow.first { it.conversations[firstId]?.assistantId == moved } }
            assertTrue(firstId in afterMove.completeConversationIds)
            assertFalse(a in afterMove.conversations.values.map { it.assistantId })
            rig.manager.removeIfIdle(second)
            val afterRemoval = withTimeout(5_000) { rig.flow.first { secondId !in it.conversations } }
            assertTrue(image in afterRemoval.protectedUrls)
            assertEquals(setOf(firstId), afterRemoval.conversations.keys)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `live observer refreshes insert queue handoff assistant move draft deletion and removal`() = runBlocking {
        val rig = Rig()
        val events = Channel<RuntimeAttachmentReferences>(Channel.UNLIMITED)
        val collector = launch { rig.flow.collect { events.send(it) } }
        suspend fun nextMatching(predicate: (RuntimeAttachmentReferences) -> Boolean): RuntimeAttachmentReferences =
            withTimeout(5_000) {
                var next = events.receive()
                while (!predicate(next)) next = events.receive()
                next
            }
        try {
            nextMatching { it.conversations.isEmpty() }
            val id = Uuid.random()
            val session = rig.manager.getOrCreate(id)
            session.initialize(load = { null }, create = { Conversation.ofId(id) })
            nextMatching { it.conversations[id]?.historyComplete == true }
            session.inbox.enqueue(parts())
            nextMatching { image in it.conversations[id]?.sentUrls.orEmpty() }
            synchronized(session) {
                session.submittingMessage = (session.inbox.takeNext() as InboxTurn.Message).message
            }
            session.updateSubmittedHistory { it.updateCurrentMessages(listOf(message())) }
            session.submittingMessage = null
            val nextAssistant = Uuid.random()
            session.update { it.copy(assistantId = nextAssistant) }
            val moved = nextMatching {
                // Once observed as queued, no intermediate handoff snapshot may drop the same sent image.
                assertTrue(image in it.conversations.getValue(id).sentUrls)
                it.conversations[id]?.assistantId == nextAssistant
            }
            assertEquals(setOf(image), moved.protectedUrls)
            val draftOwner = Uuid.random()
            val draftImage = "file:///data/user/0/test.app/files/upload/draft.png"
            rig.drafts.update(draftOwner, parts(draftImage))
            val withDraft = nextMatching { draftImage in it.protectedUrls }
            assertFalse(draftImage in withDraft.conversations.getValue(id).sentUrls)
            session.replace(Conversation.ofId(id), existsInDatabase = false) {}
            nextMatching { it.conversations[id]?.sentUrls?.isEmpty() == true }
            rig.manager.removeIfIdle(session)
            nextMatching { id !in it.conversations }
            rig.drafts.clear(draftOwner)
            nextMatching { it.protectedUrls.isEmpty() }
            assertTrue(rig.store.writes.isEmpty())
        } finally {
            collector.cancel()
            events.cancel()
            rig.close()
        }
    }

    private fun structuredImage(url: String): JsonObject = JsonObject(mapOf(
        "type" to JsonPrimitive("image"), "url" to JsonPrimitive(url),
    ))

    @Suppress("DEPRECATION")
    @Test
    fun `server and legacy tool output part arrays count but input metadata and text never count`() = runBlocking {
        val fake = "file:///data/user/0/test.app/files/upload/fake.png"
        val legacy = "file:///data/user/0/test.app/files/upload/legacy.png"
        val fakeArray = JsonArray(listOf(structuredImage(fake)))
        val metadata = JsonObject(mapOf("attachment" to structuredImage(fake)))
        val output = JsonArray(listOf(
            structuredImage(image),
            JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(fake))),
            JsonObject(mapOf("type" to JsonPrimitive("tool"), "input" to fakeArray, "metadata" to metadata,
                "output" to JsonArray(listOf(structuredImage(legacy))))),
        ))
        val parts = listOf(
            UIMessagePart.ServerTool("server", "search", input = fakeArray, output = output,
                status = ServerToolStatus.COMPLETED, metadata = metadata),
            UIMessagePart.ToolResult("legacy", "tool", content = JsonArray(listOf(structuredImage(legacy))),
                arguments = fakeArray, metadata = metadata),
            UIMessagePart.Image(image, metadata = metadata),
            UIMessagePart.Text(fake), UIMessagePart.Reasoning(fake),
        )
        val references = collectRuntimeAttachmentReferences(parts.asSequence())
        assertTrue(references.complete)
        assertEquals(setOf(image, legacy), references.urls)
        assertFalse(fake in references.urls)
        // Keep runtime semantics aligned with the existing durable/live typed collector for these schemas.
        val live = collectLiveFileReferences(parts.asSequence())
        assertTrue(live.complete)
        assertEquals(live.paths, references.urls.mapNotNull(::normalizeLocalFileReference).toSet())
    }

    @Test
    fun `truncated depth and invalid file URI mark runtime and conversation incomplete and retain files`() {
        var nested: List<UIMessagePart> = parts()
        repeat(MAX_RUNTIME_ATTACHMENT_DEPTH + 1) {
            nested = listOf(UIMessagePart.Tool("nested-$it", "tool", "ignored", output = nested))
        }
        val id = Uuid.random()
        val conversation = Conversation.ofId(id).copy(messageNodes = listOf(
            MessageNode(messages = listOf(UIMessage.assistant("").copy(parts = nested))),
        ))
        val session = SessionAttachmentReferenceCache().snapshot(conversation, true, true, InboxState(), null)
        assertFalse(session.referencesComplete)
        assertFalse(session.sent.referencesComplete)
        val runtime = mergeRuntimeAttachmentReferences(mapOf(id to session), emptyList(), AttachmentUrlReferences())
        assertFalse(runtime.complete)
        assertFalse(id in runtime.completeConversationIds)
        assertTrue(runtime.isLocalFileReferenced(File("/data/user/0/test.app/files"), "upload/possibly-truncated.png"))
        val invalid = collectRuntimeAttachmentReferences(parts("file:///broken%ZZ/path").asSequence())
        assertFalse(invalid.complete)
        assertTrue(invalid.urls.isEmpty())
        val malformed = collectRuntimeAttachmentReferences(sequenceOf(UIMessagePart.ServerTool(
            "bad", "tool", output = JsonArray(listOf(JsonObject(mapOf(
                "type" to JsonPrimitive("image"), "url" to JsonPrimitive(7),
            )))), status = ServerToolStatus.COMPLETED,
        )))
        assertFalse(malformed.complete)
    }

    @Test
    fun `part and URL budgets propagate incompleteness for drafts and presets`() {
        val tooManyParts = collectRuntimeAttachmentReferences(sequence {
            repeat(MAX_RUNTIME_ATTACHMENT_PARTS + 1) { yield(UIMessagePart.Text("not scanned")) }
        })
        assertFalse(tooManyParts.complete)
        val tooManyUrls = collectRuntimeAttachmentReferences(sequence {
            repeat(MAX_RUNTIME_ATTACHMENT_URLS + 1) {
                yield(UIMessagePart.Image("file:///data/user/0/test.app/files/upload/$it.png"))
            }
        })
        assertFalse(tooManyUrls.complete)
        assertEquals(MAX_RUNTIME_ATTACHMENT_URLS, tooManyUrls.urls.size)
        val drafts = DraftAttachmentReferenceSources()
        val owner = Uuid.random()
        drafts.update(owner, parts("file:///bad%XX/image.png"))
        assertFalse(mergeRuntimeAttachmentReferences(emptyMap(), drafts.snapshot(), AttachmentUrlReferences()).complete)
        assertFalse(mergeRuntimeAttachmentReferences(emptyMap(), emptyList(), tooManyUrls).complete)
    }

    @Test
    fun `structured tool nesting shares the same depth budget as typed nesting`() {
        var output: JsonElement = JsonArray(listOf(structuredImage(image)))
        repeat(MAX_RUNTIME_ATTACHMENT_DEPTH + 1) {
            output = JsonArray(listOf(JsonObject(mapOf(
                "type" to JsonPrimitive("server_tool"), "output" to output,
            ))))
        }
        val result = collectRuntimeAttachmentReferences(sequenceOf(UIMessagePart.ServerTool(
            "deep", "tool", output = output, status = ServerToolStatus.COMPLETED,
        )))
        assertFalse(result.complete)
    }

    @Test
    fun `file protection uses exact decoded private path and Android alias rather than basename`() {
        val snapshot = RuntimeAttachmentReferences(protectedUrls = setOf(image))
        val root = File("/data/data/test.app/files")
        assertTrue(snapshot.isLocalFileReferenced(root, "upload/shared image.png"))
        assertFalse(snapshot.isLocalFileReferenced(root, "avatars/shared image.png"))
        assertFalse(snapshot.isLocalFileReferenced(root, "../upload/shared image.png"))
        assertFalse(snapshot.isLocalFileReferenced(root, "/upload/shared image.png"))
    }
}
