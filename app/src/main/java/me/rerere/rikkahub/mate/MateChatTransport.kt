package me.rerere.rikkahub.mate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * Wire format of the existing Mate protocol-v3 server (not an OpenAI endpoint).
 * Request IDs are durable and NEVER automatically regenerated on network errors.
 */
internal data class MateSnapshot(
    val requestId: String,
    val state: String,
    val type: String,
    val text: String,
    val error: String
) {
    val terminal: Boolean get() = type == "done" || type == "error"
}

internal object MateWire {
    private val validId = Regex("[A-Za-z0-9_-]{8,80}")

    fun parseSnapshot(payload: String, expectedId: String): MateSnapshot {
        val obj = JSONObject(payload)
        val id = obj.optString("id")
        require(id == expectedId && validId.matches(id)) { "网关请求标识不匹配" }
        val type = obj.optString("type")
        val state = obj.optString("state")
        val text = obj.optString("text")
        val error = obj.optString("error")
        require(type in setOf("", "snapshot", "done", "error")) { "未知流事件" }
        require(state in setOf("running", "done", "failed", "error", "uncertain", "expired")) {
            "未知服务器状态"
        }
        require(type != "done" || state == "done") { "流结束事件与请求状态不一致" }
        require(text.length <= 200_000 && error.length <= 256) { "网关回复长度无效" }
        return MateSnapshot(id, state, type, text, error)
    }

    fun newId(): String = UUID.randomUUID().toString()
}

/** Stateful SSE parser; supports comments and multi-line data, with size caps. */
internal class MateSseParser {
    private val data = StringBuilder()

    fun line(input: String): String? {
        require(input.length <= 256_000) { "事件行过长" }
        if (input.isEmpty()) {
            if (data.isEmpty()) return null
            val result = data.toString()
            data.clear()
            return result
        }
        if (input.startsWith(":")) return null
        if (!input.startsWith("data:")) return null
        val value = input.substring(5).removePrefix(" ")
        require(data.length + value.length <= 256_000) { "事件内容过长" }
        if (data.isNotEmpty()) data.append('\n')
        data.append(value)
        return null
    }
}

internal class MateChatTransport {
    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private fun request(connection: MateConnection, path: String, data: JSONObject? = null): Request {
        val base = MateGatewayAddress.normalize(connection.baseUrl)
        require(path.startsWith("/") && ".." !in path && "#" !in path) { "无效网关路径" }
        val url = (base + path).toHttpUrlOrNull() ?: throw IOException("网关地址不合法")
        require(url.host.isNotBlank()) { "无效服务器" }
        val builder = Request.Builder().url(url)
            .header("Authorization", "Bearer " + connection.token)
            .header("Accept", if (path == "/chat/stream") "text/event-stream" else "application/json")
            .header("Cache-Control", "no-store")
        if (data != null) builder.post(
            data.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        )
        return builder.build()
    }

    private fun boundedJson(response: Response, maxBytes: Int = 256_000): JSONObject {
        if (!response.isSuccessful) {
            // Peek only a bounded response and accept known-safe error identifiers.
            // Never show upstream HTML, tokens or arbitrary private response text.
            val code = runCatching {
                JSONObject(response.peekBody(1024L).string()).optString("error")
            }.getOrDefault("").takeIf { Regex("[a-z_]{1,64}").matches(it) }
            throw IOException("网关 HTTP " + response.code + (if (code == null) "" else " " + code))
        }
        val type = response.header("Content-Type", "").orEmpty()
        if (!type.startsWith("application/json")) throw IOException("网关响应格式无效")
        val input = response.body?.byteStream() ?: throw IOException("网关未返回数据")
        val buffer = ByteArray(4096)
        val output = ByteArrayOutputStream()
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            if (output.size() + size > maxBytes) throw IOException("网关响应超过限制")
            output.write(buffer, 0, size)
        }
        return try {
            JSONObject(output.toString("UTF-8"))
        } catch (_: Exception) {
            throw IOException("网关返回数据无法解析")
        }
    }

    suspend fun get(connection: MateConnection, path: String): JSONObject = withContext(Dispatchers.IO) {
        val call = http.newCall(request(connection, path))
        val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { boundedJson(it) }
        } finally {
            handle.dispose()
        }
    }

    suspend fun post(connection: MateConnection, path: String, body: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val call = http.newCall(request(connection, path, body))
            val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { boundedJson(it) }
            } finally {
                handle.dispose()
            }
        }

    suspend fun capabilities(connection: MateConnection): JSONObject {
        val caps = get(connection, "/capabilities")
        if (caps.optInt("protocol") != 3 || caps.optString("instance_id") != connection.instanceId) {
            throw IOException("服务器协议或实例标识发生变化，已拒绝使用旧授权")
        }
        return caps
    }

    suspend fun requestResult(connection: MateConnection, id: String): MateSnapshot {
        require(Regex("[A-Za-z0-9_-]{8,80}").matches(id))
        val result = get(connection, "/requests/" + id)
        // The request lookup response has no SSE event type.
        return MateWire.parseSnapshot(result.put("type", if (result.optString("state") == "done") "done"
            else if (result.optString("state") == "running") "snapshot" else "error").toString(), id)
    }

    private suspend fun postJson(
        connection: MateConnection, path: String, body: JSONObject
    ): JSONObject = withContext(Dispatchers.IO) {
        require(path in setOf("/devices/pair-code", "/devices/revoke"))
        capabilities(connection)
        val call = http.newCall(request(connection, path, body))
        val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { boundedJson(it) }
        } finally {
            handle.dispose()
        }
    }

    suspend fun devices(connection: MateConnection): JSONObject {
        capabilities(connection)
        return get(connection, "/devices")
    }

    suspend fun createInvite(connection: MateConnection): JSONObject {
        val result = postJson(connection, "/devices/pair-code", JSONObject().put("confirm", true))
        val code = result.optString("code")
        require(code.length in 24..80 && result.optInt("expires_in") in 60..600) {
            "服务器返回的配对码无效"
        }
        return result
    }

    suspend fun revokeDevice(connection: MateConnection, deviceId: String): Boolean {
        require(deviceId.matches(Regex("[0-9a-f]{24}"))) { "设备标识无效" }
        val result = postJson(connection, "/devices/revoke",
            JSONObject().put("device_id", deviceId))
        return result.optBoolean("revoked")
    }

    suspend fun memorySearch(connection: MateConnection, query: String): JSONObject {
        require(query.length <= 120) { "检索内容过长" }
        val address = (connection.baseUrl + "/memory/search").toHttpUrlOrNull()
            ?: throw IOException("服务器地址无效")
        val url = address.newBuilder().addQueryParameter("q", query).addQueryParameter("after", "0").build()
        require(url.toString().startsWith(MateGatewayAddress.normalize(connection.baseUrl) + "/memory/search?"))
        return get(connection, "/memory/search?" + (url.encodedQuery ?: ""))
    }
}
