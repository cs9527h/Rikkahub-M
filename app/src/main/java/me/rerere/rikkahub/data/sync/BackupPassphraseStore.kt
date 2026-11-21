package me.rerere.rikkahub.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Explicitly opt-in local escrow for unattended backups. The wrapping key stays in
 * Android Keystore; this file stays in noBackupFilesDir, never in any exported archive.
 * Restoring on another device still requires the user's original recovery passphrase.
 */
class BackupPassphraseStore(
    context: Context,
    private val keyAlias: String = "rikkahub-backup-passphrase-v1",
) {
    private val file = File(context.noBackupFilesDir, "backup-secret/passphrase.bin")
    private val mutex = Mutex()

    // AtomicFile can recover from its legacy .bak when the base file is missing.
    suspend fun isConfigured(): Boolean = withContext(Dispatchers.IO) {
        file.isFile || File(file.parentFile, file.name + ".bak").isFile
    }

    suspend fun save(passphrase: CharArray) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(passphrase.size in 8..1024) { "自动备份口令需为 8 至 1024 位" }
            val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(passphrase))
            val plain = ByteArray(encoded.remaining()).also(encoded::get)
            if (encoded.hasArray()) encoded.array().fill(0)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, loadKey(create = true))
                cipher.updateAAD(MAGIC)
                check(cipher.iv.size == IV_BYTES) { "本机加密参数不可用" }
                val encrypted = cipher.doFinal(plain)
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "无法创建本机密钥目录" }
                val atomic = AtomicFile(file)
                val output = atomic.startWrite()
                try {
                    output.write(MAGIC)
                    output.write(cipher.iv)
                    output.write(encrypted)
                    atomic.finishWrite(output)
                } catch (error: Throwable) {
                    atomic.failWrite(output)
                    throw error
                }
            } finally {
                plain.fill(0)
            }
        }
    }

    suspend fun read(): CharArray? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val backup = File(file.parentFile, file.name + ".bak")
            val readable = when {
                file.isFile -> file
                backup.isFile -> backup
                else -> return@withLock null
            }
            require(readable.length() in (MAGIC.size + IV_BYTES + 16).toLong()..MAX_FILE_BYTES) {
                "本机备份口令文件无效，请重新设置"
            }
            // AtomicFile restores the legacy .bak to the base file while reading it.
            val encrypted = AtomicFile(file).readFully()
            require(encrypted.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "本机备份口令版本无效，请重新设置" }
            val iv = encrypted.copyOfRange(MAGIC.size, MAGIC.size + IV_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, loadKey(create = false), GCMParameterSpec(128, iv))
            cipher.updateAAD(MAGIC)
            val plain = cipher.doFinal(encrypted, MAGIC.size + IV_BYTES, encrypted.size - MAGIC.size - IV_BYTES)
            try {
                val decoded = Charsets.UTF_8.decode(ByteBuffer.wrap(plain))
                val result = CharArray(decoded.remaining()).also(decoded::get)
                if (decoded.hasArray()) decoded.array().fill('\u0000')
                result
            } finally {
                plain.fill(0)
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { AtomicFile(file).delete() }
    }

    private fun loadKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        check(create) { "本机备份密钥不可用，请重新设置口令" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()
    }

    companion object {
        private val MAGIC = "RKMKEY01".toByteArray(Charsets.US_ASCII)
        private const val IV_BYTES = 12
        private const val MAX_FILE_BYTES = 8L * 1024
    }
}
