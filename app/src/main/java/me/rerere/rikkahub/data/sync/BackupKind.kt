package me.rerere.rikkahub.data.sync

/** One archive engine, with distinct data policies and remote version directories. */
enum class BackupKind(val directory: String, val wireName: String) {
    FULL("", "full"),
    SETTINGS("settings", "settings"),
    CHATS("chats", "chats");

    companion object {
        fun fromWireName(value: String): BackupKind = entries.firstOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("不支持的备份类型")
    }
}

internal data class PreparedBackup(
    val file: java.io.File,
    val fingerprint: String,
    val kind: BackupKind,
)
