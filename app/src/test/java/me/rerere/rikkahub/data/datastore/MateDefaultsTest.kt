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

    @Test fun defaultLiYuAssistantHasResolvableModel() {
        val settings = Settings().normalized()
        val model = settings.getCurrentChatModel()
        assertEquals("李鱼", settings.getCurrentAssistant().name)
        assertEquals(MATE_MODEL_ID, model?.id)
        assertEquals("liyu", model?.modelId)
        assertEquals(
            MATE_PROVIDER_ID,
            settings.providers.single { provider ->
                provider.models.any { it.id == MATE_MODEL_ID }
            }.id,
        )
        assertFalse(settings.isNotConfigured())
    }

    @Test fun upgradingSettingsRecoversMissingMateProvider() {
        val legacySettings = Settings(
            providers = DEFAULT_PROVIDERS.filterNot { it.id == MATE_PROVIDER_ID },
            assistants = DEFAULT_ASSISTANTS,
        )
        assertTrue(legacySettings.getCurrentChatModel() == null)
        val normalized = legacySettings.normalized()
        assertEquals(MATE_MODEL_ID, normalized.getCurrentChatModel()?.id)
        assertEquals(1, normalized.providers.count { it.id == MATE_PROVIDER_ID })
        assertFalse(normalized.isNotConfigured())
    }

    @Test fun normalizedSettingsKeepUserProvidersAndAssistants() {
        val customProvider = ProviderSetting.OpenAI(
            id = Uuid.parse("aa11bb22-cc33-dd44-ee55-ff6677889900"),
            name = "我的服务",
            baseUrl = "https://api.example.net/v1",
            apiKey = "user-key",
            models = listOf(me.rerere.ai.provider.Model(modelId = "my-model", displayName = "我的模型")),
        )
        val customAssistant = Assistant(
            id = Uuid.parse("bb22cc33-dd44-ee55-ff66-77889900aabb"),
            name = "我的助手",
            chatModelId = customProvider.models.single().id,
        )
        val settings = Settings(
            providers = listOf(customProvider),
            assistants = listOf(customAssistant),
            assistantId = customAssistant.id,
            chatModelId = customProvider.models.single().id,
        ).normalized()

        // 用户自己的服务商与助手原样保留，不被内置项覆盖
        assertTrue(settings.providers.any { it.id == customProvider.id })
        assertTrue(settings.assistants.any { it.id == customAssistant.id })
        assertEquals(customAssistant.id, settings.getCurrentAssistant().id)
        assertEquals("我的服务", settings.providers.single { it.id == customProvider.id }.name)
        // 同时把缺失的内置 Mate 服务端补回来，且只补一次
        assertEquals(1, settings.providers.count { it.id == MATE_PROVIDER_ID })
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
