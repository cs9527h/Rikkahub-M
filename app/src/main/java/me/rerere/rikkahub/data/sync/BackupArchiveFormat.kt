package me.rerere.rikkahub.data.sync

/** Shared by remote listings; never treat nested paths as archive basenames. */
object BackupArchiveFormat {
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
