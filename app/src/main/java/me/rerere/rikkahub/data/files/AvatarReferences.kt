package me.rerere.rikkahub.data.files

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Avatar
import java.io.File
import java.net.URI

/** Pure settings classification, available even before avatar migration has started. */
object AvatarReferences {
    fun relativePath(value: String): String? = runCatching {
        val uri = URI(value)
        if (!uri.scheme.equals("file", true) || !uri.rawAuthority.isNullOrEmpty() ||
            uri.query != null || uri.fragment != null
        ) {
            return null
        }
        val path = uri.path ?: return null
        val marker = "/files/"
        val index = path.lastIndexOf(marker)
        if (index < 0) return null
        val relative = path.substring(index + marker.length)
        val parts = relative.split('/')
        if (parts.size != 2 || parts[0] !in setOf("upload", "avatars") ||
            parts.any { it.isBlank() || it == "." || it == ".." } ||
            relative.any { it == '\\' || it.isISOControl() }
        ) {
            return null
        }
        relative
    }.getOrNull()

    fun localAvatarOwners(settings: Settings, filesDir: File? = null): Map<String, Set<String>> {
        val result = linkedMapOf<String, MutableSet<String>>()
        fun add(avatar: Avatar, owner: String) {
            val url = (avatar as? Avatar.Image)?.url ?: return
            val path = relativePath(url) ?: return
            if (filesDir != null &&
                normalizeLocalFileReference(url) != normalizePrivateFilePath(File(filesDir, path).absolutePath)) {
                return
            }
            result.getOrPut(path) { linkedSetOf() }.add(owner)
        }
        add(settings.displaySetting.userAvatar, "user")
        settings.assistants.forEach { add(it.avatar, "assistant:${it.id}") }
        return result.mapValues { it.value.toSet() }
    }

    fun localAvatarPaths(settings: Settings, filesDir: File? = null): Set<String> = localAvatarOwners(
        settings,
        filesDir
    ).keys

    /** Inspect existing JSON, including preset/tool/nested parts, object keys and plain text. */
    fun containsReference(element: JsonElement, relativePath: String): Boolean =
        LocalFileReferenceMatcher(relativePath).contains(element)

    fun settingsContainReference(settings: Settings, json: Json, relativePath: String): Boolean =
        settingsContainFileReference(settings, json, relativePath)
}

/** Builds tiny target needles once; never decodes or copies an entire large prompt. */
internal class LocalFileReferenceMatcher(private val relativePath: String) {
    private val encoded = URI(null, null, "/$relativePath", null).toASCIIString().removePrefix("/")
    private val needles = setOf(
        relativePath,
        relativePath.replace("/", "%2F"),
        encoded,
        encoded.replace("/", "%2F")
    )

    fun matches(value: String): Boolean = needles.any { value.contains(it, ignoreCase = true) } ||
        // Exact file URIs may encode unreserved characters differently from our canonical needle.
        (value.startsWith("file:", ignoreCase = true) && AvatarReferences.relativePath(value) == relativePath)

    fun contains(element: JsonElement): Boolean = contains(element, 0)

    private fun contains(element: JsonElement, depth: Int): Boolean {
        // Unknown/overdeep structures must retain files instead of overflowing or returning false.
        if (depth > 128) return true
        return when (element) {
            is JsonObject -> element.any { (key, value) -> matches(key) || contains(value, depth + 1) }
            is JsonArray -> element.any { contains(it, depth + 1) }
            is JsonPrimitive -> matches(element.content)
        }
    }
}
