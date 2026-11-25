package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class WorkspaceFileSystemBoundaryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val fs = WorkspaceFileSystem()

    @Test
    fun equivalentRootPathsAreProtectedFromDeleteAndMove() {
        val root = temporary.newFolder("workspace")
        File(root, "a").mkdir()
        val keep = File(root, "keep.txt").apply { writeText("keep") }
        for (path in listOf(".", "./", "a/..", "/", "", "  ", " ./ ")) {
            assertThrows(IllegalArgumentException::class.java) { fs.delete(root, path, true) }
            assertThrows(IllegalArgumentException::class.java) { fs.move(root, path, "moved") }
            assertThrows(IllegalArgumentException::class.java) { fs.move(root, "keep.txt", path, true) }
            assertTrue(root.isDirectory)
            assertEquals("keep", keep.readText())
        }
    }

    @Test
    fun rootSymlinkAliasesAndSamePathMovesPreserveFiles() {
        val root = temporary.newFolder("root")
        val keep = File(root, "keep.txt").apply { writeText("keep") }
        Files.createSymbolicLink(File(root, "alias").toPath(), root.toPath())
        assertThrows(IllegalArgumentException::class.java) { fs.delete(root, "alias", true) }
        assertThrows(IllegalArgumentException::class.java) { fs.move(root, "alias", "moved") }
        assertThrows(IllegalArgumentException::class.java) { fs.move(root, "keep.txt", "./keep.txt", true) }
        assertEquals("keep", keep.readText())
    }

    @Test
    fun recursiveDeleteDoesNotFollowNestedDirectorySymlinks() {
        val root = temporary.newFolder("root")
        val outside = temporary.newFolder("outside")
        val sentinel = File(outside, "sentinel").apply { writeText("outside") }
        val child = File(root, "child").apply { mkdir() }
        Files.createSymbolicLink(File(child, "link").toPath(), outside.toPath())
        assertTrue(fs.delete(root, "child", true))
        assertFalse(child.exists())
        assertEquals("outside", sentinel.readText())
        Files.createSymbolicLink(File(root, "outside").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { fs.delete(root, "outside", true) }
        assertEquals("outside", sentinel.readText())
    }

    @Test
    fun grepSkipsFileAndDirectorySymlinksAndKeepsNormalMatches() {
        val root = temporary.newFolder("root")
        val outside = temporary.newFolder("root-sibling")
        val secret = File(outside, "secret.txt").apply { writeText("needle external") }
        File(root, "normal.txt").writeText("first\nneedle internal\n")
        Files.createSymbolicLink(File(root, "leak.txt").toPath(), secret.toPath())
        Files.createSymbolicLink(File(root, "directory").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "inside-link.txt").toPath(), File(root, "normal.txt").toPath())
        val matches = fs.grep(root, "needle", includeGlob = "*.txt")
        assertEquals(1, matches.size)
        assertEquals("normal.txt", matches.single().path)
        assertEquals(2, matches.single().line)
        assertEquals("needle internal", matches.single().text)
        assertThrows(IllegalArgumentException::class.java) { fs.grep(root, "needle", path = "leak.txt") }
    }

    @Test
    fun ordinaryMoveAndDeleteRemainSupported() {
        val root = temporary.newFolder("root")
        File(root, "source.txt").writeText("content")
        fs.move(root, "source.txt", "nested/target.txt")
        assertEquals("content", File(root, "nested/target.txt").readText())
        assertTrue(fs.delete(root, "nested", true))
        assertTrue(root.isDirectory)
    }
}
