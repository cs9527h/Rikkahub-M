package me.rerere.rikkahub.data.sync

/** A remote automatic backup, identified by its strict generated file name. */
internal data class BackupVersion(val name: String, val timestamp: Long)

/**
 * Opt-in retention for AUTOMATIC archives only. Manual archives and other kinds are never
 * selected, so enabling this cannot delete a user's hand-made full backup. keep = 0 disables.
 */
internal object BackupRetention {
    fun selectExpired(versions: List<BackupVersion>, kind: BackupKind, keep: Int): List<BackupVersion> {
        require(kind != BackupKind.FULL) { "整包备份不参与自动清理" }
        require(keep >= 0) { "保留份数不能为负" }
        if (keep == 0) return emptyList()
        val candidates = versions.filter { matches(it.name, kind) }
        val newestFirst = candidates.sortedWith(
            compareByDescending<BackupVersion> { it.timestamp }.thenByDescending { it.name },
        )
        return newestFirst.drop(keep)
    }

    fun matches(name: String, kind: BackupKind): Boolean {
        if (kind == BackupKind.FULL) return false
        return Regex(
            "^backup_${kind.wireName}_\\d{8}_\\d{6}(_\\d+)?\\.(zip|rkmbak)$",
            RegexOption.IGNORE_CASE,
        ).matches(name)
    }
}
