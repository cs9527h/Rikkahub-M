package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalTitleTest {
    private fun user(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    @Test fun takesFirstUserMessageAndCollapsesWhitespace() {
        val messages = listOf(
            user("  帮我看一下\n\n这个报错   怎么修  "),
            user("第二条不该用"),
        )
        assertEquals("帮我看一下 这个报错 怎么修", localTitleFrom(messages, "新聊天"))
    }

    @Test fun truncatesLongText() {
        val long = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十"
        assertEquals(long.take(24), localTitleFrom(listOf(user(long)), "新聊天"))
    }

    @Test fun fallsBackWhenThereIsNoUserText() {
        assertEquals("新聊天", localTitleFrom(emptyList(), "新聊天"))
        assertEquals("新聊天", localTitleFrom(listOf(user("   ")), "新聊天"))
    }
}
