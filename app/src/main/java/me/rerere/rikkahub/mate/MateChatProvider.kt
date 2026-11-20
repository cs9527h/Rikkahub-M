package me.rerere.rikkahub.mate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderQuota
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 把 Mate 网关（协议 v3）当成一个模型后端接进 RikkaHub 原生的 [Provider] 体系。
 *
 * 这样李鱼会话可以完全走原生聊天页面、原生会话数据库、原生搜索与删除，
 * 不再需要一套独立的聊天 UI/状态机。
 *
 * 设备地址与令牌始终从 [MateGatewayStore] 的加密存储里实时读取，
 * 不写进设置数据库，也不出现在任何日志里。
 */
class MateChatProvider(context: Context) : Provider<ProviderSetting.OpenAI> {

    private val appContext = context.applicationContext
    private val store = MateGatewayStore(appContext)
    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(190, TimeUnit.SECONDS)
        .build()

    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
        providerSetting.models

    override suspend fun getQuota(providerSetting: ProviderSetting.OpenAI): ProviderQuota? = null

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        val connection = store.load()
            ?: throw IOException("还没有连接服务器，请先在「连接与设备」里完成配对")

        val lastUser = messages.lastOrNull { it.role == MessageRole.USER }
        val userText = lastUser
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }
            ?.trim()
            .orEmpty()
        require(userText.length <= 6000) { "单条消息不能超过 6000 字" }

        // 附件先上传换服务端 id，再随消息一起提交；上传失败直接报错，不静默丢弃
        val uploadItems = lastUser?.parts.orEmpty().mapNotNull { it.toUploadPayload() }
        require(uploadItems.size <= 4) { "单条消息最多发送 4 个附件" }
        val attachmentIds = uploadItems.map { uploadMedia(connection, it) }
        require(userText.isNotEmpty() || attachmentIds.isNotEmpty()) { "没有可以发送的内容" }

        val requestId = UUID.randomUUID().toString()
        val partId = "mate-" + requestId
        val attachmentsJson = attachmentIds.joinToString(",", "[", "]") { jsonString(it) }
        val payload = """{"request_id":"$requestId","message":${jsonString(userText)},"attachments":$attachmentsJson}"""
        val request = Request.Builder()
            .url(connection.baseUrl.trimEnd('/') + "/chat/stream")
            .header("Authorization", "Bearer " + connection.token)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-store")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val call = client.newCall(request)
        val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        emit(StreamChunk.TextStart(partId))
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("发送失败，网关返回 HTTP " + response.code)
                }
                val contentType = response.header("Content-Type", "").orEmpty()
                if (!contentType.startsWith("text/event-stream")) {
                    throw IOException("服务器返回的不是流式响应")
                }
                val source = response.body?.source() ?: throw IOException("服务器没有返回内容")

                var accumulated = 0
                var terminal = false
                val data = StringBuilder()

                suspend fun flushEvent() {
                    if (data.isEmpty()) return
                    val snapshot = data.toString()
                    data.clear()
                    val obj = runCatching { json.parseToJsonElement(snapshot).jsonObject }
                        .getOrElse { throw IOException("服务器返回了无法解析的数据") }
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (id != requestId) throw IOException("网关请求标识不匹配，已停止接收")
                    val type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val state = obj["state"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val error = obj["error"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (text.length > 200_000) throw IOException("服务器回复过长")
                    when {
                        type == "error" || state == "failed" || state == "expired" ->
                            throw IOException(error.ifBlank { "服务器处理这条消息失败" })
                        state == "uncertain" ->
                            throw IOException("网络中断，服务器状态未知；这条消息不会被自动重发")
                    }
                    if (text.length > accumulated) {
                        emit(StreamChunk.TextDelta(partId, text.substring(accumulated)))
                        accumulated = text.length
                    }
                    if (type == "done" || state == "done") terminal = true
                }

                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty()) {
                        flushEvent()
                        if (terminal) break
                        continue
                    }
                    if (line.startsWith(":")) continue
                    if (!line.startsWith("data:")) continue
                    val value = line.substring(5).removePrefix(" ")
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(value)
                }
                flushEvent()
                if (!terminal) {
                    throw IOException("网络中断，没收到服务器的最终状态；请不要重复发送")
                }
            }
        } finally {
            handle.dispose()
        }
        emit(StreamChunk.TextEnd(partId))
        emit(StreamChunk.Finish(finishReason = "stop", responseId = requestId, model = params.model.modelId))
    }.flowOn(Dispatchers.IO)

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val builder = StringBuilder()
        var finishId: String? = null
        streamText(providerSetting, messages, params).collect { chunk ->
            when (chunk) {
                is StreamChunk.TextDelta -> builder.append(chunk.text)
                is StreamChunk.Finish -> finishId = chunk.responseId
                else -> Unit
            }
        }
        return TextGenerationResult(
            id = finishId ?: UUID.randomUUID().toString(),
            model = params.model.modelId,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text(builder.toString())),
            ),
            finishReason = "stop",
        )
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    /** 聊天里能带出去的附件种类；语音按音频上传，服务端按自身能力决定是否处理。 */
    private data class UploadPayload(val bytes: ByteArray, val fileName: String, val mime: String, val kind: String)

    private fun UIMessagePart.toUploadPayload(): UploadPayload? = when (this) {
        is UIMessagePart.Image -> decodeSource(url).let { (bytes, mime) ->
            UploadPayload(bytes, "image-" + bytes.size + guessExt(mime), mime, "image")
        }
        is UIMessagePart.Audio -> decodeSource(url).let { (bytes, mime) ->
            UploadPayload(bytes, "audio-" + bytes.size + guessExt(mime), mime, "audio")
        }
        is UIMessagePart.Video -> throw IOException("当前云服务器网关不支持视频附件")
        is UIMessagePart.Document -> decodeSource(url).let { (bytes, mime) ->
            UploadPayload(bytes, fileName.ifBlank { "document" + guessExt(mime) }, mime, "file")
        }
        else -> null
    }

    // Match gateway/media.py rather than accepting arbitrary MIME.
    private val supportedMediaTypes = setOf(
        "image/jpeg", "image/png", "image/gif", "image/webp",
        "audio/mp4", "audio/mpeg", "audio/wav", "audio/ogg",
        "application/pdf", "text/plain", "text/csv", "text/markdown",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    )
    private val maxGatewayMediaBytes = 8_000_000

    private fun guessExt(mime: String): String = when {
        mime.contains("png") -> ".png"
        mime.contains("jpeg") || mime.contains("jpg") -> ".jpg"
        mime.contains("webp") -> ".webp"
        mime.contains("gif") -> ".gif"
        mime.contains("pdf") -> ".pdf"
        mime.contains("mpeg") || mime.contains("mp3") -> ".mp3"
        mime.contains("wav") -> ".wav"
        mime.contains("m4a") || mime.contains("mp4a") -> ".m4a"
        else -> ""
    }

    /** 把 data: / content: / file: 的引用读成字节；读取失败一律抛出，不静默丢弃附件。 */
    private fun decodeSource(url: String): Pair<ByteArray, String> = try {
        when {
            url.startsWith("data:") -> {
                val header = url.substringAfter("data:").substringBefore(',')
                val mime = header.substringBefore(';').ifBlank { "application/octet-stream" }
                val body = url.substringAfter(',')
                if (body.length > (maxGatewayMediaBytes * 4L / 3 + 16)) {
                    throw IOException("附件超过云服务器 8MB 限制")
                }
                val bytes = if ("base64" in header) {
                    android.util.Base64.decode(body, android.util.Base64.DEFAULT)
                } else {
                    java.net.URLDecoder.decode(body, "UTF-8").toByteArray()
                }
                bytes to mime
            }
            url.startsWith("content:") || url.startsWith("file:") -> {
                val parsed = android.net.Uri.parse(url)
                val resolver = appContext.contentResolver
                val mime = resolver.getType(parsed) ?: "application/octet-stream"
                val bytes = resolver.openInputStream(parsed)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size().toLong() + count > maxGatewayMediaBytes) {
                            throw IOException("附件超过云服务器 8MB 限制")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: throw IOException("附件读取为空")
                bytes to mime
            }
            else -> throw IOException("暂不支持发送该来源的附件")
        }
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException("读取附件失败：" + (e.message ?: "未知错误"))
    }

    /**
     * Gateway protocol v3: POST /media accepts the RAW file bytes as the request body,
     * with Content-Type set to the exact MIME. The gateway itself wraps the upstream
     * /api/v1/file request as multipart. Do not send multipart to the gateway.
     *
     * Reference: cs9527h/dafayu-android gateway/server.py and gateway/media.py.
     */
    private suspend fun uploadMedia(connection: MateConnection, payload: UploadPayload): String {
        if (payload.bytes.size !in 8..maxGatewayMediaBytes) {
            throw IOException("附件大小不符合云服务器限制（最大 8MB）")
        }
        if (payload.mime !in supportedMediaTypes) {
            throw IOException("云服务器不支持此附件类型：" + payload.mime)
        }
        val request = Request.Builder()
            .url(connection.baseUrl.trimEnd('/') + "/media")
            .header("Authorization", "Bearer " + connection.token)
            .post(payload.bytes.toRequestBody(payload.mime.toMediaType()))
            .build()
        val call = client.newCall(request)
        val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("附件上传失败，网关返回 HTTP " + response.code)
                }
                val bodyText = response.body?.string().orEmpty()
                val obj = runCatching { json.parseToJsonElement(bodyText).jsonObject }.getOrElse {
                    throw IOException("附件上传返回了无法解析的数据")
                }
                val id = obj["id"]?.jsonPrimitive?.contentOrNull
                if (id.isNullOrBlank() || !Regex("[0-9a-f]{32}").matches(id)) {
                    throw IOException("附件上传响应缺少有效的网关附件 ID")
                }
                return id
            }
        } finally {
            handle.dispose()
        }
    }

}
