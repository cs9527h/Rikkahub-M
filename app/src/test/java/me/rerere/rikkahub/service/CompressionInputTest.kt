package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.editMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CompressionInputTest {
    private fun conversation(): Conversation = Conversation.ofId(Uuid.random()).copy(
        messageNodes = listOf(
            MessageNode(messages = listOf(UIMessage.user("question"))),
            MessageNode(messages = listOf(UIMessage.assistant("answer"), UIMessage.assistant("alternative"))),
            MessageNode(messages = listOf(UIMessage.user("kept recent"))),
        ),
    )

    @Test
    fun `edit with unchanged node identity invalidates summary input`() {
        val snapshot = conversation()
        val edited = snapshot.editMessage(snapshot.currentMessages.first().id, listOf(UIMessagePart.Text("edited")))
        assertFalse(edited.hasSameCompressionInput(snapshot, 2))
    }

    @Test
    fun `branch switch with unchanged node identity invalidates summary input`() {
        val snapshot = conversation()
        val switched = snapshot.copy(messageNodes = snapshot.messageNodes.mapIndexed { index, node ->
            if (index == 1) node.copy(selectIndex = 1) else node
        })
        assertFalse(switched.hasSameCompressionInput(snapshot, 2))
    }

    @Test
    fun `insertion removal and prompt changes invalidate summary input`() {
        val snapshot = conversation()
        assertFalse(snapshot.copy(messageNodes = snapshot.messageNodes.drop(1)).hasSameCompressionInput(snapshot, 2))
        assertFalse(
            snapshot.copy(messageNodes =
                listOf(MessageNode(messages = listOf(UIMessage.user("new")))) + snapshot.messageNodes)
                .hasSameCompressionInput(snapshot, 2)
        )
        assertFalse(snapshot.copy(customSystemPrompt = "new prompt").hasSameCompressionInput(snapshot, 2))
    }

    @Test
    fun `metadata and retained suffix edits do not invalidate unchanged compressed prefix`() {
        val snapshot = conversation()
        val edited = snapshot.editMessage(snapshot.currentMessages.last().id, listOf(UIMessagePart.Text("new recent")))
            .copy(title = "manual", isPinned = true)
        assertTrue(edited.hasSameCompressionInput(snapshot, 2))
        assertTrue(snapshot.hasSameCompressionInput(snapshot, 3))
    }
}
