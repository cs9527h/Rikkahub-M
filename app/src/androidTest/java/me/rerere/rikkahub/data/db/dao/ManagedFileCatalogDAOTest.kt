package me.rerere.rikkahub.data.db.dao

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.files.CatalogFile
import me.rerere.rikkahub.data.repository.FilesRepository
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
    private val branchMessages = """[
        {"parts":[{"type":"text","text":"selected branch without attachment"}]},
        {"parts":[{"type":"tool","output":[{"type":"image","url":"file:///data/data/catalog.test/files/upload/shared.png"}]}]}
    ]"""

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
