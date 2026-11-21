package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.BackupReminderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticBackupSettingsTest {
    @Test fun oldReminderConfigurationDefaultsToNoCredentialsAndNoFalseSuccess() {
        val config = Json.decodeFromString<BackupReminderConfig>(
            """{"enabled":true,"intervalDays":7,"lastBackupTime":1234,"autoBackupOnAppLifecycle":true}"""
        )
        assertTrue(config.autoBackupOnAppLifecycle)
        assertFalse(config.autoBackupIncludeCredentials)
        assertEquals(0L, config.lastAutoBackupTime)
        assertEquals("", config.lastAutoBackupError)
        assertEquals(1234L, config.lastBackupTime)
        assertEquals(6, config.autoSettingsIntervalHours)
        assertEquals(72, config.autoChatsIntervalHours)
        assertTrue(config.autoBackupStates.isEmpty())
    }

    @Test fun automaticStatusRoundTripsWithoutContainingRecoveryPassphrase() {
        val config = BackupReminderConfig(
            autoBackupOnAppLifecycle = true,
            autoBackupIncludeCredentials = true,
            lastAutoBackupTime = 1234,
            lastAutoBackupError = "网络或备份存储暂不可用",
            autoBackupStates = mapOf(
                "webdav_settings" to AutoBackupState(1234, 1234, "v1:" + "a".repeat(64))
            ),
        )
        val encoded = Json.encodeToString(config)
        assertFalse(encoded.contains("passphrase", ignoreCase = true))
        assertEquals(config, Json.decodeFromString<BackupReminderConfig>(encoded))
    }
}
