package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BackupManifestTest {
    private val payload = "synthetic backup data".toByteArray()
    private fun manifest(entries: List<BackupManifestEntry> = listOf(BackupManifest.entry("settings.json", payload))) =
        BackupManifest.encode(entries, 1234, "test-only")

    private fun archive(
        files: Map<String, ByteArray> = mapOf("settings.json" to payload),
        manifest: String? = manifest(),
        stored: Boolean = false,
    ): File = File.createTempFile("manifest-test-", ".zip").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in files +
                (manifest?.let { mapOf(BackupManifest.FILE_NAME to it.toByteArray()) } ?: emptyMap())) {
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun verify(file: File) = ZipFile(file).use { BackupManifest.verify(it) }
    private fun expectFailure(file: File) {
        try {
            assertTrue(runCatching { verify(file) }.isFailure)
        } finally {
            file.delete()
        }
    }

    @Test fun versionedArchiveChecksEveryPayloadHash() {
        val file = archive()
        try {
            assertEquals(listOf(BackupManifest.entry("settings.json", payload)), verify(file))
        } finally {
            file.delete()
        }
    }

    @Test fun legacyZipWithoutManifestRemainsReadableWithCrcChecks() {
        val file = archive(manifest = null)
        try {
            assertNull(verify(file))
        } finally {
            file.delete()
        }
    }

    @Test fun validZipCrcCannotHidePayloadHashMismatch() {
        expectFailure(archive(files = mapOf("settings.json" to "changed backup bytes".toByteArray())))
    }

    @Test fun missingPayloadIsRejected() {
        expectFailure(archive(files = emptyMap()))
    }

    @Test fun undeclaredPayloadIsRejectedEvenIfNotSelectedForRestore() {
        expectFailure(archive(files = mapOf("settings.json" to payload, "uploads/extra.bin" to payload)))
    }

    @Test fun unsupportedFormatVersionIsRejected() {
        expectFailure(archive(manifest = manifest().replace("\"formatVersion\":1", "\"formatVersion\":99")))
    }

    @Test fun incorrectLengthInManifestIsRejected() {
        expectFailure(archive(manifest = manifest(listOf(BackupManifest.entry("settings.json", payload).copy(size =
            payload.size + 1L)))))
    }

    @Test fun malformedManifestIsRejectedInsteadOfFallingBackToLegacy() {
        expectFailure(archive(manifest = "not valid JSON"))
    }

    @Test fun duplicateManifestEntriesAreRejected() {
        val entry = BackupManifest.entry("settings.json", payload)
        assertTrue(runCatching { manifest(listOf(entry, entry)) }.isFailure)
    }

    @Test fun unsafePathsAndReservedManifestPathAreRejected() {
        for (path in listOf("../outside", "/absolute", "a/../b", "a\\b", "a//b", "manifest.json", "a\u0000b")) {
            assertTrue(path, runCatching { manifest(listOf(BackupManifest.entry(path, payload))) }.isFailure)
        }
    }

    @Test fun legacyTraversalPathIsRejectedBeforeAnyExtraction() {
        expectFailure(archive(files = mapOf("../outside" to payload), manifest = null))
    }

    @Test fun invalidChecksumsAndUnsafeDeclaredSizesAreRejected() {
        val entry = BackupManifest.entry("settings.json", payload)
        for (bad in listOf(entry.copy(sha256 = "invalid"), entry.copy(size = -1), entry.copy(size = Long.MAX_VALUE))) {
            assertTrue(runCatching { manifest(listOf(bad)) }.isFailure)
        }
    }

    @Test fun cumulativeArchiveSizeIsBoundedWithoutAllocatingLargeFiles() {
        val entries = (1..3).map { BackupManifest.entry("file$it", payload).copy(size = 16L * 1024 * 1024 * 1024) }
        assertTrue(runCatching { manifest(entries) }.isFailure)
    }

    @Test fun corruptLegacyPayloadIsRejectedByCrc() {
        val file = archive(manifest = null, stored = true)
        val bytes = file.readBytes()
        val index = bytes.indices.first { start ->
            start + payload.size <= bytes.size &&
                bytes.copyOfRange(start, start + payload.size).contentEquals(payload)
        }
        bytes[index] = (bytes[index].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        expectFailure(file)
    }

    @Test fun corruptManifestBytesAreRejectedBeforeParsingEvenWithValidPayload() {
        val file = archive(stored = true)
        val bytes = file.readBytes()
        val manifestBytes = manifest().toByteArray()
        val index = bytes.indices.first { start ->
            start + manifestBytes.size <= bytes.size &&
                bytes.copyOfRange(start, start + manifestBytes.size).contentEquals(manifestBytes)
        }
        // Keep JSON valid; a missing CRC check would still accept this altered timestamp.
        val timestamp = "1234".toByteArray()
        val pos = (index until index + manifestBytes.size).first { start ->
            start + timestamp.size <= bytes.size &&
                bytes.copyOfRange(start, start + timestamp.size).contentEquals(timestamp)
        }
        bytes[pos] = '9'.code.toByte()
        file.writeBytes(bytes)
        expectFailure(file)
    }

    @Test fun splitBackupKindRoundTripsAndUnknownKindsFailClosed() {
        for (kind in BackupKind.entries) {
            val file =
                archive(
                    manifest = BackupManifest.encode(
                        listOf(BackupManifest.entry("settings.json", payload)),
                        1234,
                        "test-only",
                        kind
                    )
                )
            try {
                ZipFile(file).use { assertEquals(kind, BackupManifest.verifyDetailed(it).kind) }
            } finally {
                file.delete()
            }
        }
        expectFailure(archive(manifest = manifest().replace("\"kind\":\"full\"", "\"kind\":\"unknown\"")))
    }

    @Test fun olderVersionedManifestWithoutKindRemainsFull() {
        val legacyManifest = manifest().replace("\"kind\":\"full\",", "")
        val file = archive(manifest = legacyManifest)
        try {
            ZipFile(file).use { assertEquals(BackupKind.FULL, BackupManifest.verifyDetailed(it).kind) }
        } finally {
            file.delete()
        }
    }

    @Test fun duplicatePhysicalZipEntriesAreRejected() {
        val file = archive(files = mapOf("a.txt" to payload, "b.txt" to payload), manifest = null)
        val bytes = file.readBytes()
        val name = "b.txt".toByteArray()
        for (index in 0..bytes.size - name.size) {
            if (bytes.copyOfRange(index, index + name.size).contentEquals(name)) bytes[index] = 'a'.code.toByte()
        }
        file.writeBytes(bytes)
        expectFailure(file)
    }

    @Test fun verificationCanBeCancelledWithoutSwallowingCancellation() {
        val file = archive()
        try {
            val failure = runCatching {
                ZipFile(
                    file
                ).use { zip -> BackupManifest.verify(zip) { throw CancellationException("synthetic cancellation") } }
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
        } finally {
            file.delete()
        }
    }
}
