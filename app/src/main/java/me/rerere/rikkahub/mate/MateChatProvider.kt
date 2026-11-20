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
import java.io.File
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import kotlinx.coroutines.ensureActive
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
            ?: throw IOException("还没有连接服务器，请先在「云服务器」里完成配对")

        val lastUser = messages.lastOrNull { it.role == MessageRole.USER }
        val userText = lastUser
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }
            ?.trim()
            .orEmpty()
        require(userText.length <= 6000) { "单条消息不能超过 6000 字" }

        // 附件先上传换服务端 id，再随消息一起提交；上传失败直接报错，不静默丢弃
        val attachments = lastUser?.parts.orEmpty().filter {
            it is UIMessagePart.Image || it is UIMessagePart.Audio ||
                it is UIMessagePart.Document || it is UIMessagePart.Video
        }
        require(attachments.size <= 4) { "单条消息最多发送 4 个附件" }
        val attachmentIds = attachments.map { uploadPart(connection, it) }
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

    // Match gateway/media.py rather than accepting arbitrary MIME.
    private val supportedMediaTypes = setOf(
        "image/jpeg", "image/png", "image/gif", "image/webp",
        "audio/mp4", "audio/mpeg", "audio/wav", "audio/ogg",
        "application/pdf", "text/plain", "text/csv", "text/markdown",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    )
    private val maxGatewayMediaBytes = 8_000_000

    private suspend fun uploadPart(connection: MateConnection, part: UIMessagePart): String {
        val url = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Audio -> part.url
            is UIMessagePart.Document -> part.url
            else -> throw IOException("当前云服务器网关不支持视频附件")
        }
        if (url.startsWith("data:")) {
            val comma = url.indexOf(',')
            if (comma < 0) throw IOException("附件数据格式有误")
            val header = url.substring(5, comma)
            val base64 = header.endsWith(";base64", ignoreCase = true)
            val limit = if (base64) ((maxGatewayMediaBytes + 2) / 3) * 4 else maxGatewayMediaBytes * 3
            if (url.length - comma - 1 > limit) throw IOException("附件超过云服务器 8MB 限制")
            val mime = header.substringBefore(';').lowercase()
            val body = url.substring(comma + 1)
            val bytes = if (base64) android.util.Base64.decode(body, android.util.Base64.DEFAULT)
                else java.net.URLDecoder.decode(body.replace("+", "%2B"), "UTF-8").toByteArray()
            return uploadMedia(connection, bytes.toRequestBody(mime.toMediaType()), mime)
        }
        val uri = android.net.Uri.parse(url)
        if (uri.scheme !in setOf("content", "file")) throw IOException("暂不支持发送该来源的附件")
        val resolver = appContext.contentResolver
        val declared = (part as? UIMessagePart.Document)?.mime
        val fileName = (part as? UIMessagePart.Document)?.fileName ?: uri.lastPathSegment.orEmpty()
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val inferred = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        val mime = listOf(resolver.getType(uri), declared, inferred).filterNotNull()
            .map { it.substringBefore(';').trim().lowercase() }
            .firstOrNull { it != "application/octet-stream" && '*' !in it && it.isNotEmpty() }
            ?: throw IOException("无法识别附件类型")
        if (mime !in supportedMediaTypes) throw IOException("云服务器不支持此附件类型：" + mime)
        // Some document providers expose no size or return a pipe. Stage one bounded
        // file so OkHttp can send a known Content-Length even for those providers.
        val staged = File.createTempFile("mate-media-", ".tmp", appContext.cacheDir)
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("无法打开附件")
            input.use { source ->
                staged.outputStream().use { target ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > maxGatewayMediaBytes) throw IOException("附件超过云服务器 8MB 限制")
                        target.write(buffer, 0, count)
                    }
                }
            }
            return uploadMedia(connection, staged.asRequestBody(mime.toMediaType()), mime)
        } finally {
            staged.delete()
        }
    }

    /**
     * Gateway protocol v3: POST /media accepts the RAW file bytes as the request body,
     * with Content-Type set to the exact MIME. The gateway itself wraps the upstream
     * /api/v1/file request as multipart. Do not send multipart to the gateway.
     *
     * Reference: cs9527h/dafayu-android gateway/server.py and gateway/media.py.
     */
    private suspend fun uploadMedia(connection: MateConnection, body: RequestBody, mime: String): String {
        if (body.contentLength() !in 8L..maxGatewayMediaBytes.toLong()) {
            throw IOException("附件大小不符合云服务器限制（最大 8MB）")
        }
        if (mime !in supportedMediaTypes) {
            throw IOException("云服务器不支持此附件类型：" + mime)
        }
        val request = Request.Builder()
            .url(connection.baseUrl.trimEnd('/') + "/media")
            .header("Authorization", "Bearer " + connection.token)
            .post(body)
            .build()
        val call = client.newCall(request)
        val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    // 网关用固定错误码说明原因，直接翻成用户能看懂的提示，不吞掉原因
                    val failureText = response.body?.string().orEmpty()
                    val failure = runCatching {
                        json.parseToJsonElement(failureText).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                    throw IOException(
                        when {
                            failure == "media_disabled" -> "云服务器没有开启附件功能"
                            failure == "files_disabled" -> "云服务器只允许图片和语音，暂不允许文件"
                            failure == "unsupported_media_type" -> "云服务器不支持这种文件类型"
                            failure == "media_too_large" -> "附件超过云服务器 8MB 限制"
                            failure == "media_busy" -> "服务器正在处理其他附件，请稍后重试"
                            response.code == 401 -> "设备授权已失效，请重新配对"
                            else -> "附件上传失败，网关 HTTP " + response.code + " " + failure.orEmpty()
                        }.trim()
                    )
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
