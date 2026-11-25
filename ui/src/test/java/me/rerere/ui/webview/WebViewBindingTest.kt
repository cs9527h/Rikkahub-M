package me.rerere.ui.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewBindingTest {
    @Test
    fun `theme state replacement removes every old javascript bridge and reloads data`() {
        val binding = WebViewBinding()
        val old = WebViewState(WebContent.Data("light"), mapOf("oldBridge" to Any(), "shared" to Any()))
        val next = WebViewState(WebContent.Data("dark"), mapOf("shared" to Any()))
        next.lastLoadedData = WebContent.Data("stale cached dark")
        val removed = mutableListOf<String>()

        binding.switchTo(old, removed::add)
        binding.switchTo(next, removed::add)

        assertEquals(listOf("oldBridge", "shared"), removed)
        assertSame(next, binding.state)
        assertNull(next.lastLoadedData)
        assertTrue(next.forceReload)
        assertNull(old.webView)
    }

    @Test
    fun `switching back to previous state invalidates its loaded content marker`() {
        val binding = WebViewBinding()
        val first = WebViewState(WebContent.Data("first"))
        val second = WebViewState(WebContent.Data("second"))
        binding.switchTo(first) {}
        first.lastLoadedData = WebContent.Data("first")
        binding.switchTo(second) {}
        binding.switchTo(first) {}
        assertNull(first.lastLoadedData)
        assertTrue(first.forceReload)
    }
}
