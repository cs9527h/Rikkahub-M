package me.rerere.rikkahub.ui.pages.memory

import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.context.Navigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryAssistantSelectionTest {
    private val a = Assistant(name = "A")
    private val b = Assistant(name = "B")
    private val settings = Settings(assistantId = a.id, assistants = listOf(a, b))

    @Test fun `choosing the displayed assistant keeps the current page`() {
        val initial = listOf<NavKey>(Screen.Setting, Screen.MemoryCenter())
        val stack = initial.toMutableList()
        Navigator(stack).openMemoryAssistant(a.id.toString(), a.id.toString())
        assertEquals(initial, stack)
    }

    @Test fun `choosing another assistant opens its explicitly scoped memory page`() {
        val stack = mutableListOf<NavKey>(Screen.Setting, Screen.MemoryCenter())
        Navigator(stack).openMemoryAssistant(a.id.toString(), b.id.toString())
        assertEquals(Screen.MemoryCenter(b.id.toString()), stack.last())
        assertEquals(Screen.Setting, stack.first())
        assertEquals(a.id, settings.assistantId)
    }

    @Test fun `revisiting an assistant reuses its route instead of accumulating duplicates`() {
        val target = Screen.MemoryCenter(b.id.toString())
        val stack = mutableListOf<NavKey>(Screen.Setting, Screen.MemoryCenter(), target, Screen.MemoryCenter("other"))
        Navigator(stack).openMemoryAssistant("other", b.id.toString())
        assertEquals(listOf<NavKey>(Screen.Setting, Screen.MemoryCenter(), target), stack)
        assertEquals(1, stack.count { it == target })
    }

    @Test fun `switching settings never carries an old memory file deep link`() {
        val stack = mutableListOf<NavKey>(Screen.Setting, Screen.MemoryCenter(a.id.toString(), "private/a.md"))
        val navigator = Navigator(stack)
        navigator.openMemoryAssistant(a.id.toString(), b.id.toString())
        assertEquals(null, (stack.last() as Screen.MemoryCenter).path)
    }

    @Test fun `editing B does not switch the chat assistant or change A`() {
        val result = settings.updateMemoryAssistant(b, b.copy(enableMemory = true))
        assertEquals(a.id, result.assistantId)
        assertEquals(a, result.assistants.first())
        assertTrue(result.assistants.last().enableMemory)
    }

    @Test fun `memory patch retains latest name prompt model and concurrent memory fields`() {
        val latest = b.copy(
            name = "Renamed",
            systemPrompt = "new prompt",
            chatModelId = Uuid.random(),
            temperature = 0.7f,
            useGlobalMemory = true
        )
        val current = settings.copy(assistants = listOf(a, latest))
        val result = current.updateMemoryAssistant(b, b.copy(enableMemory = true))
        assertEquals(latest.copy(enableMemory = true), result.assistants.last())
    }

    @Test fun `rapid updates from one rendered snapshot keep both changed fields`() {
        val first = settings.updateMemoryAssistant(b, b.copy(enableMemory = true))
        val second = first.updateMemoryAssistant(b, b.copy(autoConsolidateMemory = false))
        val changed = second.assistants.last()
        assertTrue(changed.enableMemory)
        assertFalse(changed.autoConsolidateMemory)
    }

    @Test fun `all editable memory and reminder fields are merged without other fields`() {
        val updated = b.copy(
            name = "must not overwrite", systemPrompt = "must not overwrite",
            enableMemory = true, useGlobalMemory = true, autoConsolidateMemory = false,
            memoryRecallTokenBudget = 4000, enableTimeReminder = true,
            timeReminderIntervalMinutes = 20, enableRecentChatsReference = true, recordMemoryUsage = false,
        )
        val result = settings.updateMemoryAssistant(b, updated).assistants.last()
        assertEquals(updated.copy(name = b.name, systemPrompt = b.systemPrompt), result)
    }

    @Test fun `shared memory setting belongs to the chosen assistant only`() {
        val result = settings.updateMemoryAssistant(b, b.copy(useGlobalMemory = true))
        assertFalse(result.assistants.first().useGlobalMemory)
        assertTrue(result.assistants.last().useGlobalMemory)
        assertEquals(a.id, result.assistantId)
    }

    @Test fun `pending edit cannot recreate a deleted assistant`() {
        val current = settings.copy(assistants = listOf(a))
        assertEquals(current, current.updateMemoryAssistant(b, b.copy(enableMemory = true)))
    }

    @Test fun `separate pending assistant changes do not replace each other`() {
        val first = settings.updateMemoryAssistant(a, a.copy(enableMemory = true))
        val second = first.updateMemoryAssistant(b, b.copy(recordMemoryUsage = false))
        assertTrue(second.assistants.first().enableMemory)
        assertFalse(second.assistants.last().recordMemoryUsage)
        assertEquals(a.id, second.assistantId)
    }

    @Test fun `only the generic settings entry follows the current chat assistant`() {
        assertEquals(a, settings.memorySettingsAssistant(null))
        assertEquals(b, settings.memorySettingsAssistant(b.id.toString()))
    }

    @Test fun `a missing explicit assistant never falls back to the chat assistant`() {
        val removed = settings.copy(assistants = listOf(a))
        assertEquals(null, removed.memorySettingsAssistant(b.id.toString()))
        assertEquals(a, removed.memorySettingsAssistant(null))
    }

    @Test fun `empty assistant settings have no editable target`() {
        val empty = settings.copy(assistants = emptyList())
        assertEquals(null, empty.memorySettingsAssistant(null))
        assertEquals(null, empty.memorySettingsAssistant(b.id.toString()))
    }

    @Test fun `explicit assistant flow retains its identity after removal instead of showing A`() = runTest {
        val source = MutableStateFlow(settings)
        val selected = source.memorySettingsAssistantFlow(
            b.id.toString()
        ).stateIn(backgroundScope, SharingStarted.Eagerly, b)
        runCurrent()
        source.value = settings.copy(assistants = listOf(a.copy(name = "new A")))
        runCurrent()
        assertEquals(b, selected.value)
        assertEquals(null, source.value.memorySettingsAssistant(b.id.toString()))
        val restored = b.copy(name = "restored B")
        source.value = settings.copy(assistants = listOf(a, restored))
        runCurrent()
        assertEquals(restored, selected.value)
    }

    @Test fun `generic assistant flow can follow the newly selected chat assistant`() = runTest {
        val source = MutableStateFlow(settings)
        val selected = source.memorySettingsAssistantFlow(null).stateIn(backgroundScope, SharingStarted.Eagerly, a)
        runCurrent()
        source.value = settings.copy(assistantId = b.id)
        runCurrent()
        assertEquals(b, selected.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a patch must not be redirected to a different assistant id`() {
        settings.updateMemoryAssistant(b, a.copy(enableMemory = true))
    }
}
