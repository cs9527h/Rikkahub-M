package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatResilienceTest {
    @Test fun errorsStayBoundedToTheLatestTwenty() {
        var errors = emptyList<ChatError>()
        repeat(100) { index ->
            errors =
                appendRecentChatError(errors, ChatError(error = IllegalStateException("error-$index")))
        }
        assertEquals(20, errors.size)
        assertEquals("error-80", errors.first().error.message)
        assertEquals("error-99", errors.last().error.message)
    }

    @Test fun duplicateErrorIsReplacedByItsNewestOccurrenceWithoutMixingConversations() {
        val a = Uuid.random()
        val b = Uuid.random()
        val old = ChatError(error = IllegalStateException("same"), conversationId = a, title = "title")
        val other = old.copy(id = Uuid.random(), conversationId = b)
        val fresh = old.copy(id = Uuid.random(), error = IllegalStateException("same"))
        val result = appendRecentChatError(listOf(old, other), fresh)
        assertEquals(listOf(other, fresh), result)
    }

    @Test fun messageLessExceptionsAreDeduplicatedByClass() {
        val a = ChatError(error = IllegalStateException())
        val b = ChatError(error = IllegalArgumentException())
        val fresh = ChatError(error = IllegalStateException())
        assertEquals(listOf(b, fresh), appendRecentChatError(listOf(a, b), fresh))
    }

    @Test fun checkpointRequiresTheEntireToolStepToBeFinished() {
        val done = tool("done", executed = true)
        val pending = tool("pending", executed = false)
        fun message(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())
        assertTrue(message(done).endsWithExecutedTool())
        assertTrue(message(done, done.copy(toolCallId = "second")).endsWithExecutedTool())
        assertFalse(message(pending, done).endsWithExecutedTool())
        assertFalse(message(done, pending).endsWithExecutedTool())
        assertFalse(message(done, UIMessagePart.Text("streaming reply")).endsWithExecutedTool())
        assertFalse(message(UIMessagePart.Text("streaming reply")).endsWithExecutedTool())
        assertFalse(message().endsWithExecutedTool())
    }

    private fun tool(id: String, executed: Boolean) = UIMessagePart.Tool(
        toolCallId = id,
        toolName = "example",
        input = "{}",
        output = if (executed) listOf(UIMessagePart.Text("done")) else emptyList(),
    )
}
