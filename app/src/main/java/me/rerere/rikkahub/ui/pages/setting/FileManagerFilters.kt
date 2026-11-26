package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.files.CatalogAssistantFilter
import me.rerere.rikkahub.data.files.CatalogFile
import me.rerere.rikkahub.data.files.ManagedFileType
import me.rerere.rikkahub.data.files.filterCatalog
import java.util.concurrent.TimeUnit

internal enum class FileManagerSort { NEWEST, OLDEST, LARGEST, NAME }

internal fun filterManagedFiles(
    files: List<CatalogFile>,
    type: ManagedFileType?,
    assistant: CatalogAssistantFilter,
    query: String,
    sort: FileManagerSort,
): List<CatalogFile> {
    val needle = query.trim()
    val matching = files.filterCatalog(type, assistant).filter {
        needle.isEmpty() || it.entity.displayName.contains(needle, ignoreCase = true) ||
            it.entity.mimeType.contains(needle, ignoreCase = true)
    }
    val nameComparator = compareBy<CatalogFile, String>(String.CASE_INSENSITIVE_ORDER) { it.entity.displayName }
        .thenBy { it.entity.id }
    return when (sort) {
        FileManagerSort.NEWEST -> matching.sortedWith(
            compareByDescending<CatalogFile> { it.entity.createdAt }.thenByDescending { it.entity.id }
        )
        FileManagerSort.OLDEST -> matching.sortedWith(
            compareBy<CatalogFile> { it.entity.createdAt }.thenBy { it.entity.id }
        )
        FileManagerSort.LARGEST -> matching.sortedWith(
            compareByDescending<CatalogFile> { it.entity.sizeBytes }.then(nameComparator)
        )
        FileManagerSort.NAME -> matching.sortedWith(nameComparator)
    }
}

/** Work only on the immutable selection shown when the cleanup sheet opened. */
internal fun cleanupManagedFiles(
    snapshot: List<CatalogFile>,
    olderThanDays: Int?,
    onlyUnassigned: Boolean,
    nowMillis: Long,
): List<CatalogFile> {
    require(olderThanDays == null || olderThanDays > 0)
    val cutoff = olderThanDays?.let { nowMillis - TimeUnit.DAYS.toMillis(it.toLong()) }
    return snapshot.filter {
        (!onlyUnassigned || it.isUnassigned) && (cutoff == null || it.entity.createdAt < cutoff)
    }
}

internal fun managedFilesSize(files: List<CatalogFile>): Long = files.fold(0L) { total, file ->
    val size = file.entity.sizeBytes.coerceAtLeast(0)
    if (Long.MAX_VALUE - total < size) Long.MAX_VALUE else total + size
}

internal fun managedExportName(name: String, id: Long): String =
    name.substringAfterLast('/').substringAfterLast('\\').filterNot { it.isISOControl() }.trim()
        .takeIf { it.isNotEmpty() && it != "." && it != ".." } ?: "file-$id"
