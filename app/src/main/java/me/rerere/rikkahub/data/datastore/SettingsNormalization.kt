package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * Legacy sponsored/aggregator presets. Never delete an entry with a token,
 * models, custom headers or an edited name/URL.
 * The upstream provider catalog is no longer reintroduced in Mate.
 */
internal val LEGACY_PROMOTED_PRESETS = mapOf(
    Uuid.parse("d5734028-d39b-4d41-9841-fd648d65440e") to ("OpenRouter" to "https://openrouter.ai/api/v1"),
    Uuid.parse("386e0f29-8228-4512-affe-8fd8add82d88") to ("Vercel AI Gateway" to "https://ai-gateway.vercel.sh/v1"),
    Uuid.parse("da93779f-3956-48cc-82ef-67bb482eaaf7") to ("302.AI" to "https://api.302.ai/v1")
)

internal fun ProviderSetting.isUnusedLegacyPromotion(): Boolean {
    val expected = LEGACY_PROMOTED_PRESETS[id] ?: return false
    return this is ProviderSetting.OpenAI &&
        name == expected.first && baseUrl == expected.second &&
        apiKey.isBlank() && models.isEmpty() && customHeaders.isEmpty()
}

/** Only prune the old second assistant if its settings match the untouched preset. */
internal fun Assistant.isUnusedLegacySecondaryAssistant(): Boolean {
    val legacyId = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0")
    return id == legacyId &&
        systemPrompt.trim().startsWith("You are a helpful assistant, called {{char}}, based on model {{model_name}}.") &&
        systemPrompt.contains("## Info") &&
        systemPrompt.contains("## Hint") &&
        copy(systemPrompt = "") == Assistant(id = legacyId, systemPrompt = "")
}

/** 读盘后和每次修改后都要过一遍，保证内存里的设置始终是规整的。 */
internal fun Settings.normalized(): Settings =
    withBuiltInDefaults().withoutInvalidReferences().withValuesInRange()

// 补齐缺失的内置提供商/助手/TTS，内置提供商的描述信息以代码里的为准
internal fun Settings.withBuiltInDefaults(): Settings {
    var providers = this.providers.ifEmpty { DEFAULT_PROVIDERS }.filterNot { it.isUnusedLegacyPromotion() || it.id == LEGACY_MATE_PROVIDER_ID }.toMutableList()
    DEFAULT_PROVIDERS.forEach { defaultProvider ->
        if (providers.none { it.id == defaultProvider.id }) {
            providers.add(defaultProvider.copyProvider())
        }
    }
    providers = providers.map { provider ->
        val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
        when {
            defaultProvider != null -> {
                val updated = provider.copyProvider(
                    builtIn = defaultProvider.builtIn,
                    description = defaultProvider.description,
                    shortDescription = defaultProvider.shortDescription,
                )
                updated
            }

            else -> provider
        }
    }.toMutableList()
    // 内置助手可以删除，只在一个都不剩时补回，不按 id 逐个补齐
    val assistants = this.assistants.ifEmpty { DEFAULT_ASSISTANTS }
        .filterNot { it.isUnusedLegacySecondaryAssistant() }
        .map { assistant ->
            when {
                assistant.chatModelId == LEGACY_MATE_MODEL_ID -> assistant.copy(chatModelId = null)
                assistant.id == DEFAULT_ASSISTANT_ID && assistant.name.isBlank() -> assistant.copy(name = "李鱼")
                else -> assistant
            }
        }
        .ifEmpty { DEFAULT_ASSISTANTS }
    val ttsProviders = this.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }
        .filterNot { it is me.rerere.tts.provider.TTSProviderSetting.OpenAI &&
            it.name == "AiHubMix" && it.baseUrl == "https://aihubmix.com/v1" &&
            it.apiKey.isBlank() }
        .toMutableList()
    DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
        if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
            ttsProviders.add(defaultTTSProvider.copyProvider())
        }
    }
    return copy(
        chatModelId = chatModelId.takeUnless { it == LEGACY_MATE_MODEL_ID },
        providers = providers,
        assistants = assistants,
        ttsProviders = ttsProviders,
    )
}

// 去重并清理无效引用
internal fun Settings.withoutInvalidReferences(): Settings {
    val settings = this
    val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
    val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
    val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
    val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
    val validModelIds by lazy { settings.providers.flatMap { it.models }.mapTo(HashSet()) { it.id } }
    val asrProviders = settings.asrProviders.distinctBy { it.id }
    return settings.copy(
        // 选中的助手被删除后改选第一个，避免直接读 assistantId 的地方拿到失效的 ID
        assistantId = settings.assistantId
            .takeIf { id -> settings.assistants.any { it.id == id } }
            ?: settings.assistants.firstOrNull()?.id
            ?: settings.assistantId,
        providers = settings.providers.distinctBy { it.id }.map { provider ->
            provider.copyProvider(models = provider.models.distinctBy { model -> model.id })
        },
        assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
            assistant.copy(
                // 过滤掉不存在的 MCP 服务器 ID
                mcpServers = assistant.mcpServers.filter { serverId ->
                    serverId in validMcpServerIds
                }.toSet(),
                // 过滤掉不存在的模式注入 ID
                modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                    id in validModeInjectionIds
                }.toSet(),
                // 过滤掉不存在的 Lorebook ID
                lorebookIds = assistant.lorebookIds.filter { id ->
                    id in validLorebookIds
                }.toSet(),
                // 过滤掉不存在的快捷消息 ID
                quickMessageIds = assistant.quickMessageIds.filter { id ->
                    id in validQuickMessageIds
                }.toSet()
            )
        },
        ttsProviders = settings.ttsProviders.distinctBy { it.id },
        asrProviders = asrProviders,
        selectedASRProviderId = settings.selectedASRProviderId
            ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
            ?: asrProviders.firstOrNull()?.id,
        favoriteModels = settings.favoriteModels.filter { it in validModelIds }.distinct(),
        modeInjections = settings.modeInjections.distinctBy { it.id },
        lorebooks = settings.lorebooks.distinctBy { it.id },
        quickMessages = settings.quickMessages.distinctBy { it.id },
    )
}

// 把取值收回合法范围，比如删掉搜索服务后选中的下标可能越界
private fun Settings.withValuesInRange(): Settings = copy(
    searchServiceSelected = searchServiceSelected.coerceIn(0, (searchServices.size - 1).coerceAtLeast(0)),
    defaultTTSPlaybackSpeed = defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f),
    autoCompressThreshold = autoCompressThreshold.coerceIn(AUTO_COMPRESS_THRESHOLD_RANGE),
)

/** 自动压缩触发比例的可选范围，设置页的滑块也用它 */
val AUTO_COMPRESS_THRESHOLD_RANGE = 0.5f..0.95f
