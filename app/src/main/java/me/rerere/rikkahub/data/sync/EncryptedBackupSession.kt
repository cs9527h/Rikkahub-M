package me.rerere.rikkahub.data.sync

internal class MissingBackupPassphraseException : IllegalStateException("请先设置自动备份的本机加密口令")

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
