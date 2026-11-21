package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupPolicyTest {
    private val hour = 60L * 60 * 1000
    private val fingerprint = "v1:" + "a".repeat(64)
    private val destination = AutoBackupPolicy.destinationId("webdav", "https://storage.invalid", "backup", "test")

    @Test fun legacyConfigRunsBothTypesImmediatelyWithoutAnUploadMarker() {
        for (kind in listOf(BackupKind.SETTINGS, BackupKind.CHATS)) {
            assertTrue(AutoBackupPolicy.isDue(null, kind, 100 * hour, 6))
        }
    }

    @Test fun settingsAndChatChecksHaveIndependentCadences() {
        val state = AutoBackupState(lastCheckedAt = 100 * hour, lastSuccessAt = 100 * hour, fingerprint = fingerprint)
        assertTrue(AutoBackupPolicy.isDue(state, BackupKind.SETTINGS, 106 * hour, 6))
        assertFalse(AutoBackupPolicy.isDue(state, BackupKind.CHATS, 106 * hour, 72))
        assertTrue(AutoBackupPolicy.isDue(state, BackupKind.CHATS, 172 * hour, 72))
    }

    @Test fun recheckedUnchangedDataDoesNotClaimANewSuccessfulUpload() {
        val uploaded = AutoBackupPolicy.uploaded(null, 100 * hour, fingerprint, destination)
        val checked = AutoBackupPolicy.checked(uploaded, 106 * hour)
        assertEquals(100 * hour, checked.lastSuccessAt)
        assertEquals(106 * hour, checked.lastCheckedAt)
        assertTrue(AutoBackupPolicy.unchanged(checked, fingerprint, destination))
        assertFalse(AutoBackupPolicy.isDue(checked, BackupKind.SETTINGS, 107 * hour, 6))
    }

    @Test fun oldUnuploadedFingerprintCannotSkipTheFirstRealUpload() {
        val state = AutoBackupState(fingerprint = fingerprint, lastSuccessAt = 0)
        assertFalse(AutoBackupPolicy.unchanged(state, fingerprint, destination))
    }

    @Test fun changedPayloadMustUploadAgain() {
        val state = AutoBackupPolicy.uploaded(null, 100 * hour, fingerprint, destination)
        assertFalse(AutoBackupPolicy.unchanged(state, "v1:" + "b".repeat(64), destination))
    }

    @Test fun changingRemoteLocationOrAccountRequiresFreshUpload() {
        val state = AutoBackupPolicy.uploaded(null, 100 * hour, fingerprint, destination)
        val differentPath = AutoBackupPolicy.destinationId("webdav", "https://storage.invalid", "another", "test")
        val differentAccount = AutoBackupPolicy.destinationId("webdav", "https://storage.invalid", "backup", "other")
        assertFalse(AutoBackupPolicy.unchanged(state, fingerprint, differentPath))
        assertFalse(AutoBackupPolicy.unchanged(state, fingerprint, differentAccount))
        assertTrue(AutoBackupPolicy.unchanged(state, fingerprint, destination))
    }

    @Test fun clockRollbackForcesARecheck() {
        val state = AutoBackupPolicy.checked(null, 100 * hour)
        assertTrue(AutoBackupPolicy.isDue(state, BackupKind.CHATS, 50 * hour, 72))
    }

    @Test fun statesAreSeparatedByTransportAndTypeWithoutRemoteUrls() {
        val keys = listOf(
            "webdav" to BackupKind.SETTINGS,
            "webdav" to BackupKind.CHATS,
            "s3" to BackupKind.SETTINGS,
            "s3" to BackupKind.CHATS
        ).map { (transport, kind) ->
            AutoBackupPolicy.key(transport, kind)
        }
        assertEquals(4, keys.toSet().size)
        assertNotEquals(keys.first(), keys.last())
        assertTrue(keys.none { '/' in it || ':' in it })
    }

    @Test fun invalidTimingCannotSilentlyDisableAllBackups() {
        assertTrue(runCatching { AutoBackupPolicy.isDue(null, BackupKind.SETTINGS, 0, 0) }.isFailure)
        assertTrue(runCatching { AutoBackupPolicy.isDue(null, BackupKind.CHATS, 0, 24 * 31) }.isFailure)
    }
}
