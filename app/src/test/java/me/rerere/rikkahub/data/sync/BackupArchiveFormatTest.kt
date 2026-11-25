package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupArchiveFormatTest {
    @Test fun bothLegacyAndEncryptedArchivesAppearInListings() {
        assertTrue(BackupArchiveFormat.isBackupName("backup_20261121.zip"))
        assertTrue(BackupArchiveFormat.isBackupName("backup_20261121.rkmbak"))
        assertTrue(BackupArchiveFormat.isBackupName("backup_20261121.RKMBAK"))
    }

    @Test fun unrelatedFilesAndNestedPathsAreNotBackups() {
        for (name in listOf(
            "report.zip",
            "backup_one.zip.part",
            "backup_a/other.zip",
            "backup_a\\other.rkmbak",
            "backup_a.txt"
        )) {
            assertFalse(name, BackupArchiveFormat.isBackupName(name))
        }
    }

    @Test
    fun backupKindComesFromTheAutomaticNameOnly() {
        assertEquals(
            BackupKind.SETTINGS,
            BackupArchiveFormat.kindOfName("backup_settings_20261121_120000.rkmbak"),
        )
        assertEquals(BackupKind.CHATS, BackupArchiveFormat.kindOfName("backup_chats_20261121_120000.zip"))
        assertEquals(BackupKind.FULL, BackupArchiveFormat.kindOfName("backup_20261121_120000.zip"))
        assertNull(BackupArchiveFormat.kindOfName("notes.txt"))
        assertNull(BackupArchiveFormat.kindOfName("../backup_settings_20261121.rkmbak"))
    }
}
