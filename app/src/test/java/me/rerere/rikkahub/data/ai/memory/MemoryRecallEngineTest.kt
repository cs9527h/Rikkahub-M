package me.rerere.rikkahub.data.ai.memory

import me.rerere.rikkahub.data.model.MemoryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多阶段召回：中文关键词、别名、语义近似、宽泛问题与相关性门槛。 */
class MemoryRecallEngineTest {
    private fun memory(
        path: String,
        title: String,
        body: String,
        aliases: List<String> = emptyList(),
        importance: Int = 0,
    ): MemoryFile {
        val values = buildMap {
            put("name", path.substringAfterLast('/').removeSuffix(".md"))
            put("title", title)
            if (aliases.isNotEmpty()) put("aliases", aliases)
            if (importance > 0) put("importance", importance)
        }
        val content = me.rerere.rikkahub.data.skills.SkillFrontmatterParser.compose(values, body)
        return MemoryFile(path, content, System.currentTimeMillis())
    }

    private val radio = memory(
        path = "/topics/radio.md",
        title = "收音机",
        body = "SR286 和 SI4732 这两颗芯片我都在用，SR286 的灵敏度更好。",
        aliases = listOf("SR286", "SI4732", "收音机芯片"),
    )

    @Test
    fun exactAliasBeatsKeywordOverlap() {
        val query = MemoryRecallEngine.analyze("我之前用的 SR286 怎么样")
        val hit = MemoryRecallEngine.score(radio, query)
        assertTrue(hit != null)
        assertTrue(hit!!.reasons.any { it.contains("SR286") || it.contains("别名") })
    }

    @Test
    fun broadQuestionUsesImportanceInsteadOfKeywordOverlap() {
        val important = memory(
            path = "/areas/move.md",
            title = "搬家计划",
            body = "计划明年春天搬到杭州，需要先看房。",
            importance = 4,
        )
        val query = MemoryRecallEngine.analyze("你还记得我以前问过什么吗")
        assertTrue("宽泛问题应识别为 broad", query.broad)
        val result = MemoryRecallEngine.score(important, query)
        assertTrue("重要记忆应被兜底召回", result != null)
        assertTrue(result!!.reasons.any { it.contains("广泛问题") })
    }

    @Test
    fun singleSharedCharacterIsNotSemanticMatch() {
        val unrelated = memory("/topics/reading.md", "阅读速度", "我每天读书半小时，速度大约每分钟五百字。")
        val query = MemoryRecallEngine.analyze("阅读软件的要求")
        // “阅读”一个共同词不足以召回；需要标题命中 + 第二个信号或语义近似
        assertEquals(null, MemoryRecallEngine.score(unrelated, query))
    }

    @Test
    fun chineseSemanticContainmentFindsRelatedTopic() {
        val query = MemoryRecallEngine.analyze("芯片灵敏度和收音效果")
        val hit = MemoryRecallEngine.score(radio, query)
        assertTrue(hit != null)
        assertTrue(hit!!.reasons.any { it.contains("语义近似") || it.contains("关键词") })
    }

    @Test
    fun outdatedMemoryIsNeverReturned() {
        val outdated = memory("/topics/old.md", "旧收音机", "SR286 已经卖掉。")
            .let { MemoryFile(it.path, it.contentWithOutdated(true), it.updatedAt) }
        val query = MemoryRecallEngine.analyze("SR286")
        assertEquals(null, MemoryRecallEngine.score(outdated, query))
    }

    @Test
    fun rankPrefersSpecificHitsOverWeakOverlap() {
        val weak = memory("/topics/misc.md", "杂项", "收音机这个词在别的地方出现过。")
        val specific = radio
        val query = MemoryRecallEngine.analyze("SR286 灵敏度")
        val ranked = MemoryRecallEngine.rank(listOf(weak, specific), query)
        assertFalse(ranked.isEmpty())
        assertEquals("/topics/radio.md", ranked.first().file.path)
    }
}
