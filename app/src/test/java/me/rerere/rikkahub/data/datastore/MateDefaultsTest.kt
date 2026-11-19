package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MateDefaultsTest {
    @Test fun onlyOneDefaultAssistant() {
        assertEquals(1, DEFAULT_ASSISTANTS.size)
        assertEquals(DEFAULT_ASSISTANT_ID, DEFAULT_ASSISTANTS.single().id)
    }

    @Test fun noAffiliateAggregatorInDefaultProviders() {
        val urls = DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.OpenAI>().map { it.baseUrl }
        assertFalse(urls.any { it.contains("302.ai") || it.contains("openrouter.ai") ||
            it.contains("ai-gateway.vercel.sh") })
        assertTrue(DEFAULT_PROVIDERS.any { it.name == "DeepSeek" })
    }

    @Test fun userConfiguredLegacyProviderMustBePreserved() {
        val old = ProviderSetting.OpenAI(
            id = Uuid.parse("da93779f-3956-48cc-82ef-67bb482eaaf7"),
            name = "302.AI",
            baseUrl = "https://api.302.ai/v1"
        )
        assertTrue(old.isUnusedLegacyPromotion())
        assertFalse(old.copy(apiKey = "user-custom-key").isUnusedLegacyPromotion())
        assertFalse(old.copy(name = "我的服务").isUnusedLegacyPromotion())
        assertFalse(old.copy(baseUrl = "https://api.example.net/v1").isUnusedLegacyPromotion())
    }

    @Test fun secondaryAssistantRemovedOnlyWhenUntouched() {
        val legacyId = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0")
        val prompt = """
            You are a helpful assistant, called {{char}}, based on model {{model_name}}.

            ## Info
            - Date: {{cur_date}}

            ## Hint
            - If the user does not specify a language, reply in the user's primary language.
        """.trimIndent()
        val original = Assistant(id = legacyId, systemPrompt = prompt)
        assertTrue(original.isUnusedLegacySecondaryAssistant())
        assertFalse(original.copy(name = "我的助手").isUnusedLegacySecondaryAssistant())
        assertFalse(original.copy(systemPrompt = "My persona").isUnusedLegacySecondaryAssistant())
        assertFalse(original.copy(enableMemory = true).isUnusedLegacySecondaryAssistant())
    }
}
