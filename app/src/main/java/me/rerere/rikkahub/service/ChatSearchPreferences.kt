package me.rerere.rikkahub.service

import me.rerere.ai.provider.BuiltInTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantSearchDefault
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.getChatModelOf
import kotlin.uuid.Uuid

/** An explicit mode selection, not an assistant/settings snapshot captured by the UI. */
internal data class ChatSearchChange(
    val enableWebSearch: Boolean?,
    val builtInSearch: Boolean?,
) {
    /** LOCAL wins for a contradictory API patch; enabling either mode always disables the other. */
    fun exclusive(): ChatSearchChange = when {
        enableWebSearch == true -> copy(builtInSearch = false)
        builtInSearch == true -> copy(enableWebSearch = false)
        else -> this
    }

    fun applyToConversation(conversation: Conversation): Conversation {
        val config = conversation.config ?: return conversation
        return conversation.copy(config = config.copy(
            enableWebSearch = enableWebSearch ?: config.enableWebSearch,
            builtInSearch = builtInSearch ?: config.builtInSearch,
        ))
    }

    /** Merge one preference into the actual owner's latest record; never use the selected assistant as fallback. */
    fun rememberLocalDefault(settings: Settings, assistantId: Uuid): Settings {
        val enabled = enableWebSearch ?: return settings
        val stored = settings.assistants.firstOrNull { it.id == assistantId } ?: return settings
        val searchDefault = when {
            builtInSearch == true -> null
            enabled -> AssistantSearchDefault.LOCAL
            else -> AssistantSearchDefault.OFF
        }
        if (stored.enableWebSearch == enabled && stored.searchDefault == searchDefault) return settings
        return settings.copy(assistants = settings.assistants.map { assistant ->
            if (assistant.id == assistantId) {
                assistant.copy(
                    enableWebSearch = enabled,
                    searchDefault = searchDefault,
                )
            } else {
                assistant
            }
        })
    }

    /** Preserve the old explicit BUILT_IN-on draft lifecycle, without spreading a stale model to every provider. */
    fun enableDraftBuiltInSearch(settings: Settings, conversation: Conversation): Settings {
        if (conversation.config != null || builtInSearch != true) return settings
        if (settings.assistants.none { it.id == conversation.assistantId }) return settings
        val modelId = settings.getChatModelOf(conversation)?.id ?: return settings
        if (settings.providers.sumOf { provider -> provider.models.count { it.id == modelId } } != 1) return settings
        val ownerIndex = settings.providers.indexOfFirst { provider -> provider.models.any { it.id == modelId } }
        if (ownerIndex < 0) return settings
        val owner = settings.providers[ownerIndex]
        val models = owner.models.map { model ->
            if (model.id == modelId && BuiltInTools.Search !in model.tools) {
                model.copy(tools = model.tools + BuiltInTools.Search)
            } else {
                model
            }
        }
        if (models == owner.models) return settings
        return settings.copy(providers = settings.providers.mapIndexed { index, provider ->
            if (index == ownerIndex) provider.copyProvider(models = models) else provider
        })
    }
}

/** A delayed model/name edit must not write its unedited search preference pair over a newer choice. */
internal fun Assistant.mergeUnchangedChatSearchDefault(
    before: Assistant,
    edited: Assistant,
    latest: Assistant,
): Assistant = if (edited.searchDefault == before.searchDefault && edited.enableWebSearch == before.enableWebSearch) {
    copy(searchDefault = latest.searchDefault, enableWebSearch = latest.enableWebSearch)
} else {
    this
}

/** Session metadata serialization also orders the remembered default against another search click/first flush. */
internal suspend fun updateChatSearchPreferences(
    session: ConversationSession,
    enableWebSearch: Boolean?,
    builtInSearch: Boolean?,
    updateSettings: suspend ((Settings) -> Settings) -> Unit,
    persistConfig: suspend (Conversation) -> Unit,
) {
    if (enableWebSearch == null && builtInSearch == null) return
    val change = ChatSearchChange(enableWebSearch, builtInSearch).exclusive()
    val (incarnation, assistantId) = synchronized(session) { session.incarnation to session.state.value.assistantId }
    fun stillOwned() = session.incarnation == incarnation && session.state.value.assistantId == assistantId
    var accepted = false
    session.updateMetadata(
        expectedIncarnation = incarnation,
        transform = { latest ->
            accepted = latest.assistantId == assistantId
            if (accepted) change.applyToConversation(latest) else latest
        },
        persist = { current ->
            if (accepted && stillOwned()) {
                updateSettings { latest ->
                    if (!stillOwned()) {
                        latest
                    } else {
                        val remembered = change.rememberLocalDefault(latest, assistantId)
                        change.enableDraftBuiltInSearch(remembered, current)
                    }
                }
                if (current.config != null && stillOwned()) persistConfig(session.state.value)
            }
        },
    )
}
