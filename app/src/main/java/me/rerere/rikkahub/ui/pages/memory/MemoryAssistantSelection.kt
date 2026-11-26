package me.rerere.rikkahub.ui.pages.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.context.Navigator

/** Each assistant keeps its own route/ViewModel; never change Settings.assistantId to browse settings. */
internal fun Navigator.openMemoryAssistant(currentId: String, selectedId: String) {
    require(selectedId.isNotBlank())
    if (currentId == selectedId) return
    val target = Screen.MemoryCenter(assistantId = selectedId)
    navigate(target) {
        // Revisit an existing assistant rather than stacking duplicate pages on repeated switches.
        popUpTo(target)
        launchSingleTop = true
    }
}

/** Only the generic settings entry follows the chat assistant. Missing explicit IDs never fall back. */
internal fun Settings.memorySettingsAssistant(requestedId: String?): Assistant? =
    if (requestedId != null) {
        assistants.find { it.id.toString() == requestedId }
    } else {
        assistants.find { it.id == assistantId } ?: assistants.firstOrNull()
    }

internal fun Flow<Settings>.memorySettingsAssistantFlow(requestedId: String?): Flow<Assistant> =
    map { it.memorySettingsAssistant(requestedId) }.filterNotNull()

/** Merge only the fields actually changed in this UI, onto the latest persisted assistant. */
internal fun Settings.updateMemoryAssistant(before: Assistant, updated: Assistant): Settings {
    require(before.id == updated.id)
    return copy(assistants = assistants.map { current ->
        if (current.id != updated.id) {
            current
        } else {
            current.copy(
                enableMemory = memoryField(current.enableMemory, before.enableMemory, updated.enableMemory),
                useGlobalMemory = memoryField(current.useGlobalMemory, before.useGlobalMemory, updated.useGlobalMemory),
                autoConsolidateMemory = memoryField(
                    current.autoConsolidateMemory,
                    before.autoConsolidateMemory,
                    updated.autoConsolidateMemory
                ),
                memoryRecallTokenBudget = memoryField(
                    current.memoryRecallTokenBudget,
                    before.memoryRecallTokenBudget,
                    updated.memoryRecallTokenBudget
                ),
                enableTimeReminder = memoryField(
                    current.enableTimeReminder,
                    before.enableTimeReminder,
                    updated.enableTimeReminder
                ),
                timeReminderIntervalMinutes = memoryField(
                    current.timeReminderIntervalMinutes,
                    before.timeReminderIntervalMinutes,
                    updated.timeReminderIntervalMinutes
                ),
                enableRecentChatsReference = memoryField(
                    current.enableRecentChatsReference,
                    before.enableRecentChatsReference,
                    updated.enableRecentChatsReference
                ),
                recordMemoryUsage = memoryField(
                    current.recordMemoryUsage,
                    before.recordMemoryUsage,
                    updated.recordMemoryUsage
                ),
            )
        }
    })
}

private fun <T> memoryField(current: T, before: T, updated: T): T = if (before != updated) updated else current
