package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollAnchorTest {
    private fun node(
        role: MessageRole
    ) = MessageNode.of(UIMessage(role = role, parts = listOf(UIMessagePart.Text("text"))))

    @Test fun acceptedSendAnchorsOnlyTheNextRealUserMessage() {
        val old = node(MessageRole.USER)
        val notice = MessageNode.of(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Notice("event", "done")))
        )
        val checkpoint = MessageNode.of(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("summary")),
                isContextCheckpoint = true
            )
        )
        val state = ChatScrollAnchorState()
        state.anchorNextUserMessage(listOf(old))
        state.onMessagesChanged(listOf(old, notice, checkpoint, node(MessageRole.ASSISTANT)))
        assertTrue(state.awaitingUserMessage)
        assertNull(state.nodeId)
        val next = node(MessageRole.USER)
        state.onMessagesChanged(listOf(old, notice, checkpoint, next))
        assertEquals(next.id, state.nodeId)
        assertFalse(state.autoFollow)
    }

    @Test fun draggingWhileSendIsQueuedCancelsItsFutureJump() {
        val old = node(MessageRole.USER)
        val state = ChatScrollAnchorState()
        state.anchorNextUserMessage(listOf(old))
        state.onUserScroll()
        state.onMessagesChanged(listOf(old, node(MessageRole.USER)))
        assertNull(state.nodeId)
        assertFalse(state.awaitingUserMessage)
    }

    @Test fun regenerationAnchorsTheUserTurnInsteadOfTheAssistant() {
        val user = node(MessageRole.USER)
        val assistant = node(MessageRole.ASSISTANT)
        val state = ChatScrollAnchorState()
        state.anchorTurnOf(listOf(user, assistant), assistant.currentMessage.id)
        assertEquals(user.id, state.nodeId)
    }

    @Test fun interruptedAnimationLosesTheRightToRepin() {
        val user = node(MessageRole.USER)
        val state = ChatScrollAnchorState()
        state.anchorNextUserMessage(emptyList())
        state.onMessagesChanged(listOf(user))
        val request = state.scrollRequest
        assertTrue(state.beginScroll(request))
        state.onUserScroll()
        state.finishScroll(request, completed = true)
        assertFalse(state.pinned)
        assertEquals(user.id, state.nodeId)
    }

    @Test fun explicitBottomClearsPaddingTargetAndRestoresFollow() {
        val user = node(MessageRole.USER)
        val state = ChatScrollAnchorState()
        state.anchorNextUserMessage(emptyList())
        state.onMessagesChanged(listOf(user))
        assertTrue(state.beginScroll(state.scrollRequest))
        state.finishScroll(state.scrollRequest, completed = true)
        assertTrue(state.pinned)
        state.release()
        assertNull(state.nodeId)
        assertFalse(state.pinned)
        assertTrue(state.autoFollow)
    }

    @Test fun deletingTheAnchorReleasesItButChangingAssistantContentDoesNot() {
        val user = node(MessageRole.USER)
        val assistant = node(MessageRole.ASSISTANT)
        val state = ChatScrollAnchorState()
        state.anchorTurnOf(listOf(user, assistant), assistant.currentMessage.id)
        state.onMessagesChanged(
            listOf(
                user,
                assistant.copy(
                    messages = listOf(assistant.currentMessage.copy(parts = listOf(UIMessagePart.Text("more"))))
                )
            )
        )
        assertEquals(user.id, state.nodeId)
        state.onMessagesChanged(listOf(assistant))
        assertNull(state.nodeId)
        assertTrue(state.autoFollow)
    }

    @Test fun viewportPaddingIsBoundedAndLongRepliesNeedNoExtraSpace() {
        assertEquals(600, chatAnchorLastNodeMinHeight(800, 200))
        assertEquals(0, chatAnchorLastNodeMinHeight(800, 1200))
        assertEquals(0, chatAnchorLastNodeMinHeight(-1, 0))
    }
}
