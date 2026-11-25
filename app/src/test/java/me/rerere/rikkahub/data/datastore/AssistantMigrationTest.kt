package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置助手的一次性迁移：旧版把默认助手叫「李鱼」+ 🐟 头像，0.9.10 起改成上游默认（名字空 = 「默认助手」）。
 *
 * 关键点：迁移只跑一次。否则用户把名字改回「李鱼」时会被再次清空，表现就是「名字改不回去」。
 */
class AssistantMigrationTest {

    private fun defaultAssistant(name: String, avatar: Avatar = Avatar.Dummy) = Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = name,
        avatar = avatar,
    )

    @Test
    fun `legacy fish assistant is migrated to upstream default once`() {
        val legacy = Settings(
            assistants = listOf(defaultAssistant("李鱼", Avatar.Emoji("🐟"))),
        ).normalized()

        val assistant = legacy.assistants.single { it.id == DEFAULT_ASSISTANT_ID }
        assertEquals("", assistant.name)
        assertEquals(Avatar.Dummy, assistant.avatar)
        assertTrue("迁移后必须置位，避免反复覆盖", legacy.defaultAssistantMigrated)
    }

    @Test
    fun `user can rename the assistant back to 李鱼 after migration`() {
        val renamed = Settings(
            assistants = listOf(defaultAssistant("李鱼")),
            defaultAssistantMigrated = true,
        ).normalized()

        val assistant = renamed.assistants.single { it.id == DEFAULT_ASSISTANT_ID }
        assertEquals("用户改的名字必须保留", "李鱼", assistant.name)
    }

    @Test
    fun `fresh install keeps empty name and is not marked as migrated by migration itself`() {
        val fresh = Settings().normalized()
        val assistant = fresh.assistants.single { it.id == DEFAULT_ASSISTANT_ID }
        assertEquals("", assistant.name)
        // 全新安装没有旧值可迁移，标记应保持原状（不因此改写用户后续设置）
        assertFalse(fresh.defaultAssistantMigrated)
    }
}
