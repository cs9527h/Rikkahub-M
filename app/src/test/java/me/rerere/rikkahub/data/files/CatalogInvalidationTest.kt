package me.rerere.rikkahub.data.files

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.ManagedFileCatalogReference
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogInvalidationTest {
    @Test
    fun equalRoomTokenStillInvalidatesOwnershipButDeletionReusesIt() = runTest {
        val image = file(1, "a.png")
        val extra = file(2, "b.png")
        val dao = SourceDao(listOf(image, extra))
        assertTrue(dao.references.tryEmit(2L))
        var assistant = "a"
        var url = "file:///data/user/0/test/files/${image.relativePath}"
        var reads = 0
        val catalog = ManagedFileCatalog(
            File("/data/user/0/test/files"),
            readReferences = { _, _ ->
                reads++
                listOf(ManagedFileCatalogReference(
                    1,
                    assistant,
                    JsonInstant.encodeToString(
                        listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image(url))))
                    ),
                    scanEndRowId = 1,
                ))
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val emitted = mutableListOf<List<CatalogFile>>()
        backgroundScope.launch { catalog.observe(dao, "upload", flowOf(emptyList())).collect { emitted.add(it) } }
        runCurrent()
        assertEquals(1, reads)
        assertEquals(setOf("a"), emitted.last().first { it.entity.id == image.id }.assistantIds)

        dao.files.value = listOf(image)
        runCurrent()
        assertEquals("Deleting an indexed file must not parse conversations again", 1, reads)
        assertEquals(1, emitted.last().size)

        // EXISTS(message_node) + EXISTS(ConversationEntity) stays 2 after a move.
        assistant = "b"
        dao.references.emit(2L)
        runCurrent()
        assertEquals(2, reads)
        assertEquals(setOf("b"), emitted.last().single().assistantIds)

        // An in-place message edit also leaves the cheap SQL token unchanged.
        url = "https://example.test/remote.png"
        dao.references.emit(2L)
        runCurrent()
        assertEquals(3, reads)
        assertTrue(emitted.last().single().isUnassigned)
    }

    private fun file(id: Long, name: String) = ManagedFileEntity(
        id,
        "upload",
        "upload/$name",
        name,
        "image/png",
        1,
        0,
        0
    )

    private class SourceDao(initial: List<ManagedFileEntity>) : ManagedFileDAO {
        val files = MutableStateFlow(initial)
        val references = MutableSharedFlow<Long>(replay = 1)
        override fun listByFolder(folder: String): Flow<List<ManagedFileEntity>> = files
        override fun observeCatalogReferences(): Flow<Long> = references
        override suspend fun insert(file: ManagedFileEntity): Long = error("Not used")
        override suspend fun getById(id: Long): ManagedFileEntity? = error("Not used")
        override suspend fun getByPath(relativePath: String): ManagedFileEntity? = error("Not used")
        override suspend fun catalogReferences(
            afterRowId: Long,
            limit: Int
        ): List<ManagedFileCatalogReference> = error("Not used")
        override suspend fun catalogReferenceChunk(rowId: Long, start: Long, length: Int): String? = error("Not used")
        override suspend fun deleteById(id: Long): Int = error("Not used")
        override suspend fun deleteByPath(relativePath: String): Int = error("Not used")
        override suspend fun deleteByFolder(folder: String): Int = error("Not used")
    }
}
