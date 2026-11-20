package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneDefaultsTest {
    @Test fun defaultAssistantHasNoServerModel() {
        assertEquals(1, DEFAULT_ASSISTANTS.size)
        assertEquals("李鱼", DEFAULT_ASSISTANTS.single().name)
        assertNull(DEFAULT_ASSISTANTS.single().chatModelId)
        assertFalse(DEFAULT_PROVIDERS.any { it.id == LEGACY_MATE_PROVIDER_ID })
    }
    @Test fun upgradingRemovesGatewayNotPersonality() {
        val gateway = ProviderSetting.OpenAI(
            id = LEGACY_MATE_PROVIDER_ID,
            name = "云服务器",
            baseUrl = "",
            chatCompletionsPath = "/chat/stream"
        )
        val assistant = Assistant(
            id = DEFAULT_ASSISTANT_ID,
            name = "李鱼",
            chatModelId = LEGACY_MATE_MODEL_ID,
            systemPrompt = "kept persona"
        )
        val migrated = Settings(
            providers = listOf(gateway),
            assistants = listOf(assistant),
            assistantId = assistant.id,
            chatModelId = LEGACY_MATE_MODEL_ID
        ).normalized()
        assertFalse(migrated.providers.any { it.id == LEGACY_MATE_PROVIDER_ID })
        assertNull(migrated.chatModelId)
        assertNull(migrated.getCurrentAssistant().chatModelId)
        assertEquals("kept persona", migrated.getCurrentAssistant().systemPrompt)
        assertTrue(migrated.providers.any { it.name == "DeepSeek" })
    }
}
