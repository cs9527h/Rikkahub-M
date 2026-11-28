package me.rerere.rikkahub.data.model

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class AssistantSearchDefaultTest {
    @get:Rule val temp = TemporaryFolder()
    private val sharedModel = Model(modelId = "search", displayName = "Shared", tools = setOf(BuiltInTools.Search))
    private val owner = Assistant(chatModelId = sharedModel.id)
    private fun settings(assistant: Assistant = owner) = Settings(
        assistantId = assistant.id,
        assistants = listOf(assistant),
        chatModelId = sharedModel.id,
        providers = listOf(ProviderSetting.OpenAI(models = listOf(sharedModel))),
    )
    private fun draft() = Conversation.ofId(Uuid.random(), assistantId = owner.id)

    @Test fun `legacy JSON missing search default retains legacy assistant and model behavior`() {
        val legacy = JsonInstant.decodeFromString(Assistant.serializer(), """{"enableWebSearch":true}""")
        assertNull(legacy.searchDefault)
        val legacySettings = settings(legacy.copy(id = owner.id, chatModelId = sharedModel.id))
        assertTrue(legacySettings.getAssistantOf(draft()).enableWebSearch)
        assertSame(sharedModel, legacySettings.getChatModelOf(draft()))
    }

    @Test fun `nullable local and off defaults round trip through the real assistant serializer`() {
        for (mode in listOf(null, AssistantSearchDefault.LOCAL, AssistantSearchDefault.OFF)) {
            val assistant = owner.copy(searchDefault = mode)
            val encoded = JsonInstant.encodeToString(Assistant.serializer(), assistant)
            assertEquals(assistant, JsonInstant.decodeFromString(Assistant.serializer(), encoded))
        }
    }

    @Test fun `remembered defaults do not change existing bound config or shared provider data`() {
        val original = settings()
        val existing = draft().bindConfig(original)
        assertTrue(existing.config!!.builtInSearch)
        for (mode in listOf(AssistantSearchDefault.LOCAL, AssistantSearchDefault.OFF)) {
            val changed = original.copy(assistants = listOf(owner.copy(searchDefault = mode)))
            val fresh = draft()
            assertSame(original.providers, changed.providers)
            assertTrue(BuiltInTools.Search in changed.getChatModelOf(existing)!!.tools)
            assertFalse(changed.getAssistantOf(existing).enableWebSearch)
            assertFalse(BuiltInTools.Search in changed.getChatModelOf(fresh)!!.tools)
            assertEquals(mode == AssistantSearchDefault.LOCAL, changed.getAssistantOf(fresh).enableWebSearch)
            val bound = fresh.bindConfig(changed)
            assertFalse(bound.config!!.builtInSearch)
            assertEquals(mode == AssistantSearchDefault.LOCAL, bound.config!!.enableWebSearch)
        }
    }

    @Test fun `missing model still permits local default without manufacturing provider settings`() {
        val original = settings(owner.copy(searchDefault = AssistantSearchDefault.LOCAL)).copy(providers = emptyList())
        assertNull(original.getChatModelOf(draft()))
        val bound = draft().bindConfig(original)
        assertTrue(bound.config!!.enableWebSearch)
        assertFalse(bound.config!!.builtInSearch)
        assertNull(bound.config!!.chatModelId)
    }

    @Test fun `web settings entry updates the explicit default instead of being shadowed by an older override`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val disk = PreferenceDataStoreFactory.create(scope = scope) {
                temp.root.resolve("search.preferences_pb")
            }
            val store = SettingsStore(disk, scope)
            store.awaitLoaded()
            store.update { settings(owner.copy(searchDefault = AssistantSearchDefault.OFF)) }
            val providerSnapshot = store.settingsFlow.value.providers
            // SettingsRoutes uses this actual entry point; no duplicate routing implementation in the test.
            store.updateAssistantWebSearch(owner.id, true)
            val local = store.settingsFlow.value
            assertEquals(AssistantSearchDefault.LOCAL, local.assistants.first { it.id == owner.id }.searchDefault)
            assertTrue(local.getAssistantOf(draft()).enableWebSearch)
            assertFalse(BuiltInTools.Search in local.getChatModelOf(draft())!!.tools)
            assertEquals(providerSnapshot, local.providers)
            store.updateAssistantWebSearch(owner.id, false)
            val off = store.settingsFlow.value
            assertEquals(AssistantSearchDefault.OFF, off.assistants.first { it.id == owner.id }.searchDefault)
            assertFalse(off.getAssistantOf(draft()).enableWebSearch)
            assertFalse(BuiltInTools.Search in off.getChatModelOf(draft())!!.tools)
            assertEquals(providerSnapshot, off.providers)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }
}
