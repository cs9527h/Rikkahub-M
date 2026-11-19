package me.rerere.rikkahub.mate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MateGatewayAddressTest {
    @Test fun acceptsOnlyHttpsRootUrls() {
        assertEquals("https://example.com", MateGatewayAddress.normalize(" https://example.com/ "))
        assertEquals("https://example.com:8443", MateGatewayAddress.normalize("https://example.com:8443"))
    }

    @Test fun rejectsHttpAndEmbeddedSecrets() {
        for (value in listOf(
            "http://example.com", "https://user:password@example.com",
            "https://example.com/path", "https://example.com/?code=secret",
            "https://example.com/#fragment", "not-a-url"
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                MateGatewayAddress.normalize(value)
            }
        }
    }
}
