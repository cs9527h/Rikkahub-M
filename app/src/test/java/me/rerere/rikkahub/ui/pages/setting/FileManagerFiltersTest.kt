package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.CatalogAssistantFilter
import me.rerere.rikkahub.data.files.CatalogFile
import me.rerere.rikkahub.data.files.ManagedFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class FileManagerFiltersTest {
    private fun file(
        id: Long,
        name: String = "$id.txt",
        mime: String = "text/plain",
        bytes: Long = 10,
        created: Long = id,
        owners: Set<String> = emptySet(),
        complete: Boolean = true,
    ) = CatalogFile(
        ManagedFileEntity(id, "upload", "upload/$id.txt", name, mime, bytes, created, created),
        assistantIds = owners,
        ownershipComplete = complete,
    )

    @Test fun `search trims input and matches file name or MIME without case sensitivity`() {
        val files = listOf(file(1, "Report.JSON", "application/json"), file(2, "picture.png", "image/png"))
        assertEquals(
            listOf(1L),
            filterManagedFiles(
                files,
                null,
                CatalogAssistantFilter.All,
                " report ",
                FileManagerSort.NEWEST
            ).map { it.entity.id }
        )
        assertEquals(
            listOf(2L),
            filterManagedFiles(
                files,
                null,
                CatalogAssistantFilter.All,
                "IMAGE/PNG",
                FileManagerSort.NEWEST
            ).map { it.entity.id }
        )
    }

    @Test fun `assistant and media filters intersect and retain shared attachments`() {
        val files =
            listOf(
                file(1, mime = "image/png", owners = setOf("a")),
                file(2, mime = "image/png", owners = setOf("a", "b")),
                file(3, owners = setOf("a")),
                file(4, mime = "image/png", owners = setOf("c"))
            )
        assertEquals(
            listOf(2L, 1L),
            filterManagedFiles(
                files,
                ManagedFileType.IMAGE,
                CatalogAssistantFilter.Assistant("a"),
                "",
                FileManagerSort.NEWEST
            ).map { it.entity.id }
        )
        assertEquals(
            listOf(2L),
            filterManagedFiles(
                files,
                null,
                CatalogAssistantFilter.Shared,
                "",
                FileManagerSort.NEWEST
            ).map { it.entity.id }
        )
    }

    @Test fun `newest oldest and largest are deterministic for ties`() {
        val files =
            listOf(
                file(2, "b.txt", bytes = 20, created = 5),
                file(1, "a.txt", bytes = 10, created = 5),
                file(3, "c.txt", bytes = 30, created = 1)
            )
        fun ids(
            sort: FileManagerSort
        ) = filterManagedFiles(files, null, CatalogAssistantFilter.All, "", sort).map { it.entity.id }
        assertEquals(listOf(2L, 1L, 3L), ids(FileManagerSort.NEWEST))
        assertEquals(listOf(3L, 1L, 2L), ids(FileManagerSort.OLDEST))
        assertEquals(listOf(3L, 2L, 1L), ids(FileManagerSort.LARGEST))
    }

    @Test fun `name sort ignores case and does not mutate source`() {
        val files = listOf(file(3, "b.txt"), file(2, "A.txt"), file(1, "a.txt"))
        assertEquals(
            listOf(1L, 2L, 3L),
            filterManagedFiles(files, null, CatalogAssistantFilter.All, "", FileManagerSort.NAME).map { it.entity.id }
        )
        assertEquals(listOf(3L, 2L, 1L), files.map { it.entity.id })
    }

    @Test fun `default cleanup protects owned shared and unconfirmed files`() {
        val files =
            listOf(file(1), file(2, owners = setOf("a")), file(3, owners = setOf("a", "b")), file(4, complete = false))
        assertEquals(listOf(1L), cleanupManagedFiles(files, null, true, 100).map { it.entity.id })
    }

    @Test fun `explicit cleanup stays inside the supplied filtered snapshot`() {
        val visible = listOf(file(2, owners = setOf("a")), file(5))
        assertEquals(listOf(2L, 5L), cleanupManagedFiles(visible, null, false, 100).map { it.entity.id })
        assertEquals(2, visible.size)
    }

    @Test fun `age cleanup is strictly older than its cutoff`() {
        val now = TimeUnit.DAYS.toMillis(100)
        val cutoff = now - TimeUnit.DAYS.toMillis(7)
        val files = listOf(file(1, created = cutoff - 1), file(2, created = cutoff), file(3, created = now))
        assertEquals(listOf(1L), cleanupManagedFiles(files, 7, true, now).map { it.entity.id })
    }

    @Test fun `export name is a single nonempty component and preserves extension`() {
        assertEquals("Report.pdf", managedExportName("../folder/Report.pdf", 1))
        assertEquals("photo.png", managedExportName("C:\\folder\\photo.png", 1))
        assertEquals("file-8", managedExportName("..", 8))
        assertEquals("note.txt", managedExportName("no\nte.txt", 1))
    }

    @Test fun `sizes reject negative metadata and saturate overflow`() {
        assertEquals(10, managedFilesSize(listOf(file(1, bytes = -20), file(2, bytes = 10))))
        assertEquals(Long.MAX_VALUE, managedFilesSize(listOf(file(1, bytes = Long.MAX_VALUE), file(2, bytes = 10))))
    }

    @Test fun `unconfirmed ownership is distinguishable from definitely unlinked`() {
        val files = listOf(file(1), file(2, complete = false))
        assertEquals(
            listOf(2L),
            filterManagedFiles(
                files,
                null,
                CatalogAssistantFilter.Unknown,
                "",
                FileManagerSort.NEWEST
            ).map { it.entity.id }
        )
        assertTrue(
            filterManagedFiles(files, null, CatalogAssistantFilter.Unassigned, "none", FileManagerSort.NEWEST).isEmpty()
        )
    }
}
