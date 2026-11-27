package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.files.CatalogFile
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.ManagedFileType

internal const val STORAGE_AREA_CHAT = "chat"
internal const val STORAGE_AREA_AVATARS = "avatars"

/** Active legacy upload avatars are separated immediately, even if migration is still pending. */
internal fun storageAreaFiles(
    files: List<CatalogFile>,
    area: String,
    avatarPaths: Set<String>,
): List<CatalogFile> = files.filter { file ->
    val avatar = file.entity.folder == FileFolders.AVATARS || file.entity.relativePath in avatarPaths
    avatar == (area == STORAGE_AREA_AVATARS)
}

/** Avatar ownership comes from profile settings, not from invented chat references. */
internal fun filterAvatarFiles(
    files: List<CatalogFile>,
    owners: Map<String, Set<String>>,
    type: ManagedFileType?,
    ownerKey: String,
    query: String,
    sort: FileManagerSort,
): List<CatalogFile> {
    val owned = files.filter { file ->
        val current = owners[file.entity.relativePath].orEmpty()
        when (ownerKey) {
            "all" -> true
            "user" -> "user" in current
            "shared" -> current.size > 1
            "unassigned" -> current.isEmpty()
            else -> ownerKey in current
        }
    }
    return filterManagedFiles(owned, type, me.rerere.rikkahub.data.files.CatalogAssistantFilter.All, query, sort)
}
