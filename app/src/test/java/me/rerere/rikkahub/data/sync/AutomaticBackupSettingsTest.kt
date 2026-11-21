package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.BackupReminderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticBackupSettingsTest {
    // 与应用的设置序列化保持一致：旧版本多出来的字段直接忽略，不阻断升级。
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun oldReminderConfigurationLoadsWithSafeDefaults() {
        val config = json.decodeFromString<BackupReminderConfig>(
            """{"enabled":true,"intervalDays":7,"lastBackupTime":1234,"autoBackupOnAppLifecycle":true}"""
        )
        assertTrue(config.autoBackupOnAppLifecycle)
        assertEquals(0L, config.lastAutoBackupTime)
        assertEquals("", config.lastAutoBackupError)
        assertEquals(1234L, config.lastBackupTime)
        assertEquals(6, config.autoSettingsIntervalHours)
        assertEquals(72, config.autoChatsIntervalHours)
        assertEquals(0, config.autoRetainVersions)
        assertTrue(config.autoBackupStates.isEmpty())
    }

    @Test fun removedCredentialSwitchIsIgnoredAndCannotReenableIt() {
        // 0.4.3 及更早保存过 autoBackupIncludeCredentials=true；0.4.4 已移除该开关，凭据恒被排除。
        val config = json.decodeFromString<BackupReminderConfig>(
            """{"enabled":true,"autoBackupOnAppLifecycle":true,"autoBackupIncludeCredentials":true}"""
        )
        assertTrue(config.autoBackupOnAppLifecycle)
        assertFalse(json.encodeToString(config).contains("IncludeCredentials"))
    }

    @Test fun automaticStatusRoundTripsWithoutContainingRecoveryPassphrase() {
        val config = BackupReminderConfig(
            autoBackupOnAppLifecycle = true,
            lastAutoBackupTime = 1234,
            lastAutoBackupError = "网络或备份存储暂不可用",
            autoBackupStates = mapOf(
                "webdav_settings" to AutoBackupState(1234, 1234, "v1:" + "a".repeat(64))
            ),
        )
        val encoded = json.encodeToString(config)
        assertFalse(encoded.contains("passphrase", ignoreCase = true))
        assertFalse(encoded.contains("IncludeCredentials"))
        assertEquals(config, json.decodeFromString<BackupReminderConfig>(encoded))
    }
}
