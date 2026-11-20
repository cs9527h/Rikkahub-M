package me.rerere.rikkahub.mate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MatePairingLinkTest {
    private val code = "abcdefghijklmnopqrstuvwxyz123456"

    @Test fun acceptsGeneratedOneTimeInvitation() {
        val url = MateInviteCode.url("https://example.com", code)
        assertEquals("https://example.com" to code, parseMatePairingLink(url))
    }

    @Test fun rejectsUnexpectedDomainsAndMissingFields() {
        assertThrows(IllegalArgumentException::class.java) {
            parseMatePairingLink("https://example.com/?server=https://example.com&code=$code")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseMatePairingLink("mate://pair?server=https%3A%2F%2Fexample.com")
        }
    }

    @Test fun rejectsUntrustedHttpAndMalformedCodes() {
        assertThrows(IllegalArgumentException::class.java) {
            parseMatePairingLink("mate://pair?server=http%3A%2F%2Fexample.com&code=$code")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseMatePairingLink("mate://pair?server=https%3A%2F%2Fexample.com&code=short")
        }
    }
}
