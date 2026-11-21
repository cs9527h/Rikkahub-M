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

    fun restoreUri(value: String, filesDir: File, folders: Set<String>): String {
        val relative = relativePath(value, folders) ?: return value
        val root = filesDir.canonicalFile
        val target = File(root, relative).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) { "备份文件引用超出托管目录" }
        return target.toURI().toASCIIString()
    }
}
