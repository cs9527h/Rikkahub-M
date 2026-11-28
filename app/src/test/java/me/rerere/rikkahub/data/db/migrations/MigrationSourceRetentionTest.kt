package me.rerere.rikkahub.data.db.migrations

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pure production decode boundaries; Android tests separately exercise the actual DB transactions. */
class MigrationSourceRetentionTest {
    @Test
    fun legacyNodesRejectInvalidInputInsteadOfReturningEmptyHistory() {
        // Lenient JSON accepts this as a primitive; the production array-shape guard must fail.
        assertThrows(IllegalStateException::class.java) { legacyNodesForMigration("not-json") }
        assertThrows(IllegalStateException::class.java) { legacyNodesForMigration("{}") }
        for (value in listOf("[null]", "[{}]", "[{\"messages\":{}}]")) {
            assertThrows(IllegalArgumentException::class.java) { legacyNodesForMigration(value) }
        }
    }

    @Test
    fun emptyHistoryAndEveryValidBranchRemainSupported() {
        assertEquals(0, legacyNodesForMigration("[]").size)
        val original = """[{"messages":[{"parts":[{"type":"text","text":"one"}]}],"selectIndex":0},{"messages":[],"selectIndex":0}]"""
        assertEquals(2, legacyNodesForMigration(original).size)
        assertEquals(original, legacyNodesForMigration(original).toString())
    }

    @Test
    fun toolMigrationCannotSilentlySkipAnUnknownOrMalformedMessageRow() {
        assertEquals(emptyList<Any>(), messagesForMigration("[]"))
        assertThrows(SerializationException::class.java) { messagesForMigration("not-json") }
        assertThrows(SerializationException::class.java) {
            messagesForMigration("""[{"role":"ASSISTANT","parts":[{"type":"future_unknown"}]}]""")
        }
    }
}
