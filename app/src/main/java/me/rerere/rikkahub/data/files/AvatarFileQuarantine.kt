package me.rerere.rikkahub.data.files

import java.io.File
import java.util.UUID

/**
 * Retires proven avatar files with one rename; preparation and garbage collection are never done
 * under the settings lock. The private no-backup namespace is not a usable attachment/avatar path.
 * A failed/cross-filesystem rename leaves both the source and its index alone.
 */
internal class AvatarFileQuarantine(
    private val filesRoot: File,
    private val noBackupRoot: File
) {
    internal class Move private constructor(private val source: File, private val target: File) {
        var moved: Boolean = false
            private set

        /** The only filesystem syscall at the reference-fence linearization point. */
        fun retire(): Boolean = source.renameTo(target).also { moved = it }

        fun unlink(): Boolean = moved && target.delete()

        companion object {
            internal fun prepare(source: File, target: File): Move = Move(source, target)
        }
    }

    fun prepare(relativePath: String): Move? {
        val parts = relativePath.split('/')
        if (parts.size != 2 || parts[0] !in setOf("upload", "avatars") ||
            parts[1].isBlank() || parts[1] == "." || parts[1] == ".." ||
            relativePath.any { it == '\\' || it.isISOControl() }
        ) {
            return null
        }
        val root = filesRoot.canonicalFile
        val source = File(root, relativePath)
        if (source.canonicalFile != source.absoluteFile || !source.isFile ||
            !source.path.startsWith(root.path + File.separator)
        ) {
            return null
        }
        val directory = directory() ?: return null
        val target = File(directory, "${UUID.randomUUID()}.retired")
        if (target.canonicalFile != target.absoluteFile || target.exists()) return null
        return Move.prepare(source, target)
    }

    /** Only direct, recognisable retirement files; never recurse or follow symlinks. */
    fun sweep() {
        val directory = directory() ?: return
        directory.listFiles()?.forEach { file ->
            if (file.name.matches(RETIRED_NAME) && file.canonicalFile == file.absoluteFile && file.isFile) {
                file.delete()
            }
        }
    }

    private fun directory(): File? {
        val root = noBackupRoot.canonicalFile
        val directory = File(root, DIRECTORY)
        if (directory.canonicalFile != directory.absoluteFile ||
            !directory.path.startsWith(root.path + File.separator)
        ) {
            return null
        }
        if (!directory.isDirectory && !directory.mkdirs()) return null
        return directory
    }

    companion object {
        internal const val DIRECTORY = "avatar-quarantine"
        private val RETIRED_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.retired")
    }
}
