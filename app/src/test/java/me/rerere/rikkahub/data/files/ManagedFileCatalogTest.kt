package me.rerere.rikkahub.data.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.ManagedFileCatalogReference
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ManagedFileCatalogTest {
    private val root = File("/data/user/0/test.app/files")
    private val image = entity("upload/uuid.png", "photo.png", "image/png")
    private val document = entity("upload/notes.txt", "notes.txt", "text/plain")

    @Test
    fun allBranchesAndNestedToolAttachmentsHaveOwnersAndSharedIsNotDuplicated() = runTest {
        val index = ManagedFileCatalogIndex(root, listOf(image, document))
        index.add("a", messages(
            parts(attachment("image", uri(image))),
            parts(tool(parts(tool(parts(attachment("document", uri(document))))))),
        ))
        index.add("b", messages(parts(attachment("image", uri(image)))))
        index.add("a", messages(parts(attachment("image", uri(image)))))
        val result = index.files()
        assertEquals(setOf("a", "b"), result[0].assistantIds)
        assertEquals(setOf("a"), result[1].assistantIds)
        assertEquals(2, result.size)
    }

    @Test
    fun samePackageAndroidAliasesAndPercentEncodedPathsMatchExactly() = runTest {
        val encoded = entity("upload/a b中文.txt", "document", "text/plain")
        val index = ManagedFileCatalogIndex(root, listOf(encoded))
        val historical = File("/data/data/test.app/files", encoded.relativePath).toURI().toASCIIString()
        index.add("historic", messages(parts(attachment("document", historical))))
        assertEquals(setOf("historic"), index.files().single().assistantIds)
        val reverse = ManagedFileCatalogIndex(File("/data/data/test.app/files"), listOf(encoded))
        reverse.add("current", messages(parts(attachment("document", uri(encoded)))))
        assertEquals(setOf("current"), reverse.files().single().assistantIds)
    }

    @Test
    fun basenamesOutsideFilesDirAndOtherUsersNeverBecomeOwners() = runTest {
        val index = ManagedFileCatalogIndex(root, listOf(image))
        val urls = listOf(
            "file:///sdcard/Download/uuid.png",
            "file:///data/user/0/other.app/files/upload/uuid.png",
            "file:///data/user/10/test.app/files/upload/uuid.png",
            "file:///data/user/0/test.app/cache/upload/uuid.png",
            "file:///data/user/0/test.app/files/upload-other/uuid.png",
            "file:///data/user/0/test.app/files/upload/../uuid.png",
            "https://example.com/upload/uuid.png",
            "content://example/upload/uuid.png",
            "file://remote/data/user/0/test.app/files/upload/uuid.png",
            "file:///data/user/0/test.app/files/upload/uuid.png?other",
            "file:///data/user/0/test.app/files/upload/uuid.png#other",
            "file:upload/uuid.png",
            "file:///broken%URL",
        )
        for (url in urls) index.add("wrong", messages(parts(attachment("image", url))))
        assertTrue(index.files().single().assistantIds.isEmpty())
    }

    @Test
    fun ordinaryTextToolInputsMetadataAndUnknownTypesAreNotReferences() = runTest {
        val url = uri(image)
        val index = ManagedFileCatalogIndex(root, listOf(image))
        val text = JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(url)))
        val input = JsonObject(mapOf("type" to JsonPrimitive("tool"), "input" to JsonPrimitive(url)))
        val unknown = attachment("future_unknown", url)
        val metadata = JsonObject(mapOf("type" to JsonPrimitive("text"), "metadata" to attachment("image", url)))
        index.add("wrong", messages(parts(text, input, unknown, metadata)))
        index.add("", messages(parts(attachment("image", url))))
        index.add("wrong", "not JSON")
        index.add("wrong", "{}")
        assertTrue(index.files().single().assistantIds.isEmpty())
    }

    @Test
    fun videoAudioLegacyAndServerStructuredToolOutputsAreReferences() = runTest {
        val video = entity("upload/movie.mp4", "movie.mp4", "video/mp4")
        val audio = entity("upload/sound.ogg", "sound.ogg", "audio/ogg")
        val index = ManagedFileCatalogIndex(root, listOf(video, audio))
        val serverTool = JsonObject(mapOf(
            "type" to JsonPrimitive("server_tool"),
            "output" to parts(attachment("video", uri(video)))
        ))
        val legacy = JsonObject(mapOf(
            "type" to JsonPrimitive("tool_result"),
            "content" to parts(attachment("audio", uri(audio)))
        ))
        index.add("a", messages(parts(serverTool, legacy)))
        assertTrue(index.files().all { it.assistantIds == setOf("a") })
    }

    @Test
    fun malformedOversizedAndDeepNodesProtectUnknownRatherThanMarkingUnassigned() = runTest {
        for (bad in listOf(
            null,
            "{",
            "{}",
            "x".repeat(ManagedFileCatalogIndex.MAX_MESSAGE_CHARS + 1),
            "[".repeat(129)
        )) {
            val index = ManagedFileCatalogIndex(root, listOf(image, document))
            index.add("known", messages(parts(attachment("image", uri(image)))))
            index.add("unknown", bad)
            val files = index.files()
            assertEquals(setOf("known"), files.first().assistantIds)
            assertTrue(files.all { !it.ownershipComplete && !it.isUnassigned })
            assertTrue(files.filterCatalog(assistant = CatalogAssistantFilter.Unassigned).isEmpty())
            assertEquals(files, files.filterCatalog(assistant = CatalogAssistantFilter.Unknown))
            assertEquals(
                listOf(files.first()),
                files.filterCatalog(assistant = CatalogAssistantFilter.Assistant("known"))
            )
        }
    }

    @Test
    fun firstPageUpperBoundStopsScanningDespiteContinuousNewNodes() = runTest {
        var calls = 0
        val catalog = ManagedFileCatalog(root, { after, _ ->
            calls++
            (after.coerceAtLeast(0) + 1..after.coerceAtLeast(0) + 16).map { row ->
                ManagedFileCatalogReference(row, "a", "[]", scanEndRowId = 20)
            }
        }, StandardTestDispatcher(testScheduler))
        assertTrue(catalog.read(listOf(image)).single().isUnassigned)
        assertEquals(2, calls)
    }

    @Test
    fun everyFilterCombinesWithTypeAndSharedAppearsUnderEachOwner() {
        val files = listOf(
            CatalogFile(image, emptySet()),
            CatalogFile(document, setOf("a")),
            CatalogFile(image.copy(id = 3), setOf("a", "b")),
        )
        assertEquals(files, files.filterCatalog())
        assertEquals(listOf(files[0]), files.filterCatalog(assistant = CatalogAssistantFilter.Unassigned))
        assertEquals(listOf(files[2]), files.filterCatalog(assistant = CatalogAssistantFilter.Shared))
        assertEquals(listOf(files[1], files[2]), files.filterCatalog(assistant = CatalogAssistantFilter.Assistant("a")))
        assertEquals(listOf(files[2]), files.filterCatalog(assistant = CatalogAssistantFilter.Assistant("b")))
        assertTrue(files.filterCatalog(assistant = CatalogAssistantFilter.Assistant("missing")).isEmpty())
        assertEquals(listOf(files[2]), files.filterCatalog(ManagedFileType.IMAGE, CatalogAssistantFilter.Shared))
        assertTrue(files.filterCatalog(ManagedFileType.DOCUMENT, CatalogAssistantFilter.Unassigned).isEmpty())
    }

    @Test
    fun classificationUsesMimeBeforeExtensionAndHandlesHistoricGenericMime() {
        val cases = mapOf(
            "image/jpeg" to ManagedFileType.IMAGE,
            "video/webm" to ManagedFileType.VIDEO,
            "audio/ogg" to ManagedFileType.AUDIO,
            "text/plain; charset=utf-8" to ManagedFileType.DOCUMENT,
            "application/pdf" to ManagedFileType.DOCUMENT,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to ManagedFileType.DOCUMENT,
            "application/vnd.oasis.opendocument.text" to ManagedFileType.DOCUMENT,
            "application/zip" to ManagedFileType.OTHER,
        )
        cases.forEach { (mime, type) -> assertEquals(type, managedFileType(image.copy(mimeType = mime))) }
        mapOf(
            "PNG" to ManagedFileType.IMAGE,
            "mp4" to ManagedFileType.VIDEO,
            "mp3" to ManagedFileType.AUDIO,
            "pdf" to ManagedFileType.DOCUMENT,
            "bin" to ManagedFileType.OTHER,
        ).forEach { (ext, type) ->
            assertEquals(
                type,
                managedFileType(image.copy(displayName = "file.$ext", mimeType = "application/octet-stream"))
            )
        }
    }

    @Test
    fun pagingIsOnePassIndependentOfFileCountAndNewReadDropsDeletedOwners() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var calls = 0
        var references = (1L..35L).map { id ->
            ManagedFileCatalogReference(id, if (id ==
                35L) {
                "b"
            } else {
                "a"
            }, messages(parts(attachment("image", uri(image)))))
        }
        val catalog = ManagedFileCatalog(root, { after, limit ->
            calls++
            assertEquals(ManagedFileCatalog.REFERENCE_PAGE_SIZE, limit)
            references.filter { it.rowId > after }.take(limit)
        }, dispatcher)
        val files = listOf(image) + (1..100).map { image.copy(relativePath = "upload/$it.png") }
        assertEquals(setOf("a", "b"), catalog.read(files).first().assistantIds)
        assertEquals(4, calls) // Three data pages and an empty terminal page, not 101 history scans.
        references = emptyList()
        assertTrue(catalog.read(files).all { it.assistantIds.isEmpty() })
        calls = 0
        assertTrue(catalog.read(emptyList()).isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun cancelledPagingDoesNotSwallowCancellation() = runTest {
        var entered = false
        var cancelled = false
        val catalog = ManagedFileCatalog(root, { _, _ ->
            entered = true
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }, StandardTestDispatcher(testScheduler))
        val work = async { catalog.read(listOf(image)) }
        runCurrent()
        assertTrue(entered)
        work.cancel(CancellationException("changed filters"))
        work.join()
        assertTrue(cancelled)
        assertTrue(work.isCancelled)
    }

    @Test
    fun invalidManagedRelativePathsNeverParticipate() = runTest {
        val invalid = image.copy(relativePath = "../cache/uuid.png")
        val index = ManagedFileCatalogIndex(root, listOf(invalid))
        index.add("a", messages(parts(attachment("image", "file:///data/user/0/test.app/cache/uuid.png"))))
        assertFalse(index.files().single().assistantIds.isNotEmpty())
    }

    @Test
    fun acceptedRuntimeReferencesSupersedeStalePersistedHistoryWithoutGuessingUnknownOwner() = runTest {
        val persisted = ManagedFileCatalogReference(
            1,
            "old",
            messages(parts(attachment("image", uri(image)))),
            scanEndRowId = 1,
            conversationId = "conversation",
        )
        val catalog = ManagedFileCatalog(root, { after, _ ->
            if (after < 1) listOf(persisted) else emptyList()
        }, StandardTestDispatcher(testScheduler))
        val live = LiveConversationFileReferences(
            conversationId = "conversation",
            assistantId = "new",
            paths = setOf(normalizeLocalFileReference(uri(document))!!),
            replacesPersistedHistory = true,
        )
        val result = catalog.read(listOf(image, document), listOf(live))
        assertTrue(result.first().isUnassigned) // Removed from live history, not attributed to stale DB owner.
        assertEquals(setOf("new"), result.last().assistantIds)
        val unknown = catalog.read(listOf(document), listOf(live.copy(assistantId = null)))
        assertTrue(unknown.single().assistantIds.isEmpty())
        assertFalse(unknown.single().isUnassigned)
    }

    @Test
    fun typedRuntimeAndProductionStoredReferencesAgreeForNestedAttachments() = runTest {
        val parts = listOf<UIMessagePart>(
            UIMessagePart.Text(uri(document)),
            UIMessagePart.Tool(
                toolCallId = "call",
                toolName = "test",
                input = uri(document),
                output = listOf(UIMessagePart.Image(uri(image))),
            ),
        )
        val live = collectLiveFileReferences(parts.asSequence())
        val encoded = JsonInstant.encodeToString(listOf(UIMessage(role = MessageRole.USER, parts = parts)))
        var supplied = false
        val stored = scanMessageFileReferences({ if (supplied) null else encoded.also { supplied = true } })
        assertEquals(stored, live)
        assertEquals(setOf(normalizeLocalFileReference(uri(image))), live.paths)
    }

    @Test
    fun productionSerializationRetainsNestedAttachmentsAcrossEveryChunkBoundary() = runTest {
        val encoded = JsonInstant.encodeToString(listOf(UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text("Not a reference: ${uri(image)} \\\" \\n 中文 😀"),
                UIMessagePart.Tool(
                    toolCallId = "call",
                    toolName = "test",
                    input = uri(document),
                    output = listOf(UIMessagePart.Image(uri(image))),
                ),
                UIMessagePart.Document(uri(document), "notes.txt"),
            ),
        )))
        for (size in listOf(1, 2, 7, 127, encoded.length)) {
            val chunks = encoded.chunked(size).iterator()
            val result = scanMessageFileReferences({ if (chunks.hasNext()) chunks.next() else null })
            assertTrue("chunk size $size", result.complete)
            assertEquals(setOf(
                normalizeLocalFileReference(uri(image)),
                normalizeLocalFileReference(uri(document)),
            ), result.paths)
        }
    }

    @Test
    fun oversizedProductionNodeFindsAttachmentAfterLongTextWithoutCapturingIt() = runTest {
        val encoded = JsonInstant.encodeToString(listOf(UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text("😀x".repeat(200_000)),
                UIMessagePart.Image(uri(image)),
            ),
        )))
        val chunks = encoded.chunked(ManagedFileCatalog.REFERENCE_CHUNK_CHARS).iterator()
        val result = scanMessageFileReferences({ if (chunks.hasNext()) chunks.next() else null })
        assertTrue(result.complete)
        assertEquals(setOf(normalizeLocalFileReference(uri(image))), result.paths)
    }

    @Test
    fun truncatedChunkStreamsNeverProveUnassignedAndCancelledStreamsPropagate() = runTest {
        val chunks = listOf("[{\"parts\":[{\"type\":\"image\",\"url\":\"file:/").iterator()
        val result = scanMessageFileReferences({ if (chunks.hasNext()) chunks.next() else null })
        assertFalse(result.complete)
        assertTrue(result.paths.isEmpty())
        val work = async { scanMessageFileReferences({ awaitCancellation() }) }
        runCurrent()
        work.cancel()
        work.join()
        assertTrue(work.isCancelled)
    }

    private fun entity(path: String, name: String, mime: String) = ManagedFileEntity(
        folder = "upload",
        relativePath = path,
        displayName = name,
        mimeType = mime,
        sizeBytes = 1,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun uri(entity: ManagedFileEntity): String = File(root, entity.relativePath).toURI().toASCIIString()
    private fun attachment(type: String, url: String) = JsonObject(mapOf(
        "type" to JsonPrimitive(type),
        "url" to JsonPrimitive(url)
    ))
    private fun parts(vararg parts: JsonObject) = JsonArray(parts.toList())
    private fun tool(output: JsonArray) = JsonObject(mapOf("type" to JsonPrimitive("tool"), "output" to output))
    private fun messages(vararg branches: JsonArray): String = JsonArray(branches.map {
        JsonObject(mapOf("parts" to it))
    }).toString()
}
