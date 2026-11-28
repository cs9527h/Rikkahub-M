package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellToolDescriptionTest {
    @Test fun descriptionIsOptionalStringAndBoundedForRendering() {
        assertNull(shellToolDescription(JsonNull))
        assertNull(shellToolDescription(JsonObject(emptyMap())))
        assertNull(shellToolDescription(JsonObject(mapOf("description" to JsonPrimitive(1)))))
        assertNull(shellToolDescription(JsonObject(mapOf("description" to JsonPrimitive("  ")))))
        assertEquals(
            "List project files",
            shellToolDescription(JsonObject(mapOf("description" to JsonPrimitive(" List project files "))))
        )
        assertEquals(
            120,
            shellToolDescription(JsonObject(mapOf("description" to JsonPrimitive("x".repeat(10000)))))!!.length
        )
    }

    @Test fun pendingDescribedCommandHasSummaryBeforeAnyOutputExists() {
        val arguments = JsonObject(mapOf(
            "command" to JsonPrimitive("printf 'test'"),
            "description" to JsonPrimitive("Print test text"),
        ))
        val raw = arguments.toString()
        val tool = UIMessagePart.Tool(toolCallId = "call", toolName = "workspace_shell", input = raw)
        assertTrue(ShellToolUI.hasSummary(ToolUIContext(tool, arguments, content = null, loading = false)))
        assertEquals(raw, tool.input)
        assertFalse(tool.isExecuted)
    }

    @Test fun legacyPendingCommandKeepsLegacySummaryBehavior() {
        val arguments = JsonObject(mapOf("command" to JsonPrimitive("pwd")))
        val tool = UIMessagePart.Tool(toolCallId = "call", toolName = "workspace_shell", input = arguments.toString())
        assertFalse(ShellToolUI.hasSummary(ToolUIContext(tool, arguments, content = null, loading = false)))
    }
}
