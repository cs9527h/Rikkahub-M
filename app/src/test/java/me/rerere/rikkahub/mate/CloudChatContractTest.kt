package me.rerere.rikkahub.mate

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.transformers.transformMessages
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.InjectionPosition
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudChatContractTest {
    @Test fun boundRuleReachesGatewayAsSystemOnlyWhenEnabled() {
        val mock = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        mock.createContext("/chat/stream") { exchange ->
            val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            val rows = body.getJSONArray("context_messages")
            val applied = (0 until rows.length()).any {
                val row = rows.getJSONObject(it)
                row.getString("role") == "system" && row.getString("content").contains("fixture-rule")
            }
            val reply = JSONObject().put("id", body.getString("request_id")).put("state", "done")
                .put("type", "done").put("text", if (applied) "on" else "off")
            val frame = "data: $reply\n\n".toByteArray()
            exchange.sendResponseHeaders(200, frame.size.toLong())
            exchange.responseBody.use { it.write(frame) }
        }
        mock.start()
        try {
            val connection = MateConnection("http://127.0.0.1:" + mock.address.port, "fixture", "fixture")
            val client = OkHttpClient()
            val rule = PromptInjection.ModeInjection(content = "fixture-rule", position = InjectionPosition.AFTER_SYSTEM_PROMPT)
            val assistant = Assistant(modeInjectionIds = setOf(rule.id))
            for (enabled in listOf(true, false)) {
                val messages = transformMessages(listOf(UIMessage.user("hello")), assistant,
                    listOf(rule.copy(enabled = enabled)), emptyList())
                val id = if (enabled) "fixture-on" else "fixture-off"
                val request = cloudChatRequest(connection, id, "00000000-0000-4000-8000-000000000001",
                    "hello", emptyList(), messages, true)
                client.newCall(request).execute().use { response ->
                    val parser = MateSseParser()
                    val parsed = response.body!!.string().lineSequence().mapNotNull(parser::line).first()
                    assertEquals(if (enabled) "on" else "off", MateWire.parseSnapshot(parsed, id).text)
                }
            }
        } finally {
            mock.stop(0)
        }
    }
}
