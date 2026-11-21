package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BackupFingerprintTest {
    private fun fingerprint(
        settings: String,
        kind: BackupKind = BackupKind.SETTINGS,
        extra: List<BackupManifestEntry> = emptyList()
    ): String =
        BackupFingerprint.calculate(
            kind,
            listOf(BackupManifest.entry("settings.json", settings.toByteArray())) + extra,
            settings
        )

    @Test fun launchAndBackupStatusChangesDoNotTriggerAnotherUpload() {
        val first = """{
            "launchCount":1, "assistants":[],
            "backupReminderConfig":{"autoBackupOnAppLifecycle":true,"lastBackupTime":1}
        }"""
        val later = """{
            "launchCount":99, "assistants":[],
            "backupReminderConfig":{
                "autoBackupOnAppLifecycle":true,"lastBackupTime":999,"lastAutoBackupTime":900,
                "lastAutoBackupError":"synthetic transient failure","autoBackupStates":{"synthetic":{"checkedAt":900}}
            }
        }"""
        assertEquals(fingerprint(first), fingerprint(later))
    }

    @Test fun objectKeyOrderingDoesNotChangeTheContentIdentity() {
        assertEquals(fingerprint("""{"a":1,"b":{"x":2,"y":3}}"""), fingerprint("""{"b":{"y":3,"x":2},"a":1}"""))
    }

    @Test fun promptTextAndItsWhitespaceRemainMeaningful() {
        assertNotEquals(fingerprint("""{"prompt":"exact text"}"""), fingerprint("""{"prompt":"exact text "}"""))
    }

    @Test fun modelAndCredentialChangesAreNotHidden() {
        assertNotEquals(
            fingerprint("""{"apiKey":"synthetic-a","model":"a"}"""),
            fingerprint("""{"apiKey":"synthetic-b","model":"a"}""")
        )
        assertNotEquals(fingerprint("""{"model":"a"}"""), fingerprint("""{"model":"b"}"""))
    }

    @Test fun entryOrderingDoesNotChangeIdentity() {
        val one = BackupManifest.entry("upload/a", byteArrayOf(1))
        val two = BackupManifest.entry("upload/b", byteArrayOf(2))
        assertEquals(fingerprint("{}", extra = listOf(one, two)), fingerprint("{}", extra = listOf(two, one)))
    }

    @Test fun fileContentsAndFilenamesChangeIdentity() {
        val first = BackupManifest.entry("upload/a", byteArrayOf(1))
        assertNotEquals(
            fingerprint("{}", extra = listOf(first)),
            fingerprint("{}", extra = listOf(first.copy(sha256 = BackupManifest.sha256(byteArrayOf(2)))))
        )
        assertNotEquals(
            fingerprint("{}", extra = listOf(first)),
            fingerprint("{}", extra = listOf(first.copy(path = "upload/b")))
        )
    }

    @Test fun backupKindsCannotShareTheirDirtyState() {
        assertNotEquals(fingerprint("{}", BackupKind.SETTINGS), fingerprint("{}", BackupKind.CHATS))
    }

    @Test fun emptyOperationalStatusIsEquivalentToItsAbsence() {
        assertEquals(fingerprint("{}"), fingerprint("""{"backupReminderConfig":{"lastBackupTime":99}}"""))
    }

    @Test fun actualBackupPolicyChangesStillTriggerAnUpload() {
        assertNotEquals(
            fingerprint("""{"backupReminderConfig":{"autoSettingsIntervalHours":1}}"""),
            fingerprint("""{"backupReminderConfig":{"autoSettingsIntervalHours":6}}""")
        )
    }

    @Test fun assistantListOrderAndIdentityAreNotDiscarded() {
        assertNotEquals(
            fingerprint("""{"assistants":[{"id":"a"},{"id":"b"}]}"""),
            fingerprint("""{"assistants":[{"id":"b"},{"id":"a"}]}""")
        )
    }
}
