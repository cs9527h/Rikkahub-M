package me.rerere.rikkahub.data.files

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Host-runnable real filesystem tests; no Android, Room, mocks or Gradle required. */
class AvatarFileQuarantineTest {
    private val directory = Files.createTempDirectory("avatar-quarantine-test-").toFile()
    private val files = File(directory, "files").apply { mkdirs() }
    private val noBackup = File(directory, "no-backup").apply { mkdirs() }
    private val quarantine = AvatarFileQuarantine(files, noBackup)

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    @Test fun preparedMoveDoesNotTouchSourceUntilRetiredAndUnlinkIsSeparate() {
        val source = file("avatars/a b中文#?.png")
        val move = checkNotNull(quarantine.prepare("avatars/${source.name}"))
        assertTrue(source.isFile)
        assertFalse(move.moved)
        assertTrue(retiredFiles().isEmpty())
        assertFalse(move.unlink())
        assertTrue(move.retire())
        assertTrue(move.moved)
        assertFalse(source.exists())
        assertEquals("fixture image", retiredFiles().single().readText())
        assertTrue(move.unlink())
        assertTrue(retiredFiles().isEmpty())
    }

    @Test fun actualRenameFailurePreservesOriginal() {
        val source = file("avatars/failed.png")
        val move = checkNotNull(quarantine.prepare("avatars/failed.png"))
        val targetDirectory = File(noBackup, AvatarFileQuarantine.DIRECTORY)
        assertTrue(targetDirectory.delete())
        targetDirectory.writeText("not a directory")
        assertFalse(move.retire())
        assertFalse(move.moved)
        assertFalse(move.unlink())
        assertEquals("fixture image", source.readText())
    }

    @Test fun sweepRecoversCrashOrphanWithoutChangingLegacyUploads() {
        val source = file("avatars/abandoned.png")
        val unrelated = file("upload/legacy.png")
        val move = checkNotNull(quarantine.prepare("avatars/abandoned.png"))
        assertTrue(move.retire())
        assertFalse(source.exists())
        assertEquals(1, retiredFiles().size)
        AvatarFileQuarantine(files, noBackup).sweep()
        assertTrue(retiredFiles().isEmpty())
        assertEquals("fixture image", unrelated.readText())
    }

    @Test fun rejectsTraversalAndSourceSymlinksIncludingFolderSymlinks() {
        val outside = File(directory, "outside").apply { mkdirs() }
        val secret = File(outside, "secret.png").apply { writeText("keep") }
        File(files, "avatars").mkdirs()
        Files.createSymbolicLink(File(files, "avatars/link.png").toPath(), secret.toPath())
        Files.createSymbolicLink(File(files, "upload").toPath(), outside.toPath())
        assertNull(quarantine.prepare("avatars/link.png"))
        assertNull(quarantine.prepare("upload/secret.png"))
        listOf("../outside/secret.png", "avatars/../secret.png", "avatars/a/b", "avatars/..", "avatars/a\\b")
            .forEach { assertNull(quarantine.prepare(it)) }
        assertEquals("keep", secret.readText())
    }

    @Test fun rejectsSymlinkedQuarantineWithoutWritingOutsidePrivateNamespace() {
        val source = file("avatars/keep.png")
        val outside = File(directory, "outside").apply { mkdirs() }
        Files.createSymbolicLink(File(noBackup, AvatarFileQuarantine.DIRECTORY).toPath(), outside.toPath())
        assertNull(quarantine.prepare("avatars/keep.png"))
        quarantine.sweep()
        assertTrue(source.isFile)
        assertTrue(outside.listFiles().orEmpty().isEmpty())
    }

    @Test fun sweepDoesNotFollowSymlinksRecurseOrRemoveUnrecognisedFiles() {
        val source = file("avatars/keep.png")
        checkNotNull(quarantine.prepare("avatars/keep.png"))
        val targetDirectory = File(noBackup, AvatarFileQuarantine.DIRECTORY)
        val link = File(targetDirectory, "00000000-0000-0000-0000-000000000001.retired")
        Files.createSymbolicLink(link.toPath(), source.toPath())
        val nested = File(targetDirectory, "00000000-0000-0000-0000-000000000002.retired").apply { mkdirs() }
        File(nested, "child").writeText("keep child")
        val unknown = File(targetDirectory, "unrecognised").apply { writeText("keep unknown") }
        quarantine.sweep()
        assertTrue(source.isFile)
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("keep child", File(nested, "child").readText())
        assertTrue(unknown.isFile)
    }

    private fun retiredFiles() = File(noBackup, AvatarFileQuarantine.DIRECTORY).listFiles().orEmpty()

    private fun file(relative: String) = File(files, relative).apply {
        parentFile!!.mkdirs()
        writeText("fixture image")
    }
}
