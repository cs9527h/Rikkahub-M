package me.rerere.rikkahub.data.ai.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryMemoryExtractionTest {
    @Test fun longTextKeepsAllCharactersAndDoesNotSplitSurrogatePairs() {
        val text = "a".repeat(HISTORY_FRAGMENT_CHARS - 1) + "🐟" + "学习进度".repeat(5_000)
        val segments = splitHistoryText(text).toList()
        assertEquals(text, segments.joinToString(""))
        assertTrue(segments.all { it.length <= HISTORY_FRAGMENT_CHARS })
        assertFalse(segments.any { it.first().isLowSurrogate() || it.last().isHighSurrogate() })
    }

    @Test fun onlyCandidatesWithUserEvidenceSurviveAndExactVariantsDeduplicate() {
        val excerpts = listOf(HistoryExcerpt("synthetic-message", "2020-01-01T00:00", "我的虚构测试项目用 Kotlin。"))
        val payload = """
            [
              {"segment":0,"category":"projects","subject":"测试项目","fact":"测试项目用 Kotlin。","evidence":"测试项目用 Kotlin"},
              {"segment":0,"category":"projects","subject":"测试项目","fact":"测试项目用 kotlin","evidence":"测试项目用 Kotlin"},
              {"segment":0,"category":"projects","subject":"测试项目","fact":"测试项目用 Swift。","evidence":"Swift"},
              {"segment":4,"category":"projects","subject":"测试项目","fact":"测试项目用 Kotlin。","evidence":"测试项目用 Kotlin"}
            ]
        """.trimIndent()
        val facts = parseHistoryCandidates(payload, excerpts, "synthetic-job", "synthetic-conversation")
        assertEquals(1, facts.size)
        assertEquals("synthetic-message", facts.single().messageId)
        assertEquals("synthetic-conversation", facts.single().conversationId)
    }
}
