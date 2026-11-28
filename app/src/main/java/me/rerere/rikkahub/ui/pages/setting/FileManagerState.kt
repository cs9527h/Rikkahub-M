package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.files.CatalogAssistantFilter
import me.rerere.rikkahub.data.files.CatalogFile
import me.rerere.rikkahub.data.files.ManagedFileType

internal data class FileDeletionSummary(val succeeded: Int, val failed: Int) {
    val allDeleted: Boolean get() = failed == 0
}

/** Page-owned business state. Storage IO stays behind the injected, reference-aware deletion port. */
internal class FileManagerState {
    var storageArea by mutableStateOf(STORAGE_AREA_CHAT)
        private set
    var query by mutableStateOf("")
    var selectedTypeName by mutableStateOf<String?>(null)
    var ownerKey by mutableStateOf("all")
    var sort by mutableStateOf(FileManagerSort.NEWEST)
    var removedPaths by mutableStateOf(emptySet<String>())
        private set
    var busyDeleting by mutableStateOf(false)
        private set
    var pendingDelete by mutableStateOf<CatalogFile?>(null)
    var cleanupSnapshot by mutableStateOf<List<CatalogFile>?>(null)
    var confirmedCleanup by mutableStateOf<List<CatalogFile>?>(null)
    var pendingExportId by mutableStateOf<Long?>(null)
    var exporting by mutableStateOf(false)

    val avatarArea: Boolean get() = storageArea == STORAGE_AREA_AVATARS
    val selectedType: ManagedFileType? get() = ManagedFileType.entries.find { it.name == selectedTypeName }

    fun switchArea(area: String) {
        if (area == storageArea) return
        require(area == STORAGE_AREA_CHAT || area == STORAGE_AREA_AVATARS)
        storageArea = area
        resetFilters()
        pendingDelete = null
        cleanupSnapshot = null
        confirmedCleanup = null
        // A completed delete must stay hidden even if its IO finished in the other tab.
    }

    fun resetFilters() {
        query = ""
        selectedTypeName = null
        ownerKey = "all"
    }

    fun areaFiles(files: List<CatalogFile>, avatarPaths: Set<String>): List<CatalogFile> =
        storageAreaFiles(files, storageArea, avatarPaths)

    fun visibleFiles(files: List<CatalogFile>, avatarOwners: Map<String, Set<String>>): List<CatalogFile> {
        val owner = when {
            ownerKey == "unassigned" -> CatalogAssistantFilter.Unassigned
            ownerKey == "shared" -> CatalogAssistantFilter.Shared
            ownerKey == "unknown" -> CatalogAssistantFilter.Unknown
            ownerKey.startsWith("assistant:") -> CatalogAssistantFilter.Assistant(ownerKey.removePrefix("assistant:"))
            else -> CatalogAssistantFilter.All
        }
        val matching = if (avatarArea) {
            filterAvatarFiles(files, avatarOwners, selectedType, ownerKey, query, sort)
        } else {
            filterManagedFiles(files, selectedType, owner, query, sort)
        }
        return matching.filterNot { it.entity.relativePath in removedPaths }
    }

    /** Reconcile against the full successful catalog, never the current tab or a loading placeholder. */
    fun reconcile(files: List<CatalogFile>) {
        if (removedPaths.isEmpty()) return
        val present = files.mapTo(HashSet()) { it.entity.relativePath }
        removedPaths = removedPaths.filterTo(LinkedHashSet()) { it in present }
    }

    suspend fun deleteFiles(
        targets: List<CatalogFile>,
        delete: suspend (CatalogFile) -> Boolean,
        onFailure: (Exception) -> Unit,
    ): FileDeletionSummary? {
        if (busyDeleting) return null
        busyDeleting = true
        var succeeded = 0
        var failed = 0
        try {
            for (target in targets.distinctBy { it.entity.relativePath }) {
                val deleted = try {
                    delete(target)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    onFailure(error)
                    false
                }
                if (deleted) {
                    removedPaths = removedPaths + target.entity.relativePath
                    succeeded++
                } else {
                    failed++
                }
            }
            return FileDeletionSummary(succeeded, failed)
        } finally {
            busyDeleting = false
        }
    }

    companion object {
        // Destructive confirmation snapshots are deliberately NOT restored after process death.
        val Saver = listSaver<FileManagerState, Any>(
            save = {
                listOf(
                    it.storageArea,
                    it.query,
                    it.selectedTypeName.orEmpty(),
                    it.ownerKey,
                    it.sort.name,
                    it.removedPaths.toList(),
                    it.pendingExportId ?: -1L
                )
            },
            restore = { saved ->
                FileManagerState().apply {
                    storageArea = saved[0] as String
                    query = saved[1] as String
                    selectedTypeName = (saved[2] as String).ifEmpty { null }
                    ownerKey = saved[3] as String
                    sort = FileManagerSort.valueOf(saved[4] as String)
                    @Suppress("UNCHECKED_CAST")
                    removedPaths = (saved[5] as List<String>).toSet()
                    pendingExportId = (saved[6] as Long).takeUnless { it == -1L }
                }
            },
        )
    }
}
