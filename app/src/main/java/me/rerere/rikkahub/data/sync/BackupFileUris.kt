package me.rerere.rikkahub.data.sync

import java.io.File
import java.net.URI

/** Maps managed file references only, never rewrites prompt/persona/message text. */
internal object BackupFileUris {
    fun relativePath(value: String, folders: Set<String>): String? = runCatching {
        val uri = URI(value)
        if (!uri.scheme.equals("file", ignoreCase = true) || !uri.rawAuthority.isNullOrEmpty() || uri.query != null ||
            uri.fragment != null) {
            return null
        }
        val path = uri.path ?: return null
        val marker = "/files/"
        val index = path.lastIndexOf(marker)
        if (index < 0) return null
        val relative = path.substring(index + marker.length)
        val parts = relative.split('/')
        if (parts.size < 2 || parts.first() !in folders || parts.any { it.isBlank() || it == "." || it == ".." } ||
            relative.any { it == '\\' || it.isISOControl() }
        ) {
            return null
        }
        relative
    }.getOrNull()

    /**
     * 把托管文件引用改写到目标应用的 files 根目录（用于「兼容原版」导出）。
     *
     * 原版 RikkaHub 恢复时不做任何 URL 重写，它的包名是 me.rerere.rikkahub，
     * 所以导出的包必须带上 /data/user/0/me.rerere.rikkahub/files/... 这样的绝对路径，
     * 原版才能找到同一份附件（头像是 Avatar.Image(url)，找不到就退回默认头像）。
     */
    fun relocateTo(value: String, targetFilesDir: String, folders: Set<String>): String {
        val relative = relativePath(value, folders) ?: return value
        val base = targetFilesDir.trimEnd('/')
        return "file://" + base + "/" + relative
    }

    fun restoreUri(value: String, filesDir: File, folders: Set<String>): String {
        val relative = relativePath(value, folders) ?: return value
        val root = filesDir.canonicalFile
        val target = File(root, relative).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) { "备份文件引用超出托管目录" }
        return target.toURI().toASCIIString()
    }
}
