package me.rerere.rikkahub.data.sync

/** Shared by remote listings; never treat nested paths as archive basenames. */
object BackupArchiveFormat {
    /**
     * 校验导出时填写的口令：留空表示走本机密钥；填写则至少 6 位且两次一致。
     * 含凭据的备份必须加密。
     */
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
        require(passphrase.length >= 6) { "请设置至少 6 位备份口令" }
        require(passphrase == confirmation) { "两次输入的备份口令不一致" }
        return passphrase.toCharArray()
    }

    fun isBackupName(name: String): Boolean =
        name.startsWith("backup_") && '/' !in name && '\\' !in name &&
            (name.endsWith(".zip", ignoreCase = true) || name.endsWith(".${BackupCrypto.EXTENSION}", ignoreCase = true))

    /** Automatic archives encode their data policy in the file name; manual ones stay full. */
    fun kindOfName(name: String): BackupKind? = when {
        !isBackupName(name) -> null
        name.startsWith("backup_settings_") -> BackupKind.SETTINGS
        name.startsWith("backup_chats_") -> BackupKind.CHATS
        else -> BackupKind.FULL
    }
}
