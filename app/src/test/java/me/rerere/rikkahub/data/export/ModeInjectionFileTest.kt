package me.rerere.rikkahub.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ModeInjectionFileTest {
    @Test fun importsTextAndPreservesJsonSettings() {
        for (name in listOf("fixture.MD", "fixture.txt")) {
            val rule = ModeInjectionSerializer.decodeFile(name, "\uFEFF# hello\n")
            assertEquals("fixture", rule.name)
            assertEquals("# hello\n", rule.content)
            val configured = rule.copy(priority = 9, enabled = false)
            val restored = ModeInjectionSerializer.decodeFile("fixture.json", ModeInjectionSerializer.exportToJson(configured))
            assertNotEquals(configured.id, restored.id)
            assertEquals(configured, restored.copy(id = configured.id))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyText() {
        ModeInjectionSerializer.decodeFile("empty.txt", "  ")
    }
}
