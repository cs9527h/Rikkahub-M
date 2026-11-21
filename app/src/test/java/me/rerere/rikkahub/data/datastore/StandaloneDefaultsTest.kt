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

    @Test fun invalidAutomaticBackupIntervalsAreClampedInsteadOfBreakingEveryRun() {
        val normalized = Settings(
            backupReminderConfig = BackupReminderConfig(
                autoSettingsIntervalHours = 0,
                autoChatsIntervalHours = -24,
            ),
        ).normalized()
        assertEquals(1, normalized.backupReminderConfig.autoSettingsIntervalHours)
        assertEquals(1, normalized.backupReminderConfig.autoChatsIntervalHours)
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
        // 全局 chatModelId 是非空 Uuid，不能置空：有可用模型时回落到本地模型，
        // 没有任何模型时保留旧值但必须解析失败，由引导页提示用户选择模型。
        if (migrated.providers.any { it.models.isNotEmpty() }) {
            assertTrue(migrated.chatModelId != LEGACY_MATE_MODEL_ID)
            assertTrue(migrated.providers.flatMap { it.models }.any { it.id == migrated.chatModelId })
        } else {
            assertNull(migrated.findModelById(migrated.chatModelId))
        }
        assertNull(migrated.getCurrentAssistant().chatModelId)
        assertEquals("kept persona", migrated.getCurrentAssistant().systemPrompt)
        assertTrue(migrated.providers.any { it.name == "DeepSeek" })
    }
}
