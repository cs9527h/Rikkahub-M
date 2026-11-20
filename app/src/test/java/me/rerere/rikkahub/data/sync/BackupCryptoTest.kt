package me.rerere.rikkahub.data.sync

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {
    private fun payload(size: Int = 4096): File {
        val file = File.createTempFile("payload", ".zip")
        file.writeBytes(ByteArray(size) { (it * 31 % 251).toByte() })
        return file
    }

    @Test
    fun roundTripKeepsContentAndReportsEncrypted() {
        val plain = payload()
        val encrypted = File.createTempFile("backup", ".${BackupCrypto.EXTENSION}")
        val restored = File.createTempFile("restored", ".zip")
        try {
            assertFalse(BackupCrypto.isEncrypted(plain))
            BackupCrypto.encrypt(plain, encrypted, "correct horse battery staple".toCharArray())
            assertTrue(BackupCrypto.isEncrypted(encrypted))
            // 密文不得泄露明文内容
            assertFalse(encrypted.readBytes().contentEquals(plain.readBytes()))
            BackupCrypto.decrypt(encrypted, restored, "correct horse battery staple".toCharArray())
            assertArrayEquals(plain.readBytes(), restored.readBytes())
        } finally {
            plain.delete(); encrypted.delete(); restored.delete()
        }
    }

    @Test
    fun wrongPassphraseFailsAndLeavesNoPartialOutput() {
        val plain = payload()
        val encrypted = File.createTempFile("backup", ".${BackupCrypto.EXTENSION}")
        val restored = File.createTempFile("restored", ".zip")
        try {
            BackupCrypto.encrypt(plain, encrypted, "right".toCharArray())
            val failure = runCatching {
                BackupCrypto.decrypt(encrypted, restored, "wrong".toCharArray())
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertFalse(restored.exists())
        } finally {
            plain.delete(); encrypted.delete(); restored.delete()
        }
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val plain = payload()
        val encrypted = File.createTempFile("backup", ".${BackupCrypto.EXTENSION}")
        val restored = File.createTempFile("restored", ".zip")
        try {
            BackupCrypto.encrypt(plain, encrypted, "pw".toCharArray())
            val bytes = encrypted.readBytes()
            bytes[bytes.size - 20] = (bytes[bytes.size - 20].toInt() xor 0x01).toByte()
            encrypted.writeBytes(bytes)
            val failure = runCatching {
                BackupCrypto.decrypt(encrypted, restored, "pw".toCharArray())
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertFalse(restored.exists())
        } finally {
            plain.delete(); encrypted.delete(); restored.delete()
        }
    }

    @Test
    fun encryptingInPlaceCannotDestroySource() {
        val plain = payload()
        val original = plain.readBytes()
        try {
            assertTrue(runCatching { BackupCrypto.encrypt(plain, plain, "password".toCharArray()) }.isFailure)
            assertArrayEquals(original, plain.readBytes())
        } finally {
            plain.delete()
        }
    }

    @Test
    fun decryptingInPlaceCannotDestroyArchive() {
        val plain = payload()
        val encrypted = File.createTempFile("backup", ".rkmbak")
        try {
            BackupCrypto.encrypt(plain, encrypted, "password".toCharArray())
            val original = encrypted.readBytes()
            assertTrue(runCatching { BackupCrypto.decrypt(encrypted, encrypted, "password".toCharArray()) }.isFailure)
            assertArrayEquals(original, encrypted.readBytes())
        } finally {
            plain.delete(); encrypted.delete()
        }
    }

    @Test
    fun freshSaltAndIvProduceDifferentArchivesForSameInput() {
        val plain = payload(0)
        val first = File.createTempFile("first", ".rkmbak")
        val second = File.createTempFile("second", ".rkmbak")
        val restored = File.createTempFile("restored", ".zip")
        try {
            BackupCrypto.encrypt(plain, first, "password".toCharArray())
            BackupCrypto.encrypt(plain, second, "password".toCharArray())
            assertFalse(first.readBytes().contentEquals(second.readBytes()))
            BackupCrypto.decrypt(first, restored, "password".toCharArray())
            assertTrue(restored.length() == 0L)
        } finally {
            plain.delete(); first.delete(); second.delete(); restored.delete()
        }
    }

    @Test
    fun unsupportedHeaderIsRejected() {
        val bogus = File.createTempFile("bogus", ".rkmbak")
        val restored = File.createTempFile("restored", ".zip")
        try {
            bogus.writeBytes(ByteArray(64))
            assertFalse(BackupCrypto.isEncrypted(bogus))
            val failure = runCatching {
                BackupCrypto.decrypt(bogus, restored, "pw".toCharArray())
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        } finally {
            bogus.delete(); restored.delete()
        }
    }
}
