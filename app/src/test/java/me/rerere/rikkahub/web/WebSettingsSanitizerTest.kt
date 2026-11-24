package me.rerere.rikkahub.web

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebSettingsSanitizerTest {

    @Test
    fun `sanitize blanks credentials but keeps structure`() {
        val settings = Settings(
            webServerAccessPassword = "web-pass",
            providers = listOf(
                ProviderSetting.OpenAI(
                    name = "proxy",
                    apiKey = "sk-secret-key",
                    customHeaders = listOf(CustomHeader("Authorization", "Bearer header-secret")),
                )
            ),
            webDavConfig = WebDavConfig(url = "https://dav.example.com", username = "u", password = "dav-pass"),
            s3Config = S3Config(
                endpoint = "https://s3.example.com",
                accessKeyId = "AKIAEXAMPLE",
                secretAccessKey = "s3-secret",
            ),
        )

        val sanitized = WebSettingsSanitizer
            .sanitize(JsonInstant.encodeToJsonElement(Settings.serializer(), settings)) as JsonObject
        val text = sanitized.toString()

        assertFalse("apiKey 必须脱敏", text.contains("sk-secret-key"))
        assertFalse("Web 访问口令必须脱敏", text.contains("web-pass"))
        assertFalse("WebDAV 口令必须脱敏", text.contains("dav-pass"))
        assertFalse("S3 secret 必须脱敏", text.contains("s3-secret"))
        assertFalse("自定义请求头里的凭据必须脱敏", text.contains("header-secret"))

        // 结构与非敏感字段必须保留，Web 前端照常解析。
        val providers = sanitized["providers"]!!.jsonArray
        assertEquals(1, providers.size)
        val provider = providers[0].jsonObject
        assertEquals("proxy", provider["name"]!!.jsonPrimitive.content)
        assertEquals("", provider["apiKey"]!!.jsonPrimitive.content)
        assertEquals(settings.webServerPort, sanitized["webServerPort"]!!.jsonPrimitive.int)
    }

    @Test
    fun `sanitize keeps mcp header pairs redacted by header name`() {
        val json = JsonInstant.parseToJsonElement(
            """{"mcpServers":[{"headers":[["Authorization","Bearer abc"],["X-Trace","1"]]}]}"""
        )
        val text = WebSettingsSanitizer.sanitize(json).toString()
        assertFalse("Authorization 头的值必须脱敏", text.contains("Bearer abc"))
        // 非敏感头保留，说明只按名称挑字段，而不是整段丢弃。
        assertEquals(true, text.contains("X-Trace"))
    }
}
