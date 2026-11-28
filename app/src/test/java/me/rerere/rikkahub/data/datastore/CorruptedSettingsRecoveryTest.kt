package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CorruptedSettingsRecoveryTest {
    @get:Rule val folder = TemporaryFolder()
    private val corruptedBytes = byteArrayOf(0x00, 0x7f, 0xff.toByte(), 0x81.toByte(), 0x01)

    @Test fun `reset becomes available only after exact original bytes are preserved`() {
        val source = folder.newFile("settings.preferences_pb").apply { writeBytes(corruptedBytes) }
        val backup = folder.root.resolve("settings.corrupt-copy")
        val replacement = recoverCorruptedSettings(source, backup)
        assertTrue(replacement.asMap().isEmpty())
        assertArrayEquals(corruptedBytes, source.readBytes())
        assertArrayEquals(corruptedBytes, backup.readBytes())
    }

    @Test fun `backup write failure does not return defaults or alter last original`() {
        val source = folder.newFile("settings.preferences_pb").apply { writeBytes(corruptedBytes) }
        val blocker = folder.newFile("not-a-directory").apply { writeText("kept") }
        var replacementReturned = false
        val result = runCatching {
            recoverCorruptedSettings(source, blocker.resolve("backup"))
            replacementReturned = true
        }
        assertTrue(result.isFailure)
        assertFalse(replacementReturned)
        assertArrayEquals(corruptedBytes, source.readBytes())
        assertTrue(blocker.readText() == "kept")
    }

    @Test fun `existing salvage copy is not overwritten when retrying recovery`() {
        val source = folder.newFile("settings.preferences_pb").apply { writeBytes(corruptedBytes) }
        val backup = folder.newFile("settings.corrupt-copy").apply { writeText("earlier salvage") }
        assertTrue(runCatching { recoverCorruptedSettings(source, backup) }.isFailure)
        assertTrue(backup.readText() == "earlier salvage")
        assertArrayEquals(corruptedBytes, source.readBytes())
    }
}
