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
        // 文件名里的 yyyyMMdd_HHmmss 是生成时刻，定宽零填充，字典序就是时间序。
        // 远端可能拿不到 Last-Modified（S3 缺字段、WebDAV 解析失败会退成 EPOCH），
        // 只按服务端时间排序会把最新的那份当成最旧的删掉，所以名字优先、时间兜底。
        val newestFirst = candidates.sortedWith(
            compareByDescending<BackupVersion> { nameStamp(it.name) }
                .thenByDescending { it.timestamp }
                .thenByDescending { it.name },
        )
        return newestFirst.drop(keep)
    }

    /** 文件名里的生成时间（yyyyMMdd_HHmmss），取不到时返回空串。 */
    private fun nameStamp(name: String): String =
        NAME_STAMP.find(name)?.let { it.groupValues[1] + it.groupValues[2] } ?: ""

    private val NAME_STAMP = Regex("_(\\d{8})_(\\d{6})(?:_\\d+)?\\.(?:zip|rkmbak)$", RegexOption.IGNORE_CASE)

    fun matches(name: String, kind: BackupKind): Boolean {
        if (kind == BackupKind.FULL) return false
        return Regex(
            "^backup_${kind.wireName}_\\d{8}_\\d{6}(_\\d+)?\\.(zip|rkmbak)$",
            RegexOption.IGNORE_CASE,
        ).matches(name)
    }
}
