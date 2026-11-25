package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRetentionTest {
    private fun version(name: String, time: Long) = BackupVersion(name, time)

    private val settingsOld = version("backup_settings_20260101_010101.zip", 10)
    private val settingsNew = version("backup_settings_20260202_020202_123.zip", 20)
    private val settingsNewest = version("backup_settings_20260303_030303.zip", 30)
    private val chats = version("backup_chats_20260303_030303.zip", 30)
    private val manual = version("backup_20260303_030303.zip", 30)

    @Test fun keepZeroDisablesDeletionEntirely() {
        assertTrue(BackupRetention.selectExpired(listOf(settingsOld, settingsNew), BackupKind.SETTINGS, 0).isEmpty())
    }

    @Test fun keepsTheNewestRequestedNumberOfSameKind() {
        val expired = BackupRetention.selectExpired(
            listOf(settingsOld, settingsNew, settingsNewest, chats, manual),
            BackupKind.SETTINGS,
            2,
        )
        assertEquals(listOf(settingsOld), expired)
    }

    @Test fun manualAndOtherKindsAreNeverSelected() {
        val expired = BackupRetention.selectExpired(
            listOf(settingsOld, settingsNew, chats, manual),
            BackupKind.SETTINGS,
            1,
        )
        assertEquals(listOf(settingsOld), expired)
    }

    @Test fun malformedOrUnrelatedNamesAreNotTreatedAsAutomaticArchives() {
        val names = listOf(
            "backup_settings_latest.zip",
            "../backup_settings_20260101_010101.zip",
            "backup_settings_20260101_010101.txt",
            "notes_settings_20260101_010101.zip",
        )
        assertTrue(BackupRetention.selectExpired(names.map { version(it, 1) }, BackupKind.SETTINGS, 1).isEmpty())
        assertFalse(BackupRetention.matches("backup_settings_latest.zip", BackupKind.SETTINGS))
    }

    @Test fun fullArchiveKindIsRejected() {
        assertTrue(runCatching { BackupRetention.selectExpired(listOf(manual), BackupKind.FULL, 1) }.isFailure)
        assertFalse(BackupRetention.matches("backup_settings_20260101_010101.zip", BackupKind.FULL))
    }

    @Test fun negativeKeepFailsInsteadOfDeletingEverything() {
        assertTrue(
            runCatching { BackupRetention.selectExpired(listOf(settingsOld), BackupKind.SETTINGS, -1) }.isFailure
        )
    }

    @Test fun newestNameIsKeptEvenWhenServerTimestampIsMissing() {
        // S3 缺 Last-Modified、WebDAV 解析失败都会退成 EPOCH：只按服务端时间排序会把最新的删掉。
        val newestWithoutTimestamp = version("backup_settings_20260303_030303.zip", 0)
        val olderWithTimestamp = version("backup_settings_20260101_010101.zip", 9_999_999)
        val expired = BackupRetention.selectExpired(
            listOf(newestWithoutTimestamp, olderWithTimestamp),
            BackupKind.SETTINGS,
            1,
        )
        assertEquals(listOf(olderWithTimestamp), expired)
    }

    @Test fun timestampTiesAreDeterministic() {
        val a = version("backup_settings_20260101_010101.zip", 5)
        val b = version("backup_settings_20260102_010101.zip", 5)
        assertEquals(listOf(a), BackupRetention.selectExpired(listOf(a, b), BackupKind.SETTINGS, 1))
    }

    @Test fun uppercaseExtensionStillCountsAsGeneratedArchive() {
        assertTrue(BackupRetention.matches("backup_chats_20260101_010101.RKMBAK", BackupKind.CHATS))
    }

    @Test fun nothingIsDeletedWhenFewerVersionsThanKeepExist() {
        assertTrue(BackupRetention.selectExpired(listOf(settingsOld), BackupKind.SETTINGS, 5).isEmpty())
    }
}
