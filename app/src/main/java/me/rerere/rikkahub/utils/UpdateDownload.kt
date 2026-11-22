package me.rerere.rikkahub.utils

import java.io.File

/**
 * 更新包下载的单一状态源：界面进度、通知栏、取消与重试都读同一份状态，
 * 避免多处各记一套导致百分比对不上或重复下载。
 */
sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState

    /** 已开始，尚未收到第一个字节（连接、跟随重定向）。 */
    data class Preparing(val version: String) : UpdateDownloadState

    data class Running(
        val version: String,
        val read: Long,
        val total: Long,
    ) : UpdateDownloadState {
        /** 服务端没给 Content-Length 时返回 null，界面改用不确定进度。 */
        val fraction: Float?
            get() = if (total > 0) (read.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
    }

    /** 下载完成，正在校验 SHA-256、包名、版本号与签名。 */
    data class Verifying(val version: String, val source: String = "") : UpdateDownloadState

    data class Ready(val version: String, val apk: File) : UpdateDownloadState

    /** pageUrl 用于「浏览器下载」入口，保证应用内失败时用户仍有退路。 */
    data class Failed(
        val version: String,
        val message: String,
        val pageUrl: String? = null,
    ) : UpdateDownloadState
}

/** 1.2 MB / 812 KB；只用于界面显示，不参与任何校验。 */
fun formatByteSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes B" else "%.1f %s".format(value, units[unit])
}
