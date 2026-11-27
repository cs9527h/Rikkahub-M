package me.rerere.rikkahub.data.db.dao

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.files.CatalogFile
import me.rerere.rikkahub.data.files.LiveConversationFileReferences
import me.rerere.rikkahub.data.files.collectLiveFileReferences
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.service.ConversationInbox
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises Room's compiled projection/joins and live invalidation, not a fake SQL implementation. */
@RunWith(AndroidJUnit4::class)
class ManagedFileCatalogDAOTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: ManagedFileDAO
    private val root = File("/data/user/0/catalog.test/files")
    private val file = ManagedFileEntity(
        folder = "upload",
        relativePath = "upload/shared.png",
        displayName = "shared.png",
        mimeType = "image/png",
        sizeBytes = 10,
        createdAt = 0,
        updatedAt = 0,
    )

    // Exactly the JsonInstant List<UIMessage> encoding persisted by saveMessageNodes, not a
    // hand-written approximation of the sealed-part discriminator or default fields.
    private val branchMessages = JsonInstant.encodeToString(listOf(
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("selected branch without attachment"))),
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Tool(
            toolCallId = "call",
            toolName = "attachment",
            input = "{}",
            output = listOf(UIMessagePart.Image(Uri.fromFile(
                File("/data/data/catalog.test/files", file.relativePath),
            ).toString())),
        ))),
    ))

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).openHelperFactory(RequerySQLiteOpenHelperFactory()).build()
        dao = database.managedFileDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun keysetProjectionVisitsEveryNodeAndReadsCurrentConversationOwner() = runBlocking {
        database.conversationDao().insert(conversation("one", "a"))
        repeat(35) { index ->
            database.messageNodeDao().insert(MessageNodeEntity("node-$index", "one", index, branchMessages, 0))
        }
        val references = mutableListOf<ManagedFileCatalogReference>()
        var after = Long.MIN_VALUE
        while (true) {
            val page = dao.catalogReferences(after, 16)
            if (page.isEmpty()) break
            assertTrue(page.size <= 16)
            assertTrue(page.first().rowId > after)
            references.addAll(page)
            after = page.last().rowId
        }
        assertEquals(35, references.size)
        assertEquals(35, references.map { it.rowId }.toSet().size)
        assertTrue(references.all { it.assistantId == "a" && it.messages == branchMessages })
        database.conversationDao().updateAssistantId("one", "b")
        assertTrue(dao.catalogReferences(Long.MIN_VALUE, 16).all { it.assistantId == "b" })
        database.conversationDao().deleteById("one")
        assertTrue(dao.catalogReferences(Long.MIN_VALUE, 16).isEmpty())
    }

    @Test
    fun oversizedNodesAreNotTransferredOrReportedAsUnassigned() = runBlocking {
        dao.insert(file)
        database.conversationDao().insert(conversation("one", "a"))
        database.messageNodeDao().insert(MessageNodeEntity("large", "one", 0, "x".repeat(262_145), 0))
        assertEquals(null, dao.catalogReferences(Long.MIN_VALUE, 16).single().messages)
        val files = withTimeout(5_000) { FilesRepository(dao).observeCatalog("upload", root).first() }
        assertEquals(1, files.size)
        assertTrue(!files.single().ownershipComplete)
        assertTrue(!files.single().isUnassigned)
    }

    @Test
    fun oversizedProductionNodeIsAttributedThroughBoundedUnicodeChunks() = runBlocking {
        dao.insert(file)
        database.conversationDao().insert(conversation("one", "a"))
        val encoded = JsonInstant.encodeToString(listOf(UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text("😀中文 ".repeat(80_000)),
                UIMessagePart.Image(Uri.fromFile(File(root, file.relativePath)).toString()),
            ),
        )))
        database.messageNodeDao().insert(MessageNodeEntity("large-valid", "one", 0, encoded, 0))
        val reference = dao.catalogReferences(Long.MIN_VALUE, 16).single()
        assertEquals(null, reference.messages) // no oversized CursorWindow transfer
        assertEquals(encoded.codePointCount(0, encoded.length).toLong(), reference.messageChars)
        assertTrue(dao.catalogReferenceChunk(reference.rowId, 1, 32_768)!!.length <= 65_536)
        val result = withTimeout(10_000) { FilesRepository(dao).observeCatalog("upload", root).first() }.single()
        assertEquals(setOf("a"), result.assistantIds)
        assertTrue(result.ownershipComplete)
    }

    @Test
    fun filesInsertedBeforeMessagesAcquireOwnerWithoutLeavingThePage() = runBlocking {
        dao.insert(file)
        database.conversationDao().insert(conversation("one", "a"))
        val emissions = Channel<List<CatalogFile>>(Channel.UNLIMITED)
        val collection = launch {
            FilesRepository(dao).observeCatalog("upload", root).collect { emissions.send(it) }
        }
        try {
            awaitOwners(emissions, emptySet())
            database.messageNodeDao().insert(MessageNodeEntity("sent", "one", 0, branchMessages, 0))
            awaitOwners(emissions, setOf("a"))
            // Same row count and same primary key: Room must still invalidate, not just count rows.
            database.messageNodeDao().insert(MessageNodeEntity(
                "sent",
                "one",
                0,
                JsonInstant.encodeToString(listOf(UIMessage.user("attachment removed"))),
                0,
            ))
            awaitOwners(emissions, emptySet())
        } finally {
            collection.cancelAndJoin()
            emissions.close()
        }
    }

    @Test
    fun acceptedInboxAttachmentUpdatesWithNoMessageNodeWriteAndSurvivesFlushHandoff() = runBlocking {
        dao.insert(file)
        database.conversationDao().insert(conversation("one", "a"))
        val inbox = ConversationInbox()
        val live = MutableStateFlow<List<LiveConversationFileReferences>>(emptyList())
        val emissions = Channel<List<CatalogFile>>(Channel.UNLIMITED)
        val collection = launch {
            FilesRepository(dao).observeCatalog("upload", root, live).collect { emissions.send(it) }
        }
        try {
            awaitOwners(emissions, emptySet()) // An imported/editor-only file is not sent evidence.
            val parts = listOf(UIMessagePart.Image(Uri.fromFile(File(root, file.relativePath)).toString()))
            inbox.enqueue(parts)
            val references =
                collectLiveFileReferences(inbox.state.value.messages.asSequence().flatMap { it.parts.asSequence() })
            live.value = listOf(LiveConversationFileReferences("one", "a", references.paths, true))
            assertTrue(dao.catalogReferences(Long.MIN_VALUE, 16).isEmpty())
            awaitOwners(emissions, setOf("a")) // No Room invalidation: the accepted queue is the evidence.
            database.messageNodeDao().insert(MessageNodeEntity(
                "dispatched",
                "one",
                0,
                JsonInstant.encodeToString(listOf(UIMessage(role = MessageRole.USER, parts = parts))),
                0,
            ))
            live.value = emptyList() // Session eviction after a successful flush; persisted owner remains.
            awaitOwners(emissions, setOf("a"))
            database.messageNodeDao().deleteById("dispatched")
            awaitOwners(emissions, emptySet())
        } finally {
            collection.cancelAndJoin()
            emissions.close()
        }
    }

    @Test
    fun androidFileUriEncodingMatchesPrivateRootAliasWithUnicodeAndReservedCharacters() = runBlocking {
        val encodedFile = file.copy(relativePath = "upload/a b中文😀#?.png")
        dao.insert(encodedFile)
        database.conversationDao().insert(conversation("one", "a"))
        database.messageNodeDao().insert(MessageNodeEntity(
            "encoded",
            "one",
            0,
            JsonInstant.encodeToString(listOf(UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Image(Uri.fromFile(File(
                    "/data/data/catalog.test/files",
                    encodedFile.relativePath,
                )).toString())),
            ))),
            0,
        ))
        val result = withTimeout(5_000) { FilesRepository(dao).observeCatalog("upload", root).first() }.single()
        assertEquals(setOf("a"), result.assistantIds)
        assertTrue(result.ownershipComplete)
    }

    @Test
    fun observationTracksUnselectedBranchSharingMovesDeletionAndFileRemoval() = runBlocking {
        dao.insert(file)
        database.conversationDao().insert(conversation("one", "a"))
        database.conversationDao().insert(conversation("two", "b"))
        database.messageNodeDao().insert(MessageNodeEntity("node-one", "one", 0, branchMessages, 0))
        database.messageNodeDao().insert(MessageNodeEntity("node-two", "two", 0, branchMessages, 0))
        val emissions = Channel<List<CatalogFile>>(Channel.UNLIMITED)
        val collection = launch {
            FilesRepository(dao).observeCatalog("upload", root).collect { emissions.send(it) }
        }
        try {
            awaitOwners(emissions, setOf("a", "b"))
            // Row count is unchanged; this must still invalidate ownership, not be distinctUntilChanged(count).
            database.conversationDao().updateAssistantId("one", "c")
            awaitOwners(emissions, setOf("b", "c"))
            database.messageNodeDao().deleteById("node-two")
            awaitOwners(emissions, setOf("c"))
            database.conversationDao().deleteById("one")
            awaitOwners(emissions, emptySet())
            dao.deleteByPath(file.relativePath)
            withTimeout(5_000) {
                while (emissions.receive().isNotEmpty()) { /* Drain earlier emissions. */ }
            }
        } finally {
            collection.cancelAndJoin()
            emissions.close()
        }
    }

    private suspend fun awaitOwners(channel: Channel<List<CatalogFile>>, expected: Set<String>) {
        withTimeout(5_000) {
            while (true) {
                val files = channel.receive()
                if (files.size == 1 && files.single().assistantIds == expected) break
            }
        }
    }

    private fun conversation(id: String, assistantId: String) = ConversationEntity(
        id = id,
        assistantId = assistantId,
        title = "Catalog test",
        nodes = "[]",
        createAt = 0,
        updateAt = 0,
        chatSuggestions = "[]",
        isPinned = false,
    )
}
