package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ForegroundAssistantIdentityTest {
    @Test fun picksConversationAssistantInsteadOfFirstAssistant() {
        val first = Assistant(id = Uuid.random(), name = "first")
        val second = Assistant(id = Uuid.random(), name = "second")
        val selected = resolveForegroundAssistant(second.id.toString(), listOf(first, second))
        assertEquals(second.id, selected?.id)
    }

    @Test fun missingAssistantDoesNotImpersonateDefault() {
        val first = Assistant(name = "first")
        assertNull(resolveForegroundAssistant(null, listOf(first)))
        assertNull(resolveForegroundAssistant(Uuid.random().toString(), listOf(first)))
    }

    @Test fun clearedNameStillFallsBackToAppNameNotAnotherAssistant() {
        val first = Assistant(name = "first")
        val second = Assistant(id = Uuid.random(), name = "")
        assertEquals(second.id, resolveForegroundAssistant(second.id.toString(), listOf(first, second))?.id)
    }
}
