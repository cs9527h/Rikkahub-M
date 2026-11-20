package me.rerere.rikkahub.data.ai

import android.content.Context
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale

/** 用快速模型生成会话的标题和聊天建议，只负责请求模型，写回会话由调用方处理 */
class ConversationMetaGenerator(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
) {
    /** 快速模型或它的提供商找不到时抛出异常，提示用户检查设置 */
    suspend fun generateTitle(settings: Settings, conversation: Conversation): String {
        // 快速模型可能指向已被删除的模型，退回当前会话模型；再不行就用本地标题
        val model = settings.findModelById(settings.fastModelId) ?: settings.getCurrentChatModel()
        val provider = model?.findProvider(settings.providers)
        if (model == null || provider == null) return localTitle(conversation)
        return generate(
            settings,
            model,
            provider,
            conversation,
            settings.titlePrompt,
            recentMessages = 4,
            UsageSource.TITLE
        )
            .trim()
    }

    /** 没有配置快速模型时返回 null */
    suspend fun generateSuggestions(settings: Settings, conversation: Conversation): List<String>? {
        val model = settings.findModelById(settings.fastModelId) ?: return null
        val provider = model.findProvider(settings.providers) ?: return null
        return generate(
            settings,
            model,
            provider,
            conversation,
            settings.suggestionPrompt,
            recentMessages = 8,
            UsageSource.SUGGESTION,
        )
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(10)
    }

    /** 没有可用快速模型时的本地标题：取第一条用户消息，不碰服务器 */
    private fun localTitle(conversation: Conversation): String =
        localTitleFrom(conversation.currentMessages, context.getString(R.string.chat_page_new_chat))

    private suspend fun generate(
        settings: Settings,
        model: Model,
        provider: ProviderSetting,
        conversation: Conversation,
        prompt: String,
        recentMessages: Int,
        source: UsageSource,
    ): String {
        val result = usageRecorder.wrap(
            providerManager.getProviderByType(provider),
            UsageTag(source, conversation.assistantId, conversation.id),
        ).generateText(
            providerSetting = provider,
            messages = listOf(
                UIMessage.user(
                    prompt.applyPlaceholders(
                        "locale" to Locale.getDefault().displayName,
                        "content" to conversation.currentMessages
                            .takeLast(recentMessages).joinToString("\n\n") { it.summaryAsText(maxLength = 500) },
                    )
                ),
            ),
            params = backgroundTextGenerationParams(model, conversation.id, settings.fastModelReasoningLevel),
        )
        return result.message.toText()
    }
}

/**
 * 纯函数版本的本地标题：取第一条用户消息压成一行后截断。
 * 当没有可用模型时不请求远端服务，保证新会话也有可读标题。
 */
internal fun localTitleFrom(
    messages: List<UIMessage>,
    fallback: String,
): String {
    val text = messages
        .firstOrNull { it.role == MessageRole.USER }
        ?.toText()
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        .orEmpty()
    return text.take(24).ifBlank { fallback }
}
