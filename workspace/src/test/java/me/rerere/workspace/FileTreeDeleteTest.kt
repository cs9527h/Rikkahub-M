package me.rerere.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class FileTreeDeleteTest {

    @Test
    fun `deleteTreeNoFollow removes tree but leaves symlink target intact`() {
        val base = Files.createTempDirectory("ws-delete-test").toFile()
        try {
            val outside = base.resolve("outside").apply { mkdirs() }
            val victim = outside.resolve("victim.txt").apply { writeText("keep me") }
            val tree = base.resolve("tree").apply { mkdirs() }
            val nested = tree.resolve("nested").apply { mkdirs() }
            nested.resolve("inner.txt").writeText("inner")

            // 模拟 AI 在工作区里造的软链：tree/nested/link -> outside
            val link = nested.resolve("link").toPath()
            val symlinkCreated = runCatching {
                Files.createSymbolicLink(link, outside.toPath())
            }.isSuccess

            assertTrue(deleteTreeNoFollow(tree))
            assertFalse("tree 应被删除", Files.exists(Paths.get(tree.absolutePath)))
            assertTrue("软链目标不能被删", victim.exists())
            if (symlinkCreated) {
                assertTrue(Files.exists(Paths.get(victim.absolutePath)))
            }
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `deleteTreeNoFollow handles plain files and missing paths`() {
        val base = Files.createTempDirectory("ws-delete-file").toFile()
        try {
            val file = base.resolve("a.txt").apply { writeText("a") }
            assertTrue(deleteTreeNoFollow(file))
            assertFalse(file.exists())
            assertTrue("不存在的路径视为已删除", deleteTreeNoFollow(base.resolve("missing")))
        } finally {
            base.deleteRecursively()
        }
    }
}
