package me.rerere.rikkahub.ui.pages.mediacreation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.provider.MediaGenerationCapabilities
import me.rerere.rikkahub.data.model.MediaCreationDraft
import me.rerere.rikkahub.data.model.MediaCreationParams
import me.rerere.rikkahub.data.model.supportedBy

/** Main-thread reducer shared by the VM and real composer tests; no Android or IO dependency. */
internal class MediaDraftController {
    private val mutableDraft = MutableStateFlow<MediaCreationDraft?>(null)
    val draft = mutableDraft.asStateFlow()
    private val mutableSubmitting = MutableStateFlow(false)
    val submitting = mutableSubmitting.asStateFlow()
    private var revision = 0L
    private var serial = 0L
    private var pending: Ticket? = null

    internal data class Ticket(val id: Long, val revision: Long, val draft: MediaCreationDraft)

    fun load(draft: MediaCreationDraft) {
        if (mutableDraft.value == null) initialize(draft) else update { draft }
    }

    fun update(transform: (MediaCreationDraft) -> MediaCreationDraft) {
        val current = mutableDraft.value ?: return
        val next = transform(current)
        if (next != current) {
            revision++
            mutableDraft.value = next
        }
    }

    fun initialize(draft: MediaCreationDraft) {
        if (mutableDraft.value == null) mutableDraft.value = draft
    }

    fun clear() = update { it.copy(prompt = "", assets = emptyList(), editingNodeId = null) }

    fun selectModel(model: MediaGenerationModel, capabilities: MediaGenerationCapabilities, sameShape: Boolean) = update {
        it.copy(modelId = model.id, params = if (sameShape) it.params else MediaCreationParams(),
            assets = it.assets.supportedBy(capabilities))
    }

    fun beginSubmission(): Ticket? {
        if (pending != null) return null
        val current = mutableDraft.value ?: return null
        return Ticket(++serial, revision, current).also {
            pending = it
            mutableSubmitting.value = true
        }
    }

    fun submitted(ticket: Ticket) {
        if (pending?.id != ticket.id) return
        if (revision == ticket.revision) clear()
        else update {
            // A changed model/params/prompt/assets (even changed then changed back)
            // belongs to the next draft, not to the completed submission.
            if (it.editingNodeId == ticket.draft.editingNodeId) it.copy(editingNodeId = null) else it
        }
        finish(ticket)
    }

    /** Failure/cancellation never clears input; a late completion from an older ticket is ignored. */
    fun finish(ticket: Ticket) {
        if (pending?.id == ticket.id) {
            pending = null
            mutableSubmitting.value = false
        }
    }
}
