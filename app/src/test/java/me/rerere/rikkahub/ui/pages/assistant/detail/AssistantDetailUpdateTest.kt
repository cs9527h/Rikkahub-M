package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantSearchDefault
import me.rerere.rikkahub.data.model.Avatar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

class AssistantDetailUpdateTest {
    private fun rendered(id: Uuid = Uuid.random()) = Assistant(
        id = id,
        name = "Original",
        avatar = Avatar.Image("file:///app/upload/previous.png"),
        background = "file:///app/upload/previous-background.png",
    )

    @Test
    fun `old rendered callback preserves media migrated after render even when VM is already newer`() {
        val before = rendered()
        val latest = before.copy(
            avatar = Avatar.Image("file:///app/avatars/migrated.png"),
            background = "file:///app/backgrounds/migrated.png",
        )
        val updated = before.copy(name = "New name")
        val saved = mergeAssistantDetailUpdate(before.id, before, updated, latest)!!
        assertEquals("New name", saved.name)
        assertEquals(latest.avatar, saved.avatar)
        assertEquals(latest.background, saved.background)
        // A background retained by the actual merge is not a replaced background to clean up.
        assertFalse(latest.background != saved.background)
    }

    @Test
    fun `rapid edits from same rendered frame preserve concurrent migration throughout`() {
        val before = rendered()
        var latest = before.copy(avatar = Avatar.Image("file:///app/avatars/migrated.png"))
        for (name in listOf("N", "Ne", "New")) {
            latest = mergeAssistantDetailUpdate(before.id, before, before.copy(name = name), latest)!!
        }
        assertEquals("New", latest.name)
        assertEquals(Avatar.Image("file:///app/avatars/migrated.png"), latest.avatar)
        val backgroundMigration = latest.copy(background = "file:///app/backgrounds/new.png")
        val final = mergeAssistantDetailUpdate(
            before.id,
            before,
            before.copy(temperature = 0.3f),
            backgroundMigration
        )!!
        assertEquals(backgroundMigration.avatar, final.avatar)
        assertEquals(backgroundMigration.background, final.background)
        assertEquals(0.3f, final.temperature)
    }

    @Test
    fun `explicit new avatar or background overrides newest media while unchanged field stays latest`() {
        val before = rendered()
        val latest = before.copy(
            avatar = Avatar.Image("file:///app/avatars/migrated.png"),
            background = "file:///app/backgrounds/migrated.png",
        )
        val chosen = Avatar.Image("file:///app/avatars/selected.png")
        val avatarEdit = mergeAssistantDetailUpdate(before.id, before, before.copy(avatar = chosen), latest)!!
        assertEquals(chosen, avatarEdit.avatar)
        assertEquals(latest.background, avatarEdit.background)
        val backgroundEdit = mergeAssistantDetailUpdate(
            before.id,
            before,
            before.copy(background = "https://example.test/new.png"),
            latest
        )!!
        assertEquals(latest.avatar, backgroundEdit.avatar)
        assertEquals("https://example.test/new.png", backgroundEdit.background)
        val cleared = mergeAssistantDetailUpdate(
            before.id,
            before,
            before.copy(avatar = Avatar.Dummy, background = null),
            latest
        )!!
        assertEquals(Avatar.Dummy, cleared.avatar)
        assertNull(cleared.background)
    }

    @Test
    fun `old rendered media callback preserves latest search preference unless explicitly changed`() {
        val before = rendered()
        val latest = before.copy(searchDefault = AssistantSearchDefault.LOCAL)
        val updated = before.copy(avatar = Avatar.Image("file:///app/avatars/chosen.png"))
        val saved = mergeAssistantDetailUpdate(before.id, before, updated, latest)!!
        assertEquals(AssistantSearchDefault.LOCAL, saved.searchDefault)
        assertEquals(updated.avatar, saved.avatar)
        val explicit = mergeAssistantDetailUpdate(
            before.id,
            before,
            before.copy(searchDefault = AssistantSearchDefault.OFF),
            latest,
        )!!
        assertEquals(AssistantSearchDefault.OFF, explicit.searchDefault)
    }

    @Test
    fun `concurrent name edit keeps the complete latest local or off preference pair`() {
        val before = rendered().copy(enableWebSearch = true, searchDefault = AssistantSearchDefault.LOCAL)
        for (latest in listOf(
            before.copy(enableWebSearch = false, searchDefault = AssistantSearchDefault.OFF),
            before.copy(enableWebSearch = false, searchDefault = null),
            before.copy(enableWebSearch = true, searchDefault = AssistantSearchDefault.LOCAL),
        )) {
            val saved = mergeAssistantDetailUpdate(before.id, before, before.copy(name = "Edited"), latest)!!
            assertEquals("Edited", saved.name)
            assertEquals(latest.enableWebSearch, saved.enableWebSearch)
            assertEquals(latest.searchDefault, saved.searchDefault)
        }
    }

    @Test
    fun `updates for other assistant or a deleted route never create or replace a record`() {
        val before = rendered()
        val other = rendered()
        assertNull(mergeAssistantDetailUpdate(before.id, other, before.copy(name = "edit"), before))
        assertNull(mergeAssistantDetailUpdate(before.id, before, other.copy(name = "edit"), before))
        assertNull(mergeAssistantDetailUpdate(before.id, before, before.copy(name = "edit"), other))
        assertNull(mergeAssistantDetailUpdate(before.id, before, before.copy(name = "edit"), null))
        assertNull(mergeAssistantDetailUpdate(other.id, before, before.copy(name = "edit"), before))
    }

    @Test
    fun `page callbacks freeze rendered snapshot and VM accepts only explicit before after contract`() {
        val root = sequenceOf(
            File("src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail"),
            File("app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail"),
        ).first { it.isDirectory }
        val pages = listOf(
            "AssistantBasicPage",
            "AssistantRequestPage",
            "AssistantMcpPage",
            "AssistantExtensionsPage",
            "AssistantPromptPage",
            "AssistantLocalToolPage"
        )
        for (page in pages) {
            val source = File(root, "$page.kt").readText()
            assertTrue(
                "$page freezes a plain value before creating content slots and callbacks",
                source.contains("val assistant = vm.assistant.collectAsStateWithLifecycle().value")
            )
            assertFalse(source.contains("val assistant by vm.assistant"))
            val updates = Regex("vm\\.update\\(([^\\n]*)").findAll(source).toList()
            assertTrue("$page has an actual update callback", updates.isNotEmpty())
            assertTrue(
                "$page passes the frozen rendered assistant to every update",
                updates.all { it.groupValues[1].startsWith("assistant,") }
            )
        }
        val basic = File(root, "AssistantBasicPage.kt").readText()
        assertTrue(basic.contains("vm.updateTags(assistant, tagIds, tagList)"))
        val vm = File(root, "AssistantDetailVM.kt").readText()
        assertTrue(vm.contains("fun update(before: Assistant, updated: Assistant)"))
        assertFalse(vm.contains("fun update(assistant: Assistant)"))
        assertFalse(vm.contains("val displayed = this.assistant.value"))
        assertTrue(vm.contains("mergeAssistantDetailUpdate(assistantId, before, updated, latest)"))
        assertTrue(vm.contains("oldBackground != savedBackground"))
        assertFalse(vm.contains("oldBackground != updated.background"))
    }
}
