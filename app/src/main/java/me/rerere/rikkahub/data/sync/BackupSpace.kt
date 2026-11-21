package me.rerere.rikkahub.data.sync

/** Pure free-space decision for restore staging; unknown/zero availability is caller-handled. */
internal object BackupSpace {
    const val MARGIN_BYTES = 32L * 1024 * 1024

    fun fits(needed: Long, available: Long, margin: Long = MARGIN_BYTES): Boolean {
        require(needed >= 0 && available >= 0 && margin >= 0) { "空间数值无效" }
        require(needed <= Long.MAX_VALUE - margin) { "空间需求无效" }
        return available >= needed + margin
    }

    fun requiredBytes(needed: Long, margin: Long = MARGIN_BYTES): Long {
        require(needed >= 0 && margin >= 0 && needed <= Long.MAX_VALUE - margin) { "空间数值无效" }
        return needed + margin
    }
}
