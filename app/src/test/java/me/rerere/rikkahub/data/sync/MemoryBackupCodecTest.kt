package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBackupCodecTest {
    private val first =
        MemoryBackupRecord(
            "assistant-a",
            "/profile.md",
            "---\ntitle: synthetic\n---\nKeep this exact synthetic text.\n",
            1234
        )

    @Test fun contentIdentityAndTimestampsRoundTripWithoutRewriting() {
        assertEquals(listOf(first), MemoryBackupCodec.decode(MemoryBackupCodec.encode(listOf(first))))
    }

    @Test fun assistantsRemainIsolatedEvenWithTheSamePath() {
        val other = first.copy(assistantId = "assistant-b", content = "different synthetic memory")
        assertEquals(listOf(first, other), MemoryBackupCodec.decode(MemoryBackupCodec.encode(listOf(other, first))))
    }

    @Test fun outputOrderingIsStableForDirtyDetection() {
        val second = first.copy(path = "/topics/second.md")
        assertEquals(MemoryBackupCodec.encode(listOf(first, second)), MemoryBackupCodec.encode(listOf(second, first)))
    }

    @Test fun duplicateRecordsCannotSilentlyOverwriteEachOther() {
        assertTrue(runCatching { MemoryBackupCodec.encode(listOf(first, first.copy(content = "conflict"))) }.isFailure)
    }

    @Test fun duplicateNormalizedPathsAreRejectedOnImport() {
        val input = """{
            "formatVersion":1,"memories":[
                {"assistantId":"a","path":"profile.md","content":"first","updatedAt":1},
                {"assistantId":"a","path":"/profile.md","content":"second","updatedAt":2}
            ]
        }"""
        assertTrue(runCatching { MemoryBackupCodec.decode(input) }.isFailure)
    }

    @Test fun malformedContentIsNotCoercedIntoAString() {
        val input = """{
            "formatVersion":1,"memories":[{"assistantId":"a","path":"/profile.md","content":42,"updatedAt":1}]
        }"""
        assertTrue(runCatching { MemoryBackupCodec.decode(input) }.isFailure)
    }

    @Test fun unsupportedVersionsAndMalformedJsonFailClosed() {
        assertTrue(runCatching { MemoryBackupCodec.decode("not JSON") }.isFailure)
        assertTrue(runCatching { MemoryBackupCodec.decode("""{"formatVersion":99,"memories":[]}""") }.isFailure)
    }

    @Test fun invalidPathsAndAssistantIdentifiersAreRejected() {
        for (record in listOf(
            first.copy(path = "/../escape.md"),
            first.copy(path = "/.hidden.md"),
            first.copy(assistantId = ""),
            first.copy(assistantId = "a\u0000b")
        )) {
            assertTrue(runCatching { MemoryBackupCodec.encode(listOf(record)) }.isFailure)
        }
    }

    @Test fun invalidTimestampsAreRejected() {
        assertTrue(runCatching { MemoryBackupCodec.encode(listOf(first.copy(updatedAt = -1))) }.isFailure)
    }

    @Test fun emptyMemoryStoreIsAValidSnapshot() {
        assertEquals(emptyList<MemoryBackupRecord>(), MemoryBackupCodec.decode(MemoryBackupCodec.encode(emptyList())))
    }

    @Test fun oversizedHistoricalMemoryCanBePreservedWithoutTheNormalWriteLimit() {
        val old = first.copy(content = "synthetic text\n".repeat(5000))
        assertEquals(listOf(old), MemoryBackupCodec.decode(MemoryBackupCodec.encode(listOf(old))))
    }
}
