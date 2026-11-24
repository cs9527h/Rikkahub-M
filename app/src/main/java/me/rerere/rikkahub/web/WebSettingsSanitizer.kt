package me.rerere.rikkahub.web

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Web 端只需要设置里与界面有关的字段，但 `/api/events` 早先直接把整个 [me.rerere.rikkahub.data.datastore.Settings]
 * 序列化下发，等于把每个 provider 的 apiKey、WebDAV/S3 口令、MCP 访问令牌和 Web 访问密码都推给浏览器。
 *
 * 这里在序列化后按字段名脱敏：结构、字段名和类型全部保留（Web 前端照常解析），只把敏感字段的值替换成空串。
 * 字段名匹配忽略大小写与下划线，避免 `secret_access_key` 之类漏网。
 */
internal object WebSettingsSanitizer {
    private const val REDACTED = ""

    /** 命中即脱敏的字段名（已去掉下划线/连字符并转小写）。 */
    private val sensitiveNames = setOf(
        "apikey",
        "password",
        "webserveraccesspassword",
        "proxyusername",
        "secretaccesskey",
        "accesskeyid",
        "accesstoken",
        "refreshtoken",
        "idtoken",
        "clientsecret",
        "sessiontoken",
        "privatekey",
        "authorization",
        "cookie",
    )

    /** 字段名以这些后缀结尾也脱敏，覆盖 `backupPassword`、`oauthAccessToken` 一类命名。 */
    private val sensitiveSuffixes = listOf("password", "secret", "token", "apikey", "credential")

    fun sanitize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(sanitizeObject(element))
        is JsonArray -> sanitizeArray(element)
        else -> element
    }

    /** MCP 的 `headers: List<Pair<String, String>>` 在 JSON 里是 `["Authorization", "..."]`，按名称脱敏。 */
    private fun sanitizeArray(array: JsonArray): JsonArray {
        val headerName = (array.getOrNull(0) as? JsonPrimitive)?.contentOrNull
        if (headerName != null && array.size == 2 && isSensitiveHeader(headerName)) {
            val value = array[1]
            if (value is JsonPrimitive) {
                return JsonArray(listOf(array[0], JsonPrimitive(REDACTED)))
            }
        }
        return JsonArray(array.map { sanitize(it) })
    }

    private fun sanitizeObject(obj: JsonObject): Map<String, JsonElement> {
        // 请求头有两种序列化形态：MCP 用 Pair（{"first": 名称, "second": 值}），
        // CustomHeader 用 {"name": 名称, "value": 值}。按 header 名称决定是否脱敏。
        val pairHeaderName = obj.stringOrNull("first")
        val customHeaderName = obj.stringOrNull("name")
        return obj.mapValues { (key, value) ->
            when {
                isSensitiveName(key) -> redact(value)
                key == "second" && pairHeaderName != null && isSensitiveHeader(pairHeaderName) -> redact(value)
                key == "value" && customHeaderName != null && isSensitiveHeader(customHeaderName) -> redact(value)
                else -> sanitize(value)
            }
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun isSensitiveName(name: String): Boolean {
        val normalized = name.lowercase().replace("_", "").replace("-", "")
        return normalized in sensitiveNames || sensitiveSuffixes.any { normalized.endsWith(it) }
    }

    private fun isSensitiveHeader(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized.contains("authorization") ||
            normalized.contains("cookie") ||
            normalized.contains("token") ||
            normalized.contains("key") ||
            normalized.contains("secret")
    }

    private fun redact(value: JsonElement): JsonElement =
        if (value is JsonPrimitive) JsonPrimitive(REDACTED) else sanitize(value)
}
