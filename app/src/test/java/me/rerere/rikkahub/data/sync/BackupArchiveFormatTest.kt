package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertArrayEquals
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
        for (name in listOf("report.zip", "backup_one.zip.part", "backup_a/other.zip", "backup_a\\other.rkmbak", "backup_a.txt")) {
            assertFalse(name, BackupArchiveFormat.isBackupName(name))
        }
    }

    @Test fun explicitlyUnencryptedExportDoesNotUsePassphrase() {
        assertNull(BackupArchiveFormat.exportPassphrase(false, "unused password", "unused password", false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun plaintextCannotIncludeCredentials() {
        BackupArchiveFormat.exportPassphrase(false, "password", "password", true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyEncryptionPassphraseIsRejected() {
        BackupArchiveFormat.exportPassphrase(true, "", "", false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedConfirmationIsRejected() {
        BackupArchiveFormat.exportPassphrase(true, "password one", "password two", true)
    }

    @Test fun whitespaceInPassphraseIsPreservedAndCopyIsIndependent() {
        val original = " pass word "
        val passphrase = BackupArchiveFormat.exportPassphrase(true, original, original, true)!!
        assertArrayEquals(original.toCharArray(), passphrase)
        passphrase.fill('\u0000')
        assertTrue(original == " pass word ")
    }
}
