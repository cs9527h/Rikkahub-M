package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableSettingsTest {
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
