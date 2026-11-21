package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

class EncryptedBackupSessionTest {
    @Test fun missingKeyNeverStartsUpload() = runBlocking {
        var called = false
        val failure = runCatching {
            EncryptedBackupSession.run({ null }) { called = true }
        }.exceptionOrNull()
        assertTrue(failure is MissingBackupPassphraseException)
        assertFalse(called)
    }

    @Test fun emptyKeyNeverStartsUpload() = runBlocking {
        var called = false
        val failure = runCatching {
            EncryptedBackupSession.run({ charArrayOf() }) { called = true }
        }.exceptionOrNull()
        assertTrue(failure is MissingBackupPassphraseException)
        assertFalse(called)
    }

    @Test fun keyLoadFailureNeverFallsBackToPlaintext() = runBlocking {
        var called = false
        val failure = runCatching {
            EncryptedBackupSession.run({ error("synthetic lost keystore key") }) { called = true }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(called)
    }

    @Test fun successfulBackupClearsOwnedKeyButNotTheStoredSource() = runBlocking {
        val source = "synthetic recovery password".toCharArray()
        val owned = source.copyOf()
        EncryptedBackupSession.run({ owned }) { key -> assertArrayEquals(source, key) }
        assertTrue(owned.all { it == '\u0000' })
        assertTrue(source.any { it != '\u0000' })
    }

    @Test fun transportFailureStillClearsOwnedKey() = runBlocking {
        val owned = "synthetic password".toCharArray()
        val expected = IllegalStateException("synthetic connection failure")
        val failure = runCatching {
            EncryptedBackupSession.run({ owned }) { throw expected }
        }.exceptionOrNull()
        assertTrue(failure === expected)
        assertTrue(owned.all { it == '\u0000' })
    }

    @Test fun cancellationPropagatesAndClearsOwnedKey() = runBlocking {
        val owned = "synthetic password".toCharArray()
        val expected = CancellationException("synthetic cancellation")
        val failure = runCatching {
            EncryptedBackupSession.run({ owned }) { throw expected }
        }.exceptionOrNull()
        assertTrue(failure === expected)
        assertTrue(owned.all { it == '\u0000' })
    }

    @Test fun automaticSessionProducesARealDecryptableEncryptedArchive() = runBlocking {
        val plain = File.createTempFile("auto-plain-", ".zip")
        val encrypted = File.createTempFile("auto-backup-", ".rkmbak")
        val restored = File.createTempFile("auto-restore-", ".zip")
        val original = "synthetic password for restore".toCharArray()
        val owned = original.copyOf()
        try {
            plain.writeText("synthetic settings and memory fixture")
            EncryptedBackupSession.run({ owned }) { key -> BackupCrypto.encrypt(plain, encrypted, key) }
            assertTrue(BackupCrypto.isEncrypted(encrypted))
            assertTrue(owned.all { it == '\u0000' })
            BackupCrypto.decrypt(encrypted, restored, original)
            assertArrayEquals(plain.readBytes(), restored.readBytes())
        } finally {
            plain.delete()
            encrypted.delete()
            restored.delete()
            original.fill('\u0000')
        }
    }
}
