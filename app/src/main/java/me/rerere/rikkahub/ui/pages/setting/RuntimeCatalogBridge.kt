package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.files.LiveConversationFileReferences
import me.rerere.rikkahub.data.files.normalizeLocalFileReference
import me.rerere.rikkahub.service.RuntimeAttachmentReferences

/** Each collector belongs to the visible page; no process-global mutable registry is needed. */
internal fun RuntimeAttachmentReferences.catalogReferences(): List<LiveConversationFileReferences> =
    conversations.map { (id, reference) ->
        LiveConversationFileReferences(
            conversationId = id.toString(),
            assistantId = reference.assistantId.toString(),
            paths = reference.sentUrls.mapNotNull(::normalizeLocalFileReference).toSet(),
            replacesPersistedHistory = reference.historyComplete && reference.referencesComplete,
            complete = reference.referencesComplete,
        )
    }
