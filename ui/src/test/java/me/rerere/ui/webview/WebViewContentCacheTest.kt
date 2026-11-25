package me.rerere.ui.webview

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class WebViewContentCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `stores large content outside navigation state`() {
        val content = "<html>${"preview".repeat(50_000)}</html>"

        val id = WebViewContentCache.store(temporaryFolder.root, content)

        assertEquals(64, id.length)
        assertEquals(content, WebViewContentCache.load(temporaryFolder.root, id))
    }

    @Test
    fun `reuses the same cache entry for identical content`() {
        val content = "<html>preview</html>"

        val firstId = WebViewContentCache.store(temporaryFolder.root, content)
        val secondId = WebViewContentCache.store(temporaryFolder.root, content)

        assertEquals(firstId, secondId)
        assertEquals(1, temporaryFolder.root.resolve("webview_content").listFiles()?.size)
    }

    @Test
    fun `async concurrent previews never load a partially written cache file`() = runBlocking {
        val content = "<html>${"large".repeat(100_000)}</html>"
        val ids = (1..8).map {
            async {
                val id = WebViewContentCache.storeAsync(temporaryFolder.root, content)
                assertEquals(content, WebViewContentCache.loadAsync(temporaryFolder.root, id))
                id
            }
        }.awaitAll()
        assertEquals(1, ids.toSet().size)
    }

    @Test
    fun `expired cache cleanup is throttled across repeated previews`() {
        val directory = temporaryFolder.root.resolve("webview_content").apply { mkdirs() }
        val expired = File(directory, "expired").apply {
            writeText("old")
            setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8))
        }
        WebViewContentCache.store(temporaryFolder.root, "first")
        assertFalse(expired.exists())

        // Simulate another old entry appearing after the first scan. A repeated
        // preview should not synchronously rescan the entire directory again.
        expired.writeText("old")
        expired.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8))
        WebViewContentCache.store(temporaryFolder.root, "second")
        assertTrue(expired.exists())
    }

    @Test
    fun `rejects invalid cache ids`() {
        assertNull(WebViewContentCache.load(temporaryFolder.root, "../content"))
    }
}
