package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileUtilsTest {
    private val uuidWithExtension =
        Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z0-9]{1,8}")

    @Test
    fun `normal extension is kept`() {
        assertTrue(uuidWithExtension.matches(FileUtils.buildUuidFileName("photo.PNG", null)))
        assertTrue(FileUtils.buildUuidFileName("notes.md", null).endsWith(".md"))
    }

    @Test
    fun `extension containing a path separator cannot escape the target folder`() {
        // 导入的备份包 / 远端 MCP 配置可以直接给文件名，这类名字过去会把扩展名变成 "../.." 路径。
        val evil = FileUtils.buildUuidFileName("x./../../datastore/settings.preferences_pb", null)
        assertEquals("bin", evil.substringAfterLast('.'))
        assertTrue(uuidWithExtension.matches(evil))
        assertTrue(!evil.contains('/'))
        assertTrue(!evil.contains(".."))
    }

    @Test
    fun `overlong extension falls back to bin`() {
        assertEquals("bin", FileUtils.buildUuidFileName("a.verylongextension", null).substringAfterLast('.'))
    }
}
