package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.runtime.saveable.SaverScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.CatalogFile
import org.junit.Assert.*
import org.junit.Test

class FileManagerStateTest {
    private fun file(id: Long) = CatalogFile(ManagedFileEntity(id, "upload", "upload/$id.txt", "$id.txt", "text/plain", 10, id, id), emptySet())

    @Test fun `success hides stale row immediately but false and exception preserve rows`() = runBlocking {
        val state = FileManagerState()
        val files = listOf(file(1), file(2), file(3))
        var logged = 0
        val result = state.deleteFiles(files, { when (it.entity.id) { 1L -> true; 2L -> false; else -> throw IOException("disk") } }, { logged++ })
        assertEquals(FileDeletionSummary(1, 2), result)
        assertEquals(setOf(1L), state.removedPaths.map { it.substringAfterLast('/').substringBefore('.').toLong() }.toSet())
        assertEquals(setOf(2L, 3L), state.visibleFiles(files, emptyMap()).map { it.entity.id }.toSet())
        assertEquals(1, logged)
        assertFalse(state.busyDeleting)
    }

    @Test fun `tab switches and stale full catalog never resurrect completed deletes`() = runBlocking {
        val state = FileManagerState()
        val files = listOf(file(1))
        state.deleteFiles(files, { true }, {})
        state.switchArea(STORAGE_AREA_AVATARS)
        state.reconcile(files)
        state.switchArea(STORAGE_AREA_CHAT)
        state.reconcile(files)
        assertTrue(state.visibleFiles(files, emptyMap()).isEmpty())
        state.reconcile(emptyList())
        assertTrue(state.removedPaths.isEmpty())
        // A genuinely reintroduced path after authoritative absence is visible again.
        assertEquals(files, state.visibleFiles(files, emptyMap()))
    }

    @Test fun `batch snapshots deduplicate and reject overlapping deletion`() = runBlocking {
        val state = FileManagerState()
        val gate = CompletableDeferred<Boolean>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { state.deleteFiles(listOf(file(1), file(1)), { gate.await() }, {}) }
        assertTrue(state.busyDeleting)
        assertNull(state.deleteFiles(listOf(file(2)), { error("must not execute") }, {}))
        gate.complete(true)
        job.join()
        assertEquals(setOf("upload/1.txt"), state.removedPaths)
    }

    @Test fun `cancellation keeps earlier batch successes but releases busy and does not hide interrupted row`() = runBlocking {
        val state = FileManagerState()
        try {
            state.deleteFiles(listOf(file(1), file(2)), { if (it.entity.id == 1L) true else throw CancellationException("leave page") }, { error("cancellation is not failure") })
            fail("must propagate cancellation")
        } catch (_: CancellationException) { }
        assertEquals(setOf("upload/1.txt"), state.removedPaths)
        assertFalse(state.busyDeleting)
    }

    @Test fun `save restores filters and successful paths but never destructive confirmation`() = runBlocking {
        val state = FileManagerState().apply {
            query = "report"
            ownerKey = "shared"
            selectedTypeName = "DOCUMENT"
            sort = FileManagerSort.LARGEST
            pendingExportId = 7
            pendingDelete = file(2)
            confirmedCleanup = listOf(file(2))
        }
        state.deleteFiles(listOf(file(1)), { true }, {})
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(FileManagerState.Saver) { scope.save(state) }!!
        val restored = FileManagerState.Saver.restore(saved)!!
        assertEquals("report", restored.query)
        assertEquals("shared", restored.ownerKey)
        assertEquals("DOCUMENT", restored.selectedTypeName)
        assertEquals(FileManagerSort.LARGEST, restored.sort)
        assertEquals(setOf("upload/1.txt"), restored.removedPaths)
        assertEquals(7L, restored.pendingExportId)
        assertNull(restored.pendingDelete)
        assertNull(restored.confirmedCleanup)
        assertFalse(restored.busyDeleting)
    }

    @Test fun `changing area resets incompatible filters not sort or pending SAF identity`() {
        val state = FileManagerState().apply { query = "abc"; ownerKey = "unknown"; selectedTypeName = "VIDEO"; sort = FileManagerSort.NAME; pendingExportId = 1 }
        state.switchArea(STORAGE_AREA_AVATARS)
        assertEquals("", state.query)
        assertEquals("all", state.ownerKey)
        assertNull(state.selectedType)
        assertEquals(FileManagerSort.NAME, state.sort)
        assertEquals(1L, state.pendingExportId)
    }
}
