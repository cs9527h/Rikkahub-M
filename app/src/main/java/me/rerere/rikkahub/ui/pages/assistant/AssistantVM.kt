package me.rerere.rikkahub.ui.pages.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.AvatarStorage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.service.ChatService

class AssistantVM(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val chatService: ChatService,
    private val avatarStorage: AvatarStorage,
    private val appScope: AppScope,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun updateSettings(fn: (Settings) -> Settings) {
        viewModelScope.launch {
            saveSettings(fn)
        }
    }

    fun addAssistant(assistant: Assistant) {
        viewModelScope.launch {
            saveSettings { it.copy(assistants = it.assistants.plus(assistant)) }
        }
    }

    fun removeAssistant(assistant: Assistant) {
        // 助手从列表移除后用户无法再次触发清理，放到应用级作用域里，离开页面也要跑完
        appScope.launch {
            // 在同一次更新里按最新列表检查，连续删除时不会把最后一个助手也删掉
            var removed: Assistant? = null
            saveSettings { settings ->
                val target = settings.assistants.find { it.id == assistant.id }
                if (target == null || settings.assistants.size <= 1) return@saveSettings settings
                removed = target
                settings.copy(assistants = settings.assistants.filter { it.id != target.id })
            }
            // 只有真的删掉了才清理关联数据
            val target = removed ?: return@launch
            cleanupAssistantFiles(target)
            memoryRepository.deleteFilesOfAssistant(target.id.toString())
            chatService.deleteConversationsOfAssistant(target.id)
        }
    }

    private suspend fun saveSettings(fn: (Settings) -> Settings) {
        var before: Settings? = null
        var saved: Settings? = null
        settingsStore.update { current ->
            before = current
            fn(current).also { saved = it }
        }
        avatarStorage.onSettingsSaved(checkNotNull(before), checkNotNull(saved))
    }

    private suspend fun cleanupAssistantFiles(assistant: Assistant) {
        assistant.background?.let { avatarStorage.deleteLocalUriIfUnused(it) }
    }

    fun copyAssistant(assistant: Assistant, copyMemories: Boolean = false) {
        viewModelScope.launch {
            val copiedId = kotlin.uuid.Uuid.random()
            saveSettings { latest ->
                // The page snapshot may predate avatar migration; clone the current reference, not a deleted upload.
                val source = latest.assistants.find { it.id == assistant.id } ?: return@saveSettings latest
                val copiedAssistant = source.copy(id = copiedId, name = "${source.name} (Clone)")
                latest.copy(assistants = latest.assistants.plus(copiedAssistant))
            }
            if (copyMemories && settingsStore.settingsFlow.value.assistants.any { it.id == copiedId }) {
                memoryRepository.copyFiles(
                    fromAssistantId = assistant.id.toString(),
                    toAssistantId = copiedId.toString(),
                )
            }
        }
    }

    fun getMemories(assistant: Assistant) = memoryRepository.getFilesFlow(assistant.memoryId)
}
