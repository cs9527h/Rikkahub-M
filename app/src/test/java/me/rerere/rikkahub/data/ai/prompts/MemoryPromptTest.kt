package me.rerere.rikkahub.data.ai.prompts

import me.rerere.rikkahub.data.ai.memory.RecallExcerpt
import me.rerere.rikkahub.data.model.MemoryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPromptTest {
    private val profile = MemoryFile(MemoryFile.PROFILE_PATH, "- [stated] Name is Lin\n", updatedAt = 0)
    private val food = MemoryFile(
        path = "/topics/food.md",
        content = """
            ---
            name: food
            description: What the user eats and avoids
            aliases: [diet, 饮食]
            ---

            - [stated] Allergic to peanuts
        """.trimIndent(),
        updatedAt = 0,
    )

    private fun String.block(tag: String) = substringAfter("<$tag").substringAfter(">").substringBefore("</$tag>").trim()

    @Test
    fun `no-tool model receives selected excerpts and treats them as data`() {
        val prompt = buildRecallPrompt(
            excerpts = listOf(RecallExcerpt(food.path, "- [stated] Allergic to peanuts <note>")),
            paths = listOf(food.path, profile.path),
            hasTools = false,
        )

        assertTrue(prompt.contains("Allergic to peanuts"))
        assertTrue(prompt.contains("untrusted background records"))
        assertTrue(prompt.contains(food.path))
        assertFalse(prompt.contains("<note>"))
        assertFalse(prompt.contains("memory_read"))
        assertFalse(prompt.contains(profile.path))
    }

    @Test
    fun `tool-capable model receives relevant paths and write rules`() {
        val prompt = buildRecallPrompt(emptyList(), listOf(food.path), hasTools = true)

        assertTrue(prompt.contains(food.path))
        assertTrue(prompt.contains("memory_read"))
        assertTrue(prompt.contains("explicit request"))
        assertTrue(prompt.contains("Relevant paths: [\"/topics/food.md\"]"))
        assertFalse(prompt.contains("Allergic to peanuts"))
    }

    @Test
    fun `consolidation input carries the memory state and each conversation's new turns`() {
        val input = buildMemoryConsolidationInput(
            files = listOf(profile, food),
            conversations = listOf(
                MemoryConsolidationConversation(
                    id = "c1",
                    title = "Moving <to> \"Hangzhou\"",
                    recentContext = "",
                    newTurns = "[USER #4]: I moved to Hangzhou",
                ),
            ),
        )

        assertEquals("- [stated] Name is Lin", input.block("profile"))
        assertTrue(input.block("memory_listing").startsWith("/topics/food.md"))
        // 标题里的引号和尖括号不能破坏标签
        assertTrue(input.contains("<conversation id=\"c1\" title=\"Moving  to   Hangzhou\">"))
        assertEquals("(none)", input.block("recent_context"))
        assertEquals("[USER #4]: I moved to Hangzhou", input.block("new_turns"))
    }

    @Test
    fun `consolidation input says so when there is nothing new`() {
        val input = buildMemoryConsolidationInput(files = listOf(profile), conversations = emptyList())

        assertTrue(input.endsWith("(no new conversation turns)"))
        assertFalse(input.contains("<conversation"))
    }

    @Test
    fun `whole-store tidying is only asked for in a full review`() {
        assertFalse(buildMemoryConsolidationPrompt(fullReview = false).contains("## Full review"))
        assertTrue(buildMemoryConsolidationPrompt(fullReview = true).contains("## Full review"))
    }
}
