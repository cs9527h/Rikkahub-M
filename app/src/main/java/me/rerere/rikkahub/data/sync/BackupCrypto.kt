package me.rerere.rikkahub.data.sync

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份归档的客户端加密格式，供设置/聊天备份共用。
 *
 * 文件布局：magic(8) | 迭代次数(4, big-endian) | 盐(16) | IV(12) | AES-256-GCM 密文(含 128-bit tag)
 * 密钥由用户口令经 PBKDF2-HMAC-SHA256 派生，盐和 IV 每次随机；口令本身从不写入文件。
 * 解密使用 CipherInputStream，GCM 校验失败会抛出异常，调用方必须按"备份损坏或口令错误"处理。
 */
object BackupCrypto {
    const val EXTENSION = "rkmbak"

    private const val MAGIC = "RKMENC01"
    private const val ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val HEADER_BYTES = 8 + 4 + SALT_BYTES + IV_BYTES

    fun isEncrypted(file: File): Boolean = runCatching {
        FileInputStream(file).use { input ->
            val header = ByteArray(8)
            if (input.read(header) != 8) return false
            String(header, Charsets.US_ASCII) == MAGIC
        }
    }.getOrDefault(false)

    fun encrypt(input: File, output: File, passphrase: CharArray) {
        require(passphrase.isNotEmpty()) { "备份口令不能为空" }
        require(input.canonicalFile != output.canonicalFile) { "备份输入与输出不能是同一文件" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt, ITERATIONS)
        try {
            FileOutputStream(output).use { fileOut ->
                fileOut.write(MAGIC.toByteArray(Charsets.US_ASCII))
                fileOut.write(intBytes(ITERATIONS))
                fileOut.write(salt)
                fileOut.write(iv)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                CipherOutputStream(fileOut, cipher).use { cipherOut ->
                    FileInputStream(input).use { it.copyTo(cipherOut) }
                }
            }
        } catch (e: Throwable) {
            output.delete()
            throw e
        }
    }

    /**
     * 从任意输入流解密到文件：界面从 SAF 拿到的就是 InputStream，
     * 先整包复制到 cache 再解密等于多读写一遍 200MB。
     */
    fun decrypt(input: java.io.InputStream, output: File, passphrase: CharArray) {
        require(passphrase.isNotEmpty()) { "备份口令不能为空" }
        val magic = ByteArray(8)
        require(input.read(magic) == 8 && String(magic, Charsets.US_ASCII) == MAGIC) {
            "不是 Rikkahub-M 加密备份文件"
        }
        val iterations = readInt(input)
        require(iterations in 50_000..2_000_000) { "备份迭代次数异常" }
        val salt = ByteArray(SALT_BYTES).also { require(input.read(it) == SALT_BYTES) }
        val iv = ByteArray(IV_BYTES).also { require(input.read(it) == IV_BYTES) }
        val key = deriveKey(passphrase, salt, iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            FileOutputStream(output).use { fileOut ->
                CipherInputStream(input, cipher).use { it.copyTo(fileOut) }
            }
        } catch (e: Throwable) {
            output.delete()
            throw IllegalStateException("备份口令错误或文件已损坏", e)
        }
    }

    fun decrypt(input: File, output: File, passphrase: CharArray) {
        require(passphrase.isNotEmpty()) { "备份口令不能为空" }
        require(input.canonicalFile != output.canonicalFile) { "备份输入与输出不能是同一文件" }
        FileInputStream(input).use { fileIn ->
            val magic = ByteArray(8)
            require(fileIn.read(magic) == 8 && String(magic, Charsets.US_ASCII) == MAGIC) {
                "不是 Rikkahub-M 加密备份文件"
            }
            val iterations = readInt(fileIn)
            require(iterations in 50_000..2_000_000) { "备份迭代次数异常" }
            val salt = ByteArray(SALT_BYTES).also { require(fileIn.read(it) == SALT_BYTES) }
            val iv = ByteArray(IV_BYTES).also { require(fileIn.read(it) == IV_BYTES) }
            val key = deriveKey(passphrase, salt, iterations)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                FileOutputStream(output).use { fileOut ->
                    CipherInputStream(fileIn, cipher).use { it.copyTo(fileOut) }
                }
            } catch (e: Throwable) {
                output.delete()
                throw IllegalStateException("备份口令错误或文件已损坏", e)
            }
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun intBytes(value: Int): ByteArray = ByteArray(4) { index ->
        ((value shr (24 - index * 8)) and 0xFF).toByte()
    }

    private fun readInt(input: java.io.InputStream): Int {
        val bytes = ByteArray(4).also { require(input.read(it) == 4) { "加密备份头不完整" } }
        return bytes.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
    }
}
