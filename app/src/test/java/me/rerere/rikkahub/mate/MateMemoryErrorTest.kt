package me.rerere.rikkahub.mate

import org.junit.Assert.assertTrue
import org.junit.Test

class MateMemoryErrorTest {
    @Test
    fun legacyGateway404ExplainsDeployment() {
        val message = humanizeMemoryError("网关 HTTP 404")
        assertTrue(message.contains("记忆中心接口"))
        assertTrue(message.contains("HTTP 404"))
    }

    @Test
    fun missingMemoryConfigurationIsNotEmptyList() {
        assertTrue(humanizeMemoryError("网关 HTTP 503 memory_not_configured")
            .contains("记忆库路径"))
    }

    @Test
    fun missingOrMalformedListIsNotReportedAsWriteDisabled() {
        assertTrue(humanizeMemoryError("memory_list_invalid")
            .contains("格式不兼容"))
    }

    @Test
    fun deniedPermissionsUseReadableText() {
        assertTrue(humanizeMemoryError("网关 HTTP 403")
            .contains("拒绝访问"))
    }
}
