package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatCompletionsImageDataUriTest {
    @Test
    fun `stream parser preserves complete PNG JPEG and WebP data URIs`() {
        listOf("png", "jpeg", "webp").forEach { format ->
            val uri = "data:image/$format;base64,aGVsbG8rLz0="
            val decoder = ChatCompletionsStreamDecoder()
            val payload = imageMessage(uri)
            val parse = ChatCompletionsStreamDecoder::class.java.getDeclaredMethod(
                "parseMessage",
                JsonObject::class.java
            )
            parse.isAccessible = true
            val parsed = parse.invoke(decoder, payload) as UIMessage
            assertEquals(uri, parsed.parts.filterIsInstance<UIMessagePart.Image>().single().url)
        }
    }

    @Test
    fun `stream events reconstruct the exact original data URI for every image MIME`() {
        listOf("png", "jpeg", "webp").forEach { format ->
            val uri = "data:image/$format;base64,aGVsbG8rLz0="
            val decoder = ChatCompletionsStreamDecoder()
            val chunks = decoder.accept(SseEvent(
                data = """{"id":"image-response","choices":[{"delta":${imageMessage(uri)}}]}"""
            )).chunks + decoder.onClosed()
            val handler = StreamChunkHandler()
            val messages = chunks.fold(
                listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
            ) { messages, chunk ->
                handler.handle(messages, chunk)
            }
            assertEquals(uri, messages.single().parts.filterIsInstance<UIMessagePart.Image>().single().url)
        }
    }

    @Test
    fun `non streaming parser preserves the same image URI`() {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val parse = ChatCompletionsAPI::class.java.getDeclaredMethod("parseMessage", JsonObject::class.java)
        parse.isAccessible = true
        listOf("png", "jpeg", "webp").forEach { format ->
            val uri = "data:image/$format;base64,aGVsbG8rLz0="
            val parsed = parse.invoke(api, imageMessage(uri)) as UIMessage
            assertEquals(uri, parsed.parts.filterIsInstance<UIMessagePart.Image>().single().url)
        }
    }

    private fun imageMessage(uri: String): JsonObject = json.parseToJsonElement(
        """{"role":"assistant","images":[{"type":"image_url","image_url":{"url":"$uri"}}]}"""
    ).jsonObject
}
