package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantSearchDefault
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.bindConfig
import me.rerere.rikkahub.data.model.getAssistantOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatSearchPreferencesTest {
    private val model = Model(modelId = "chat", displayName = "Chat")
    private val owner = Assistant(name = "Owner", chatModelId = model.id)
    private val other = Assistant(name = "Other", chatModelId = model.id)
    private fun settings() = Settings(
        assistantId = other.id,
        assistants = listOf(owner, other),
        providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
        chatModelId = model.id,
    )
    private fun conversation() = Conversation.ofId(Uuid.random(), assistantId = owner.id).copy(
        config = ConversationConfig(chatModelId = model.id),
    )

    @Test fun `local choice in an existing conversation becomes same assistant next conversation default`() {
        val change = ChatSearchChange(true, false).exclusive()
        val current = change.applyToConversation(conversation())
        val latest = change.rememberLocalDefault(settings(), current.assistantId)
        val next = Conversation.ofId(Uuid.random(), assistantId = owner.id).bindConfig(latest)
        assertTrue(current.config!!.enableWebSearch)
        assertFalse(current.config!!.builtInSearch)
        assertTrue(next.config!!.enableWebSearch)
        assertTrue(latest.getAssistantOf(next).enableWebSearch)
        assertFalse(latest.assistants.first { it.id == other.id }.enableWebSearch)
    }

    @Test fun `off choice is remembered but does not rewrite other saved conversation snapshots`() {
        val local = ChatSearchChange(true, false).exclusive()
        val older = local.applyToConversation(conversation())
        val settings = local.rememberLocalDefault(settings(), owner.id)
        val off = ChatSearchChange(false, false).exclusive()
        val latest = off.rememberLocalDefault(settings, owner.id)
        val next = Conversation.ofId(Uuid.random(), assistantId = owner.id).bindConfig(latest)
        assertFalse(next.config!!.enableWebSearch)
        assertTrue(latest.getAssistantOf(older).enableWebSearch)
        assertTrue(older.config!!.enableWebSearch)
    }

    @Test fun `default merge preserves latest assistant fields and never falls back to selected assistant`() {
        val latestOwner = owner.copy(name = "Renamed while waiting", systemPrompt = "updated")
        val latest = settings().copy(assistants = listOf(latestOwner, other))
        val changed = ChatSearchChange(true, false).exclusive().rememberLocalDefault(latest, owner.id)
        assertEquals(latestOwner.copy(enableWebSearch = true, searchDefault = AssistantSearchDefault.LOCAL), changed.assistants.first())
        assertSame(other, changed.assistants.last())
        assertSame(latest.providers, changed.providers)
        val deleted = latest.copy(assistants = listOf(other))
        assertSame(deleted, ChatSearchChange(true, false).exclusive().rememberLocalDefault(deleted, owner.id))
    }

    @Test fun `shared model remains unchanged while two assistants have independent local and off defaults`() {
        val sharedModel = model.copy(tools = setOf(BuiltInTools.Search))
        val original = settings().copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(sharedModel))))
        val ownDraft = Conversation.ofId(Uuid.random(), assistantId = owner.id)
        val otherDraft = Conversation.ofId(Uuid.random(), assistantId = other.id)
        for (change in listOf(ChatSearchChange(true, false), ChatSearchChange(false, false))) {
            val exclusive = change.exclusive()
            val remembered = exclusive.rememberLocalDefault(original, owner.id)
            val changed = exclusive.enableDraftBuiltInSearch(remembered, ownDraft)
            assertSame(original.providers, changed.providers)
            assertTrue(BuiltInTools.Search in original.providers.single().models.single().tools)
            assertFalse(BuiltInTools.Search in changed.getChatModelOf(ownDraft)!!.tools)
            assertTrue(BuiltInTools.Search in changed.getChatModelOf(otherDraft)!!.tools)
            assertEquals(change.enableWebSearch, changed.getAssistantOf(ownDraft).enableWebSearch)
            assertFalse(changed.getAssistantOf(otherDraft).enableWebSearch)
            val bound = ownDraft.bindConfig(changed)
            assertEquals(change.enableWebSearch, bound.config!!.enableWebSearch)
            assertFalse(bound.config!!.builtInSearch)
        }
    }

    @Test fun `built in enables only explicit draft target owner and never changes a bound conversation provider`() {
        val currentModel = model.copy(displayName = "Latest model name")
        val first = ProviderSetting.OpenAI(name = "Owner", models = listOf(currentModel))
        val second = ProviderSetting.OpenAI(name = "Other provider", models = listOf(
            model.copy(id = Uuid.random(), displayName = "Other"),
        ))
        val original = settings().copy(providers = listOf(first, second), assistants = listOf(
            owner.copy(searchDefault = AssistantSearchDefault.LOCAL), other,
        ))
        val change = ChatSearchChange(false, true).exclusive()
        val remembered = change.rememberLocalDefault(original, owner.id)
        assertEquals(null, remembered.assistants.first().searchDefault)
        val draft = Conversation.ofId(Uuid.random(), assistantId = owner.id)
        val enabled = change.enableDraftBuiltInSearch(remembered, draft)
        assertTrue(BuiltInTools.Search in enabled.providers.first().models.single().tools)
        assertEquals("Latest model name", enabled.providers.first().models.single().displayName)
        assertSame(second, enabled.providers.last())
        assertFalse(BuiltInTools.Search in enabled.providers.last().models.single().tools)
        assertSame(remembered, change.enableDraftBuiltInSearch(remembered, conversation()))
        assertSame(original, ChatSearchChange(null, false).exclusive().enableDraftBuiltInSearch(original, draft))
        val deletedModel = original.copy(providers = emptyList())
        assertSame(deletedModel, change.enableDraftBuiltInSearch(deletedModel, draft))
        val ambiguousOwners = remembered.copy(providers = listOf(first, second.copy(models = listOf(model))))
        assertSame(ambiguousOwners, change.enableDraftBuiltInSearch(ambiguousOwners, draft))
        val ambiguousEntries = remembered.copy(providers = listOf(first.copy(models = listOf(currentModel, model))))
        assertSame(ambiguousEntries, change.enableDraftBuiltInSearch(ambiguousEntries, draft))
    }

    @Test fun `production session helper merges latest default and persists only current conversation metadata`() = runBlocking {
        val initial = conversation()
        val sibling = conversation()
        val session = session(initial, this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var latest = settings()
        val writes = mutableListOf<Conversation>()
        val task = launch(start = CoroutineStart.UNDISPATCHED) {
            updateChatSearchPreferences(session, true, false,
                updateSettings = { transform -> entered.complete(Unit); release.await(); latest = transform(latest) },
                persistConfig = { writes.add(it) },
            )
        }
        entered.await()
        latest = latest.copy(assistants = listOf(owner.copy(name = "Renamed while waiting"), other))
        release.complete(Unit)
        task.join()
        assertTrue(session.state.value.config!!.enableWebSearch)
        assertFalse(session.state.value.config!!.builtInSearch)
        assertSame(initial.messageNodes, session.state.value.messageNodes)
        assertEquals(listOf(session.state.value), writes)
        assertEquals("Renamed while waiting", latest.assistants.first().name)
        assertEquals(AssistantSearchDefault.LOCAL, latest.assistants.first().searchDefault)
        assertFalse(latest.getAssistantOf(sibling).enableWebSearch)
    }

    @Test fun `production helper does not redirect pending defaults to a rebound session assistant`() = runBlocking {
        val session = session(conversation(), this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var latest = settings()
        var persisted = false
        val task = launch(start = CoroutineStart.UNDISPATCHED) {
            updateChatSearchPreferences(session, true, false,
                updateSettings = { transform -> entered.complete(Unit); release.await(); latest = transform(latest) },
                persistConfig = { persisted = true },
            )
        }
        entered.await()
        val replacementConfig = ConversationConfig(enableWebSearch = false, builtInSearch = true)
        session.update { it.copy(assistantId = other.id, config = replacementConfig) }
        release.complete(Unit)
        task.join()
        assertSame(replacementConfig, session.state.value.config)
        assertSame(owner, latest.assistants.first())
        assertSame(other, latest.assistants.last())
        assertFalse(persisted)
    }

    @Test fun `production helper leaves new draft unbound and null patch does not write`() = runBlocking {
        val draft = Conversation.ofId(Uuid.random(), assistantId = owner.id)
        val session = session(draft, this)
        var latest = settings()
        var settingsWrites = 0
        var configWrites = 0
        suspend fun select(local: Boolean?, builtIn: Boolean?) {
            updateChatSearchPreferences(session, local, builtIn,
                updateSettings = { latest = it(latest); settingsWrites++ },
                persistConfig = { configWrites++ },
            )
        }
        select(null, null)
        assertEquals(0, settingsWrites)
        select(true, false)
        assertEquals(null, session.state.value.config)
        assertEquals(0, configWrites)
        assertTrue(latest.getAssistantOf(draft).enableWebSearch)
        select(false, false)
        assertFalse(latest.getAssistantOf(draft).enableWebSearch)
        assertEquals(AssistantSearchDefault.OFF, latest.assistants.first().searchDefault)
    }

    private suspend fun session(initial: Conversation, scope: CoroutineScope) = ConversationSession(
        id = initial.id, initial = initial, scope = scope,
        store = object : ConversationStore {
            override suspend fun prepareWrite(): (Conversation) -> Conversation = { it }
            override suspend fun write(previous: Conversation?, current: Conversation) {
                error("Search preferences must not rewrite conversation message nodes")
            }
        },
        onIdle = {},
    ).also { session ->
        session.initialize(load = { initial.takeIf { it.config != null } }, create = { initial })
    }

    @Test fun `late draft model edit preserves a newer complete search preference pair`() {
        val before = owner.copy(enableWebSearch = true, searchDefault = AssistantSearchDefault.LOCAL)
        val edited = before.copy(chatModelId = Uuid.random(), temperature = 0.4f)
        for (latest in listOf(
            before.copy(enableWebSearch = false, searchDefault = AssistantSearchDefault.OFF),
            before.copy(enableWebSearch = false, searchDefault = null),
        )) {
            val saved = edited.mergeUnchangedChatSearchDefault(before, edited, latest)
            assertEquals(edited.chatModelId, saved.chatModelId)
            assertEquals(edited.temperature, saved.temperature)
            assertEquals(latest.enableWebSearch, saved.enableWebSearch)
            assertEquals(latest.searchDefault, saved.searchDefault)
        }
        val explicit = before.copy(enableWebSearch = false, searchDefault = AssistantSearchDefault.OFF)
        assertSame(explicit, explicit.mergeUnchangedChatSearchDefault(before, explicit, before))
    }

    @Test fun `explicit mode enables are exclusive and absent fields retain current values`() {
        val builtIn = ChatSearchChange(null, true).exclusive().applyToConversation(conversation().copy(
            config = ConversationConfig(enableWebSearch = true),
        ))
        assertFalse(builtIn.config!!.enableWebSearch)
        assertTrue(builtIn.config!!.builtInSearch)
        val local = ChatSearchChange(true, null).exclusive().applyToConversation(builtIn)
        assertTrue(local.config!!.enableWebSearch)
        assertFalse(local.config!!.builtInSearch)
        val noChange = ChatSearchChange(null, null).exclusive().applyToConversation(local)
        assertEquals(local.config, noChange.config)
        val contradictory = ChatSearchChange(true, true).exclusive().applyToConversation(builtIn)
        assertTrue(contradictory.config!!.enableWebSearch)
        assertFalse(contradictory.config!!.builtInSearch)
    }
}
