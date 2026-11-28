package me.rerere.common.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExceptionDiagnosticsTest {
    @Test
    fun `operation type cause and code location survive without payloads`() {
        val cause = IllegalArgumentException("prompt: private conversation")
        val error =
            IllegalStateException("HTTP body token=secret https://host/?key=secret /sdcard/private/photo.jpg", cause)
        error.stackTrace = arrayOf(StackTraceElement("example.Provider", "request", "/home/alice/secret.kt", 42))
        val result = formatExceptionForLog("provider.generate", error)
        assertTrue(result.startsWith("provider.generate failed: java.lang.IllegalStateException"))
        assertTrue(result.contains("example.Provider.request:42"))
        assertTrue(result.contains("Caused by: java.lang.IllegalArgumentException"))
        listOf("private conversation", "secret", "https://", "sdcard", "alice", "prompt:").forEach {
            assertFalse("diagnostic leaked $it", result.contains(it))
        }
    }

    @Test
    fun `cyclic and deep cause chains terminate with bounded output`() {
        val first = Exception("first")
        val second = Exception("second", first)
        first.initCause(second)
        assertTrue(formatExceptionForLog("test.cycle", first).contains("[cause cycle]"))
        var deep: Throwable = Exception("leaf")
        repeat(100) { deep = Exception("node", deep) }
        val result = formatExceptionForLog("test.deep", deep)
        assertTrue(result.contains("[causes truncated]"))
        assertTrue(result.length <= 8192)
    }

    @Test
    fun `huge messages and stacks do not enter diagnostics`() {
        val error = Exception("secret".repeat(1_000_000))
        error.stackTrace = Array(1000) { StackTraceElement("example.Type", "request", "secret", it) }
        val result = formatExceptionForLog("test.large", error)
        assertTrue(result.length <= 8192)
        assertTrue(result.contains("[frames truncated]"))
        assertFalse(result.contains("secret"))
        assertEquals(12, result.lineSequence().count { it.startsWith("  at ") })
    }

    @Test
    fun `malformed operation and synthetic path frames are redacted`() {
        val error = Exception("Bearer sk-private key=value access_token=private")
        error.stackTrace = arrayOf(StackTraceElement("https://host/?token=private", "/home/private", "private", 1))
        val result = formatExceptionForLog("https://host/?token=private", error)
        assertTrue(result.startsWith("operation failed"))
        assertTrue(result.contains("[redacted]"))
        listOf("private", "Bearer", "key=value", "token=", "/home/").forEach { assertFalse(result.contains(it)) }
    }
}
