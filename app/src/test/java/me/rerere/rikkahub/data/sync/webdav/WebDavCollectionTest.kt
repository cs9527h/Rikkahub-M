package me.rerere.rikkahub.data.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class WebDavCollectionTest {
    private fun item(href: String, folder: Boolean = false) = WebDavResourceInfo(
        href = href,
        displayName = href.substringAfterLast('/'),
        contentLength = 42,
        contentType = "application/zip",
        lastModified = Instant.EPOCH,
        isCollection = folder,
    )

    @Test fun retainsFirstBackupWhenCollectionIsLast() {
        val backup = item("/dav/backups/backup_20261120.zip")
        val directory = item("/dav/backups/", folder = true)
        assertEquals(
            listOf(backup),
            excludeCurrentCollection(
                listOf(backup, directory), "https://storage.example.net/dav/backups/",
            ),
        )
    }

    @Test fun keepsOtherDirectoriesButRemovesCollectionRegardlessOfResponseOrder() {
        val other = item("/dav/backups/archive/", folder = true)
        val own = item("https://storage.example.net/dav/backups/", folder = true)
        val zip = item("/dav/backups/backup_v1.zip")
        assertEquals(
            listOf(other, zip),
            excludeCurrentCollection(
                listOf(other, own, zip), "https://storage.example.net/dav/backups",
            ),
        )
    }

    @Test fun retainsRootBackupsWhenServerOmitsCollectionResponse() {
        val backup = item("/backup_1.zip")
        assertEquals(
            listOf(backup),
            excludeCurrentCollection(listOf(backup), "https://storage.example.net/"),
        )
    }
}
