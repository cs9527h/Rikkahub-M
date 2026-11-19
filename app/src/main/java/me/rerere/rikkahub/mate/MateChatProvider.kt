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

    private val store = MateGatewayStore(context.applicationContext)
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
            ?: throw IOException("还没有连接李鱼服务器，请先在「连接与设备」里完成配对")

        val userText = messages.lastOrNull { it.role == MessageRole.USER }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }
            ?.trim()
            .orEmpty()
        require(userText.isNotEmpty()) { "没有可以发送的内容" }
        require(userText.length <= 6000) { "单条消息不能超过 6000 字" }

        val requestId = UUID.randomUUID().toString()
        val partId = "mate-" + requestId
        val payload = """{"request_id":"$requestId","message":${jsonString(userText)},"attachments":[]}"""
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
}
