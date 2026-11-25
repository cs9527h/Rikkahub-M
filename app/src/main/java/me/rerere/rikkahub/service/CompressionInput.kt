package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.model.Conversation

/** Compare the actual selected prefix, including branch identity, before accepting an async summary. */
internal fun Conversation.hasSameCompressionInput(snapshot: Conversation, endIndex: Int): Boolean {
    if (messageNodes.size < endIndex || snapshot.messageNodes.size < endIndex) return false
    if (config != snapshot.config || customSystemPrompt != snapshot.customSystemPrompt ||
        assistantId != snapshot.assistantId || modeInjectionIds != snapshot.modeInjectionIds ||
        lorebookIds != snapshot.lorebookIds || workspaceCwd != snapshot.workspaceCwd
    ) {
        return false
    }
    return (0 until endIndex).all { index ->
        val current = messageNodes[index]
        val original = snapshot.messageNodes[index]
        current.id == original.id && current.selectIndex == original.selectIndex &&
            current.currentMessage == original.currentMessage
    }
}
