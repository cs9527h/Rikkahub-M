package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BackupAssetSnapshotsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun settingsOnlySnapshotRetainsRetiredAvatarAndBackgroundAtOriginalPaths() {
        assertRetiredAssetsAreRecoverable(includeFiles = false)
    }

    @Test fun fullFileExportRetainsRetiredAvatarAndDoesNotIncludePrivateSnapshotDirectory() {
        assertRetiredAssetsAreRecoverable(includeFiles = true)
    }

    @Test fun fullFileExportUsesSnapshotInsteadOfChangedLiveFileWithTheSameEntryName() {
        val files = temporary.newFolder("files")
        val staging = temporary.newFolder("private-staging")
        val avatar = create(files, "avatars/A.png", "avatar A at settings read")
        val snapshots = BackupAssetSnapshots.capture(mapOf("avatars/A.png" to avatar), files, staging)
        avatar.writeText("different bytes at the same path")
        val archive = archive(snapshots, files, includeFiles = true)
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toList()
            assertEquals(listOf("avatars/A.png"), entries)
            assertEquals(
                "avatar A at settings read",
                zip.getInputStream(zip.getEntry("avatars/A.png")).reader().readText()
            )
        }
    }

    @Test fun deletingSourceImmediatelyBeforeOpeningCopyFailsInsteadOfPublishingMissingAsset() {
        val files = temporary.newFolder("files")
        val staging = temporary.newFolder("private-staging")
        val avatar = create(files, "avatars/A.png", "avatar A")
        var blocks = 0
        assertThrows(NoSuchFileException::class.java) {
            BackupAssetSnapshots.capture(mapOf("avatars/A.png" to avatar), files, staging) {
                // After collecting/validating metadata, immediately before the source stream is opened.
                blocks++
                if (blocks == 2) assertTrue(avatar.delete())
            }
        }
        assertFalse(File(staging, "avatars/A.png").exists())
    }

    @Test fun missingSourceAtSnapshotStartFailsClosed() {
        val files = temporary.newFolder("files")
        val staging = temporary.newFolder("private-staging")
        assertThrows(NoSuchFileException::class.java) {
            BackupAssetSnapshots.capture(
                mapOf("avatars/missing.png" to File(files, "avatars/missing.png")),
                files,
                staging
            )
        }
    }

    @Test fun sourceTruncatedWhileStreamingFailsAndRemovesPartialSnapshot() {
        val files = temporary.newFolder("files")
        val staging = temporary.newFolder("private-staging")
        val avatar = create(files, "avatars/A.png", "A".repeat(192 * 1024))
        var blocks = 0
        assertThrows(IllegalArgumentException::class.java) {
            BackupAssetSnapshots.capture(mapOf("avatars/A.png" to avatar), files, staging) {
                blocks++
                if (blocks == 4) avatar.writeText("truncated")
            }
        }
        assertFalse(File(staging, "avatars/A.png").exists())
    }

    @Test fun sameSizedSourceReplacementWhileStreamingFailsIdentityCheck() {
        val files = temporary.newFolder("files")
        val staging = temporary.newFolder("private-staging")
        val avatar = create(files, "avatars/A.png", "A".repeat(192 * 1024))
        var blocks = 0
        assertThrows(IllegalArgumentException::class.java) {
            BackupAssetSnapshots.capture(mapOf("avatars/A.png" to avatar), files, staging) {
                blocks++
                if (blocks == 4) {
                    val oldTime = avatar.lastModified()
                    assertTrue(avatar.delete())
                    avatar.writeText("B".repeat(192 * 1024))
                    assertTrue(avatar.setLastModified(oldTime))
                }
            }
        }
        assertFalse(File(staging, "avatars/A.png").exists())
    }

    @Test fun cancellationDuringCopyPropagatesAndRemovesPartialSnapshot() {
        val files = temporary.newFolder("files")
        val staging = temporary.newFolder("private-staging")
        val avatar = create(files, "avatars/A.png", "A".repeat(192 * 1024))
        var blocks = 0
        assertThrows(CancellationException::class.java) {
            BackupAssetSnapshots.capture(mapOf("avatars/A.png" to avatar), files, staging) {
                blocks++
                if (blocks == 4) throw CancellationException("cancelled")
            }
        }
        assertFalse(File(staging, "avatars/A.png").exists())
    }

    @Test fun traversalAndSymlinkEscapeAndSnapshotInsideLiveFilesAreRejected() {
        val files = temporary.newFolder("files")
        val staging = temporary.newFolder("private-staging")
        val outside = temporary.newFile("outside.png").apply { writeText("outside") }
        assertThrows(IllegalArgumentException::class.java) {
            BackupAssetSnapshots.capture(mapOf("../outside.png" to outside), files, staging)
        }
        val avatars = File(files, "avatars").apply { mkdirs() }
        Files.createSymbolicLink(File(avatars, "escape.png").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            BackupAssetSnapshots.capture(mapOf("avatars/escape.png" to outside), files, staging)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupAssetSnapshots.capture(emptyMap(), files, File(files, "snapshots"))
        }
        assertFalse(File(files, "snapshots").exists())
    }

    @Test fun destinationFailureIsNotReturnedAsSuccessfulSnapshot() {
        val files = temporary.newFolder("files")
        val staging = temporary.newFolder("private-staging")
        val avatar = create(files, "avatars/A.png", "avatar A")
        File(staging, "avatars").writeText("not a directory")
        assertThrows(IllegalStateException::class.java) {
            BackupAssetSnapshots.capture(mapOf("avatars/A.png" to avatar), files, staging)
        }
    }

    private fun assertRetiredAssetsAreRecoverable(includeFiles: Boolean) {
        val files = temporary.newFolder("files")
        val staging = temporary.newFolder("private-staging")
        val avatar = create(files, "avatars/A.png", "avatar A")
        val background = create(files, "upload/background.png", "old background")
        val snapshots = BackupAssetSnapshots.capture(
            mapOf("avatars/A.png" to avatar, "upload/background.png" to background),
            files,
            staging,
        )
        assertTrue(avatar.delete())
        assertTrue(background.delete())
        create(files, "avatars/B.png", "avatar B")
        val archive = archive(snapshots, files, includeFiles)
        ZipFile(archive).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertEquals(names.size, names.toSet().size)
            assertEquals("avatar A", zip.getInputStream(zip.getEntry("avatars/A.png")).reader().readText())
            assertEquals(
                "old background",
                zip.getInputStream(zip.getEntry("upload/background.png")).reader().readText()
            )
            if (includeFiles) assertNotNull(zip.getEntry("avatars/B.png"))
            assertTrue(names.none { "private-staging" in it || "core-assets" in it })
        }
    }

    /** Same snapshot-first/deduplicated entry contract used by BackupManager, with real ZIP I/O. */
    private fun archive(snapshots: Map<String, File>, files: File, includeFiles: Boolean): File {
        val archive = temporary.newFile("backup.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            fun write(name: String, file: File) {
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            snapshots.forEach { (name, file) -> write(name, file) }
            if (includeFiles) {
                files.walkTopDown().filter { it.isFile }.forEach { file ->
                    val name = file.relativeTo(files).invariantSeparatorsPath
                    if (name !in snapshots) write(name, file)
                }
            }
        }
        return archive
    }

    private fun create(root: File, name: String, text: String): File = File(root, name).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }
}
