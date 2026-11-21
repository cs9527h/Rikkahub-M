package me.rerere.rikkahub.data.model

/** One path contract shared by the native repository and portable memory backups. */
object MemoryPaths {
    private const val MAX_PATH_LENGTH = 128

    fun normalize(raw: String): String? {
        val path = raw.trim().let { if (it.startsWith("/")) it else "/$it" }
        if (path.length > MAX_PATH_LENGTH || !path.endsWith(".md")) return null
        if (path.any { it.isISOControl() || it == '\\' }) return null
        val segments = path.removePrefix("/").split("/")
        if (segments.any { it.isBlank() || it != it.trim() || it.startsWith(".") }) return null
        return path
    }
}
