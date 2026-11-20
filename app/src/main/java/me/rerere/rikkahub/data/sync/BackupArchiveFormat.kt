package me.rerere.rikkahub.data.sync

/** Shared by remote listings; never treat nested paths as archive basenames. */
object BackupArchiveFormat {
    fun isBackupName(name: String): Boolean =
        name.startsWith("backup_") && '/' !in name && '\\' !in name &&
            (name.endsWith(".zip", ignoreCase = true) || name.endsWith(".${BackupCrypto.EXTENSION}", ignoreCase = true))

    fun exportPassphrase(
        encrypt: Boolean,
        passphrase: String,
        confirmation: String,
        includeCredentials: Boolean,
    ): CharArray? {
        if (!encrypt) {
            require(!includeCredentials) { "包含凭据的备份必须加密" }
            return null
        }
        require(passphrase.length >= 8) { "请设置至少 8 位备份口令" }
        require(passphrase == confirmation) { "两次输入的备份口令不一致" }
        return passphrase.toCharArray()
    }
}
