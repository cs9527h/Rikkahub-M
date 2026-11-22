package me.rerere.rikkahub.data.ai.memory

import me.rerere.rikkahub.data.db.entity.HistoryMemoryCandidate
import me.rerere.rikkahub.data.model.MemoryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTriageTest {
    private var seq = 0

    private fun candidate(
        fact: String,
        category: String = "preferences",
        subject: String = fact.take(12),
        evidence: String = "用户原话：$fact",
    ) = HistoryMemoryCandidate(
        jobId = "job",
        key = "key-${seq++}",
        category = category,
        subject = subject,
        fact = fact,
        evidence = evidence,
        conversationId = "00000000-0000-0000-0000-000000000001",
        messageId = "00000000-0000-0000-0000-000000000002",
        sourceTime = "2026-01-01T00:00:00Z",
    )

    private fun memory(path: String, title: String, body: String): MemoryFile {
        val content = MemoryFile(path, "", 0).contentWith(title, title, emptyList(), body)
        return MemoryFile(path, content, 0)
    }

    @Test
    fun duplicateFactIsIgnored() {
        val existing = memory("/topics/reading.md", "阅读偏好", "- [stated] 我喜欢在晚上用阅读软件看小说\n")
        val result = MemoryTriageEngine.evaluate(candidate("我喜欢在晚上用阅读软件看小说"), listOf(existing))
        assertEquals(MemorySuggestion.IGNORE, result.suggestion)
        assertEquals("/topics/reading.md", result.targetPath)
    }

    @Test
    fun nearDuplicateIsMerged() {
        val existing = memory(
            "/topics/reading.md",
            "阅读偏好",
            "- [stated] 我喜欢在晚上用阅读软件看网络小说，每天大约一小时\n",
        )
        val result = MemoryTriageEngine.evaluate(
            candidate("我喜欢在晚上用阅读软件看网络小说，每天大约一小时，周末会看两小时"),
            listOf(existing),
        )
        assertEquals(MemorySuggestion.MERGE, result.suggestion)
        assertEquals("/topics/reading.md", result.targetPath)
    }

    @Test
    fun importantPersonalFactsNeedConfirmation() {
        val result = MemoryTriageEngine.evaluate(
            candidate("我的妹妹今年在杭州读研究生，专业是建筑设计", category = "people"),
            emptyList(),
        )
        assertEquals(MemorySuggestion.CONFIRM, result.suggestion)
    }

    @Test
    fun shortOrQuestionLikeFactsAreIgnored() {
        assertEquals(MemorySuggestion.IGNORE, MemoryTriageEngine.evaluate(candidate("好的"), emptyList()).suggestion)
        assertEquals(
            MemorySuggestion.IGNORE,
            MemoryTriageEngine.evaluate(candidate("你还记得我喜欢什么吗？"), emptyList()).suggestion,
        )
    }

    @Test
    fun plainLongTermPreferenceIsSaved() {
        val result = MemoryTriageEngine.evaluate(
            candidate("我每周末都会去游泳馆游两千米，已经坚持了三年", category = "ongoing"),
            emptyList(),
        )
        assertEquals(MemorySuggestion.SAVE, result.suggestion)
    }

    @Test
    fun conflictingStatementNeedsConfirmation() {
        val existing = memory("/preferences.md", "饮食偏好", "- [stated] 我不吃辣，一点辣都受不了\n")
        val result = MemoryTriageEngine.evaluate(candidate("我现在能吃辣了，一点辣都受得了"), listOf(existing))
        assertEquals(MemorySuggestion.CONFIRM, result.suggestion)
    }

    @Test
    fun singleSharedWordIsNotRelevant() {
        val file = memory("/topics/speed.md", "阅读速度记录", "我每天读书半小时，速度大概每分钟五百字\n")
        val match = memoryMatch(file, recallTerms("阅读软件要求"))
        assertFalse(match.relevant)
    }

    @Test
    fun twoSpecificWordsAreRelevant() {
        val file = memory("/topics/reader.md", "阅读软件", "我比较过几款阅读软件，要求支持本地导入和夜间模式\n")
        val match = memoryMatch(file, recallTerms("阅读软件要求"))
        assertTrue(match.relevant)
    }

    @Test
    fun wholePhraseCountsEvenWithOneWord() {
        val file = memory("/topics/reader.md", "阅读软件", "我要求阅读软件支持本地导入\n")
        val match = memoryMatch(file, recallTerms("阅读软件"), query = "阅读软件")
        assertTrue(match.relevant)
    }
}
