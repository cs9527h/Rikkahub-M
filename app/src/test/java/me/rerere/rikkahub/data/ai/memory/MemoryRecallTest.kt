package me.rerere.rikkahub.data.ai.memory

import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.repository.FakeMemoryDAO
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRecallTest {
    @Test fun recallsChineseAliasesAndRespectsScopeTrashAndRestore() = runTest {
        val repo = MemoryRepository(FakeMemoryDAO())
        repeat(1000) { repo.writeFile("assistant-a", "/topics/fixture-$it.md", "Synthetic unrelated data $it") }
        val path = "/areas/lab.md"
        val content = MemoryFile(path, "", 0).contentWith("实验项目", "自动化课", listOf("电机控制"), "完成了二阶系统")
        repo.writeFile("assistant-a", path, content)
        assertEquals(listOf(path), repo.searchForRecall("assistant-a", "电机控制学到哪了").map { it.path })
        assertTrue(repo.searchForRecall("assistant-b", "电机控制").isEmpty())
        repo.deleteFile("assistant-a", path)
        assertTrue(repo.searchForRecall("assistant-a", "电机控制").isEmpty())
        repo.restoreFile("assistant-a", path)
        assertEquals(listOf(path), repo.searchForRecall("assistant-a", "电机控制").map { it.path })
        val restored = checkNotNull(repo.getFile("assistant-a", path))
        repo.writeFile("assistant-a", path, restored.contentWithOutdated(true), restored.version)
        assertTrue(repo.searchForRecall("assistant-a", "电机控制").isEmpty())
        val outdated = checkNotNull(repo.getFile("assistant-a", path))
        repo.writeFile("assistant-a", path, outdated.contentWithOutdated(false), outdated.version)
        assertEquals(path, repo.searchForRecall("assistant-a", "电机控制").single().path)
    }

    @Test fun excerptsAreBoundedAndExcludeSourceMetadata() {
        val long = (1..20).map { MemoryFile("/topics/fixture-$it.md", "项目".repeat(3_000) + "\n<!-- history-source synthetic -->", it.toLong()) }
        val excerpts = recallExcerpts(long)
        assertTrue(excerpts.isNotEmpty())
        assertTrue(excerpts.sumOf { it.content.length } in 1..4000)
        assertFalse(excerpts.any { "history-source" in it.content })
    }
}
