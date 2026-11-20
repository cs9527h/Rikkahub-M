package me.rerere.rikkahub.mate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
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
    private val transport = MateChatTransport()
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
    ): Flow<StreamChunk> = flow<StreamChunk> {
        val connection = store.load()
            ?: throw IOException("还没有连接服务器，请先在「云服务器」里完成配对")

        val capabilities = transport.capabilities(connection)
        val versions = capabilities.optJSONArray("chat_versions")
        val isolated = versions != null && (0 until versions.length()).any { versions.optInt(it) == 4 }
        val ordered = isolated && capabilities.optBoolean("ordered_context_v1")
        val diagnosticKey = "turn_" + connection.instanceId
        val prefs = MateDiagnostics.preferences(appContext)
        if (params.cloudInjectionCount > 0 && !ordered) {
            prefs.edit().putString(diagnosticKey, "云服务器未支持本次注入，已停止发送").apply()
            throw IOException("当前云服务器未启用有序提示词协议，本次注入未发送")
        }
        val lastUser = messages.lastOrNull {
            it.role == MessageRole.USER && (params.currentUserMessageId == null || it.id.toString() == params.currentUserMessageId)
        }
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
        val payload = org.json.JSONObject().apply {
            put("request_id", requestId)
            put("message", userText)
            put("attachments", org.json.JSONArray(attachmentIds))
            if (isolated && params.sessionId != null) {
                put("chat_version", 4)
                put("conversation_id", params.sessionId)
                if (ordered) {
                    val context = org.json.JSONArray()
                    messages.forEach { message ->
                        val text = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                        if (message.role == MessageRole.TOOL) throw IOException("云服务器暂不支持工具历史")
                        context.put(org.json.JSONObject().put("role", message.role.name.lowercase()).put("content", text))
                    }
                    put("context_messages", context)
                }
            }
        }.toString()
        require(payload.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "云端聊天上下文超过 1 MiB，请缩短上下文后重试" }
        val request = Request.Builder()
            .url(connection.baseUrl.trimEnd('/') + "/chat/stream")
            .header("Authorization", "Bearer " + connection.token)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-store")
            .apply { if (isolated) header("X-Mate-Chat-Version", "4") }
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val call = client.newCall(request)
        emit(StreamChunk.TextStart(partId))
        val handle = cancelCallWithRequest(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val reason = if (response.code == 409) {
                        val code = runCatching {
                            json.parseToJsonElement(response.peekBody(1024).string())
                                .jsonObject["error"]?.jsonPrimitive?.contentOrNull
                        }.getOrNull()
                        when (code) {
                            "another_request_running" -> "服务器正在处理另一条消息，请等它结束后再发送"
                            "request_id_conflict" -> "请求编号已用于其他内容，请检查原请求状态"
                            "revision_conflict" -> "服务器数据已更新，请刷新后再试"
                            else -> "服务器报告请求冲突，请检查请求状态"
                        }
                    } else "发送失败，网关返回 HTTP " + response.code
                    throw IOException(reason)
                }
                prefs.edit().putString("request_" + connection.instanceId, requestId).apply()
                val summary = if (ordered) "网关已接收有序上下文，匹配规则 " + params.cloudInjectionCount + " 条"
                    else if (isolated) "独立会话，未发送注入规则" else "旧版网关使用共享会话，未发送注入规则"
                prefs.edit().putString(diagnosticKey, summary).apply()
                val contentType = response.header("Content-Type", "").orEmpty()
                if (!contentType.startsWith("text/event-stream")) {
                    throw IOException("服务器返回的不是流式响应")
                }
                val source = response.body?.source() ?: throw IOException("服务器没有返回内容")

                val parser = MateSseParser()
                var accumulated = ""
                var terminal = false
                while (true) {
                    val line = try {
                        if (source.exhausted()) null else source.readUtf8LineStrict(800_000L)
                    } catch (interrupted: IOException) {
                        currentCoroutineContext().ensureActive()
                        null
                    }
                    val eof = line == null
                    val payloadText = parser.line(line.orEmpty())
                    if (payloadText != null) {
                        val event = MateWire.parseSnapshot(payloadText, requestId)
                        if (event.type == "error" || event.state in setOf("error", "failed", "expired", "uncertain")) {
                            throw IOException("服务器未完成这条消息，请检查会话状态")
                        }
                        if (!event.text.startsWith(accumulated)) {
                            throw IOException("服务器回复与已接收的内容不一致")
                        }
                        if (event.text.length > accumulated.length) {
                            emit(StreamChunk.TextDelta(partId, event.text.substring(accumulated.length)))
                            accumulated = event.text
                        }
                        terminal = event.type == "done" || event.state == "done"
                    }
                    if (terminal || eof) break
                }
                if (terminal && accumulated.isBlank()) throw IOException("网关没有返回可显示的文字，请检查回复类型或请求状态")
                if (!terminal) {
                    currentCoroutineContext().ensureActive()
                    val recovered = transport.requestResult(connection, requestId)
                    if (recovered.state != "done") {
                        throw IOException(if (recovered.state == "running") "连接已中断，服务器仍在生成，请勿重复发送"
                            else "连接已中断，服务器未确认生成成功")
                    }
                    if (recovered.text.isBlank() || !recovered.text.startsWith(accumulated)) {
                        throw IOException("恢复结果与已接收的回复不一致")
                    }
                    if (recovered.text.length > accumulated.length) {
                        emit(StreamChunk.TextDelta(partId, recovered.text.substring(accumulated.length)))
                    }
                }
            }
        } finally {
            handle.cancel()
        }
        emit(StreamChunk.TextEnd(partId))
        emit(StreamChunk.Finish(finishReason = "stop", responseId = requestId, model = params.model.modelId))
    }.catch { error ->
        if (error is IOException) runCatching { MateDiagnostics.enqueue(appContext, DiagnosticEvent.CLOUD_CHAT_FAILED) }
        throw error
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

    // A completion handler runs after a blocking read returns. This child receives
    // parent cancellation immediately and closes the socket to interrupt that read.
    private suspend fun cancelCallWithRequest(call: okhttp3.Call) =
        CoroutineScope(currentCoroutineContext()).launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
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
        val handle = cancelCallWithRequest(call)
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
            handle.cancel()
        }
    }

}
