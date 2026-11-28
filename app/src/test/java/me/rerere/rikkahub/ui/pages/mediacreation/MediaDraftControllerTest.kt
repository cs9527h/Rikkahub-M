package me.rerere.rikkahub.ui.pages.mediacreation

import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.capabilities
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class MediaDraftControllerTest {
    private fun draft() = MediaCreationDraft(modelId = Uuid.random(), prompt = "original", assets = listOf(MediaCreationAsset("input.png")), params = MediaCreationParams(count = 2), editingNodeId = Uuid.random())
    private fun controller() = MediaDraftController().apply { load(draft()) }

    @Test fun `loading placeholder rejects submission and late init does not replace loaded state`() {
        val state = MediaDraftController()
        assertNull(state.beginSubmission())
        state.load(draft())
        val loaded = state.draft.value
        state.initialize(draft())
        assertEquals(loaded, state.draft.value)
    }

    @Test fun `untouched successful submission clears input but preserves model and params`() {
        val state = controller()
        val original = state.draft.value!!
        val ticket = state.beginSubmission()!!
        assertTrue(state.submitting.value)
        assertNull(state.beginSubmission())
        state.submitted(ticket)
        assertEquals(original.copy(prompt = "", assets = emptyList(), editingNodeId = null), state.draft.value)
        assertFalse(state.submitting.value)
    }

    @Test fun `editing prompt during submission preserves later draft`() {
        val state = controller()
        val ticket = state.beginSubmission()!!
        state.update { it.copy(prompt = "new input") }
        state.submitted(ticket)
        assertEquals("new input", state.draft.value!!.prompt)
        assertEquals(ticket.draft.assets, state.draft.value!!.assets)
        assertNull(state.draft.value!!.editingNodeId)
    }

    @Test fun `changing params or model only must not clear unchanged prompt and assets`() {
        for (change in listOf<(MediaCreationDraft) -> MediaCreationDraft>(
            { it.copy(params = it.params.copy(count = 3)) }, { it.copy(modelId = Uuid.random()) },
        )) {
            val state = controller()
            val ticket = state.beginSubmission()!!
            state.update(change)
            state.submitted(ticket)
            assertEquals(ticket.draft.prompt, state.draft.value!!.prompt)
            assertEquals(ticket.draft.assets, state.draft.value!!.assets)
        }
    }

    @Test fun `editing then undoing to identical content still owns a new draft revision`() {
        val state = controller()
        val ticket = state.beginSubmission()!!
        state.update { it.copy(prompt = "temporary") }
        state.update { it.copy(prompt = ticket.draft.prompt) }
        state.submitted(ticket)
        assertEquals(ticket.draft.prompt, state.draft.value!!.prompt)
    }

    @Test fun `failure or cancellation releases gate and never clears draft`() {
        val state = controller()
        val ticket = state.beginSubmission()!!
        state.finish(ticket)
        assertEquals(ticket.draft, state.draft.value)
        val retry = state.beginSubmission()!!
        state.submitted(ticket)
        assertTrue(state.submitting.value)
        assertEquals(ticket.draft, state.draft.value)
        state.finish(retry)
        assertFalse(state.submitting.value)
    }

    @Test fun `session controllers isolate late completion and clearing while submitting`() {
        val first = controller()
        val second = controller()
        val firstTicket = first.beginSubmission()!!
        val secondDraft = second.draft.value
        first.clear()
        first.submitted(firstTicket)
        assertEquals("", first.draft.value!!.prompt)
        assertEquals(secondDraft, second.draft.value)
        assertFalse(second.submitting.value)
    }

    @Test fun `switch model keeps same shape params and normalizes unsupported assets`() {
        val state = controller()
        state.update { it.copy(assets = listOf(MediaCreationAsset("first.png", role = ImageRole.FIRST_FRAME), MediaCreationAsset("video.mp4", MediaCreationAssetType.VIDEO))) }
        val imageModel = MediaGenerationModel(modelId = "image", kind = MediaKind.IMAGE)
        val imageCapabilities = MediaGenerationProviderSetting.OpenAI().capabilities(MediaKind.IMAGE)!!
        val params = state.draft.value!!.params
        state.selectModel(imageModel, imageCapabilities, sameShape = true)
        assertEquals(params, state.draft.value!!.params)
        assertEquals(listOf(MediaCreationAsset("first.png")), state.draft.value!!.assets)
        state.selectModel(imageModel.copy(id = Uuid.random()), imageCapabilities, sameShape = false)
        assertEquals(MediaCreationParams(), state.draft.value!!.params)
    }

    @Test fun `completion never detaches a different editing node chosen while pending`() {
        val state = controller()
        val ticket = state.beginSubmission()!!
        val newEditing = Uuid.random()
        state.update { it.copy(editingNodeId = newEditing) }
        state.submitted(ticket)
        assertEquals(newEditing, state.draft.value!!.editingNodeId)
    }
}
