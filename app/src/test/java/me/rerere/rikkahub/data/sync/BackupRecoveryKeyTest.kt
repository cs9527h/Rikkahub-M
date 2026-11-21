package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupRecoveryKeyTest {

    @Test
    fun `generated key is url safe base64 of 32 bytes`() {
        repeat(20) {
            val key = BackupRecoveryKey.generate()
            assertEquals(43, key.size)
            assert(key.all { it.isLetterOrDigit() || it == '-' || it == '_' })
            key.fill('\u0000')
        }
    }

    @Test
    fun `generated keys are distinct`() {
        val first = BackupRecoveryKey.generate()
        val second = BackupRecoveryKey.generate()
        assertNotEquals(String(first), String(second))
        first.fill('\u0000')
        second.fill('\u0000')
    }

    @Test
    fun `encode then decode round trip keeps the secret`() {
        val secret = BackupRecoveryKey.generate()
        val text = BackupRecoveryKey.encode(secret)
        val decoded = BackupRecoveryKey.decode(text)
        assertEquals(String(secret), String(decoded))
        secret.fill('\u0000')
        decoded.fill('\u0000')
    }

    @Test
    fun `decode tolerates crlf and surrounding blank lines`() {
        val secret = BackupRecoveryKey.generate()
        val text = "\r\n" + BackupRecoveryKey.HEADER + "\r\n" + String(secret) + "\r\n\r\n"
        val decoded = BackupRecoveryKey.decode(text)
        assertEquals(String(secret), String(decoded))
        secret.fill('\u0000')
        decoded.fill('\u0000')
    }

    @Test
    fun `decode rejects foreign file`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupRecoveryKey.decode("some other app backup key\nAAAA")
        }
    }

    @Test
    fun `decode rejects corrupted body`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupRecoveryKey.decode(BackupRecoveryKey.HEADER + "\nnot base64 !!!\n")
        }
    }

    @Test
    fun `decode rejects wrong key size`() {
        val short = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
        assertThrows(IllegalArgumentException::class.java) {
            BackupRecoveryKey.decode(BackupRecoveryKey.HEADER + "\n" + short + "\n")
        }
    }
}
