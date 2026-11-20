package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableSettingsTest {
    @Test fun encryptedExportCanPreserveSyntheticCredentialsButNotEnableWebServer() {
        val input = Json.parseToJsonElement(
            """{"providers":[{"apiKey":"SYNTHETIC_KEY"}],
                "webDavConfig":{"password":"SYNTHETIC_PASSWORD"},
                "s3Config":{"secretAccessKey":"SYNTHETIC_SECRET"},
                "webServerEnabled":true,"webServerAccessPassword":"PRIVATE"}"""
        ).jsonObject
        val encryptedSettings = BackupManager.portableSettings(input, includeCredentials = true).toString()
        for (value in listOf("SYNTHETIC_KEY", "SYNTHETIC_PASSWORD", "SYNTHETIC_SECRET")) {
            assertTrue(encryptedSettings.contains(value))
            assertFalse(BackupManager.portableSettings(input).toString().contains(value))
        }
        assertFalse(encryptedSettings.contains("PRIVATE"))
        assertTrue(encryptedSettings.contains("\"webServerEnabled\":false"))
    }

    @Test fun removesStoredCredentialsAndPreservesConfiguration() {
        val input = Json.parseToJsonElement(
            """{"providers":[{"id":"my-model","apiKey":"PRIVATE","credential":{"accessToken":"PRIVATE"}}],
               "webDavConfig":{"path":"my-backups","password":"PRIVATE"},
               "s3Config":{"bucket":"my-bucket","secretAccessKey":"PRIVATE"},
               "mcpServers":[{"commonOptions":{"headers":[["custom","PRIVATE"]],"oauth":{"accessToken":"PRIVATE"}}}],
               "assistants":[{"name":"my-assistant","customHeaders":[{"value":"PRIVATE"}]}],
               "webServerAccessPassword":"PRIVATE","webServerEnabled":true}"""
        ).jsonObject
        val output = BackupManager.portableSettings(input).toString()
        assertFalse(output.contains("PRIVATE"))
        for (expected in listOf("my-model", "my-backups", "my-bucket", "my-assistant")) {
            assertTrue(output.contains(expected))
        }
        assertTrue(input.toString().contains("PRIVATE"))
    }
}
