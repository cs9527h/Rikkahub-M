package me.rerere.rikkahub.data.sync

internal class MissingBackupPassphraseException :
    IllegalStateException("本机没有这份备份的解密密钥：请先导入恢复密钥，或在恢复旧文件时输入原口令")

/** Never invokes an automatic upload without a key, and clears the owned key on every exit. */
internal object EncryptedBackupSession {
    suspend fun <T> run(
        readPassphrase: suspend () -> CharArray?,
        backup: suspend (CharArray) -> T,
    ): T {
        val passphrase = readPassphrase() ?: throw MissingBackupPassphraseException()
        try {
            if (passphrase.isEmpty()) throw MissingBackupPassphraseException()
            return backup(passphrase)
        } finally {
            passphrase.fill('\u0000')
        }
    }
}
