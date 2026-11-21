package me.rerere.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitEntitlementTest {
    private val grokZeroQuota = """
        {"code":"Too many requests","error":"Too many requests. Requests per Minute (actual/limit): 0/0"}
    """.trimIndent()

    @Test
    fun `zero quota on 429 is reported as an entitlement problem`() {
        val message = rateLimitEntitlementMessage(429, grokZeroQuota)
        assertTrue(message!!.contains("RPM 0/0"))
        assertTrue(message.contains("API Key"))
    }

    @Test
    fun `ordinary rate limit is left to the normal error path`() {
        assertNull(rateLimitEntitlementMessage(429, """{"error":"Too many requests. Requests per Minute (actual/limit): 12/100"}"""))
    }

    @Test
    fun `non 429 responses are not reinterpreted`() {
        assertNull(rateLimitEntitlementMessage(500, grokZeroQuota))
        assertNull(rateLimitEntitlementMessage(429, null))
    }

    @Test
    fun `parsed error keeps the actionable text`() {
        val message = rateLimitEntitlementMessage(429, grokZeroQuota)!!
        val exception = HttpException(message)
        assertEquals(message, exception.message)
    }
}
