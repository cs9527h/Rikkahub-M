package me.rerere.rikkahub.ui.pages.setting

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.ManagedFileCatalogReference
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.CatalogFile
import me.rerere.rikkahub.data.files.ManagedFileCatalog
import me.rerere.rikkahub.data.files.ManagedFileType
import me.rerere.rikkahub.service.RuntimeAttachmentReferences
import me.rerere.rikkahub.service.RuntimeConversationAttachmentReferences
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

class StorageAreasTest {
    private fun file(id: Long, folder: String = "upload") = CatalogFile(
        ManagedFileEntity(id, folder, "$folder/$id.png", "$id.png", "image/png", 10, id, id),
        emptySet(),
    )

    @Test fun `current legacy avatars are separated before migration without inventing chat owners`() {
        val files = listOf(file(1), file(2), file(3, "avatars"))
        assertEquals(listOf(2L), storageAreaFiles(files, STORAGE_AREA_CHAT, setOf("upload/1.png")).map { it.entity.id })
        val avatars = storageAreaFiles(files, STORAGE_AREA_AVATARS, setOf("upload/1.png"))
        assertEquals(listOf(1L, 3L), avatars.map { it.entity.id })
        assertTrue(avatars.all { it.assistantIds.isEmpty() })
    }

    @Test fun `ordinary unassociated uploads stay in chat storage`() {
        val files = listOf(file(1), file(2, "avatars"))
        val chat = storageAreaFiles(files, STORAGE_AREA_CHAT, emptySet())
        assertEquals(listOf(1L), chat.map { it.entity.id })
        assertTrue(chat.single().isUnassigned)
    }

    @Test fun `avatar filters use profile references including the user and shared image`() {
        val files = (1L..4L).map { file(it, "avatars") }
        val owners = mapOf(
            "avatars/1.png" to setOf("assistant:a"),
            "avatars/2.png" to setOf("user"),
            "avatars/3.png" to setOf("user", "assistant:a")
        )
        fun ids(
            key: String,
            query: String = ""
        ) = filterAvatarFiles(
            files,
            owners,
            ManagedFileType.IMAGE,
            key,
            query,
            FileManagerSort.NEWEST
        ).map { it.entity.id }
        assertEquals(listOf(3L, 2L), ids("user"))
        assertEquals(listOf(3L, 1L), ids("assistant:a"))
        assertEquals(listOf(3L), ids("shared"))
        assertEquals(listOf(4L), ids("unassigned"))
        assertEquals(listOf(2L), ids("all", " 2.PNG "))
    }

    @Test fun `runtime bridge never attributes unsent draft protection to an assistant`() {
        val snapshot =
            RuntimeAttachmentReferences(protectedUrls = setOf("file:///data/user/0/app/files/upload/draft.png"))
        assertTrue(snapshot.catalogReferences().isEmpty())
    }

    @Test fun `incomplete runtime snapshot cannot replace persisted ownership`() {
        val snapshot = RuntimeAttachmentReferences(mapOf(Uuid.random() to RuntimeConversationAttachmentReferences(
            Uuid.random(),
            setOf("file:///data/data/app/files/upload/a.png"),
            true,
            referencesComplete = false,
        )))
        val reference = snapshot.catalogReferences().single()
        assertFalse(reference.replacesPersistedHistory)
        assertFalse(reference.complete)
        assertEquals(setOf("/data/user/0/app/files/upload/a.png"), reference.paths)
    }

    @Test fun `actual runtime bridge replaces old database assistant without fake sharing`() = runTest {
        val conversationId = Uuid.random()
        val oldAssistant = Uuid.random()
        val newAssistant = Uuid.random()
        val asset = file(1)
        val url = "file:///data/user/0/app/files/upload/1.png"
        val encoded = JsonInstant.encodeToString(
            listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image(url))))
        )
        val row =
            ManagedFileCatalogReference(
                1,
                oldAssistant.toString(),
                encoded,
                scanEndRowId = 1,
                conversationId = conversationId.toString()
            )
        val catalog = ManagedFileCatalog(File("/data/user/0/app/files"), { _, _ -> listOf(row) })
        val runtime =
            RuntimeAttachmentReferences(
                mapOf(conversationId to RuntimeConversationAttachmentReferences(newAssistant, setOf(url), true))
            )
        val result = catalog.read(listOf(asset.entity), runtime.catalogReferences()).single()
        assertEquals(setOf(newAssistant.toString()), result.assistantIds)
        assertFalse(result.isShared)
        assertTrue(result.ownershipComplete)
    }
}
