package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FileAudio
import me.rerere.hugeicons.stroke.FileVideo
import me.rerere.hugeicons.stroke.FolderOpen
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Sorting01
import me.rerere.hugeicons.stroke.UserCircle
import me.rerere.hugeicons.stroke.View
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.AvatarReferences
import me.rerere.rikkahub.data.files.AvatarStorage
import me.rerere.rikkahub.data.files.CatalogAssistantFilter
import me.rerere.rikkahub.data.files.CatalogFile
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.ManagedFileType
import me.rerere.rikkahub.data.files.exportManagedFile
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.file.ManagedFilePreviewDialog
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import org.koin.compose.koinInject
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingFilesPage(
    filesManager: FilesManager = koinInject(),
    repository: FilesRepository = koinInject(),
    settingsStore: SettingsStore = koinInject(),
    avatarStorage: AvatarStorage = koinInject(),
    chatService: ChatService = koinInject(),
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    var refresh by remember { mutableStateOf(0) }
    val manager = rememberSaveable(saver = FileManagerState.Saver) { FileManagerState() }
    val avatarOwners = remember(settings) { AvatarReferences.localAvatarOwners(settings, context.filesDir) }
    val liveReferences = remember(chatService) { chatService.observeManagedFileReferences().map { it.catalogReferences() } }
    val catalogResult by produceState<Result<List<CatalogFile>>?>(null, repository, filesManager, avatarStorage, liveReferences, lifecycle, refresh) {
        value = null
        try {
            // Repair work can take seconds on a large upload folder. It only inserts/removes index
            // rows, so run it beside the live query instead of delaying the first paint.
            launch {
                try {
                    avatarStorage.synchronize()
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Exception) {
                    reportSettingsFailure("files.avatar.synchronize", error)
                    // A failed migration must not hide the original files; settings still classify them.
                }
                try {
                    filesManager.syncFolder(FileFolders.UPLOAD)
                    filesManager.syncFolder(FileFolders.AVATARS)
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Exception) {
                    reportSettingsFailure("files.index.repair", error)
                    // Index repair is best effort; the catalog still shows what is already known.
                }
            }
            combine(
                repository.observeCatalog(FileFolders.UPLOAD, context.filesDir, liveReferences),
                repository.listByFolder(FileFolders.AVATARS),
            ) { attachments, avatars ->
                attachments + avatars.map { CatalogFile(it, emptySet()) }
            }.flowWithLifecycle(lifecycle).collect { value = Result.success(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            reportSettingsFailure("files.catalog.observe", error)
            value = Result.failure(error)
        }
    }
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    val assistantNames = remember(settings.assistants, defaultAssistantName) {
        settings.assistants.associate { it.id.toString() to it.name.ifBlank { defaultAssistantName } }
    }
    var preview by remember { mutableStateOf<ManagedFileEntity?>(null) }
    val exportedMessage = stringResource(R.string.file_manager_exported)
    val exportFailedMessage = stringResource(R.string.file_manager_export_failed)
    val deletedMessage = stringResource(R.string.setting_files_page_deleted_toast)
    val deleteFailedMessage = stringResource(R.string.setting_files_page_delete_failed_toast)
    val cleanedMessage = stringResource(R.string.setting_files_page_cleaned_toast)
    val cleanFailedMessage = stringResource(R.string.setting_files_page_clean_failed_toast)
    val avatarRetainedMessage = stringResource(R.string.file_manager_avatar_retained)
    suspend fun deleteFile(target: CatalogFile): Boolean {
        val current = filesManager.get(target.entity.id) ?: return false
        val activeAvatars = AvatarReferences.localAvatarPaths(settingsStore.settingsFlow.value, context.filesDir)
        return if (current.folder == FileFolders.AVATARS || current.relativePath in activeAvatars || target.isUnassigned) {
            avatarStorage.deleteIfUnused(current.relativePath)
        } else {
            filesManager.delete(current.id)
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ManagedFileExportContract()) { uri ->
        val id = manager.pendingExportId
        manager.pendingExportId = null
        if (uri != null && id != null) {
            scope.launch {
                manager.exporting = true
                try {
                    val entity = checkNotNull(filesManager.get(id))
                    exportManagedFile(context, filesManager.getFile(entity), uri)
                    toaster.show(exportedMessage)
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Exception) {
                    reportSettingsFailure("files.export.write", error)
                    toaster.show(exportFailedMessage)
                } finally {
                    manager.exporting = false
                }
            }
        }
    }
    val exportFile: (ManagedFileEntity) -> Unit = { file ->
        if (!manager.exporting && manager.pendingExportId == null) {
            manager.pendingExportId = file.id
            try {
                exportLauncher.launch(ManagedFileExportRequest(managedExportName(file.displayName, file.id), file.mimeType))
            } catch (error: Exception) {
                reportSettingsFailure("files.export.launch", error)
                manager.pendingExportId = null
                toaster.show(exportFailedMessage)
            }
        }
    }
    preview?.let { file ->
        ManagedFilePreviewDialog(
            file = file,
            fileOnDisk = filesManager.getFile(file),
            onDismiss = { preview = null },
            onExport = { preview = null; exportFile(file) },
        )
    }
    SettingFilesContent(
        state = manager,
        catalogResult = catalogResult,
        avatarOwners = avatarOwners,
        assistantNames = assistantNames,
        fileOnDisk = filesManager::getFile,
        deleteFile = ::deleteFile,
        exportFile = exportFile,
        onPreview = { preview = it },
        onRefresh = { refresh++ },
        onDeletionResult = { result, batch, avatar ->
            toaster.show(when {
                batch -> if (result.allDeleted) cleanedMessage else cleanFailedMessage
                result.allDeleted -> deletedMessage
                avatar -> avatarRetainedMessage
                else -> deleteFailedMessage
            })
        },
    )
}

@Composable
internal fun FileManagerFilters(
    query: String, onQuery: (String) -> Unit,
    selectedType: ManagedFileType?, onType: (ManagedFileType?) -> Unit,
    ownerKey: String, onOwner: (String) -> Unit,
    assistantNames: Map<String, String>, ownerIds: List<String>,
    sort: FileManagerSort, onSort: (FileManagerSort) -> Unit,
    avatarArea: Boolean = false,
) {
    OutlinedTextField(
        value = query, onValueChange = onQuery, singleLine = true,
        placeholder = { Text(stringResource(R.string.file_manager_search)) },
        leadingIcon = { Icon(HugeIcons.Search01, null) },
        trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onQuery("") }) { Icon(HugeIcons.Cancel01, stringResource(R.string.file_manager_clear_search)) } }) else null,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
    )
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(null, ManagedFileType.IMAGE, ManagedFileType.DOCUMENT, ManagedFileType.VIDEO, ManagedFileType.AUDIO, ManagedFileType.OTHER).forEach { type ->
            FilterChip(selected = selectedType == type, onClick = { onType(type) }, label = { Text(fileTypeLabel(type)) })
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var ownerMenu by remember { mutableStateOf(false) }
        Box(Modifier.weight(1f)) {
            FilledTonalButton(onClick = { ownerMenu = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(HugeIcons.UserCircle, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(ownerFilterLabel(ownerKey, assistantNames, avatarArea), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(HugeIcons.ArrowDown01, null, Modifier.size(16.dp))
            }
            DropdownMenu(expanded = ownerMenu, onDismissRequest = { ownerMenu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                (if (avatarArea) listOf("all", "user", "shared", "unassigned") else listOf("all", "unassigned", "shared", "unknown")).forEach { key ->
                    DropdownMenuItem(text = { Text(ownerFilterLabel(key, assistantNames, avatarArea)) }, onClick = { onOwner(key); ownerMenu = false })
                }
                ownerIds.forEach { id ->
                    DropdownMenuItem(text = { Text(assistantNames[id] ?: stringResource(R.string.file_manager_unknown_assistant, id.take(6))) }, onClick = { onOwner("assistant:$id"); ownerMenu = false })
                }
            }
        }
        var sortMenu by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { sortMenu = true }) {
                Icon(HugeIcons.Sorting01, stringResource(R.string.file_manager_sort), Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(sortLabel(sort), maxLines = 1)
            }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                FileManagerSort.entries.forEach { value -> DropdownMenuItem(text = { Text(sortLabel(value)) }, onClick = { onSort(value); sortMenu = false }) }
            }
        }
    }
}

@Composable
internal fun ownerFilterLabel(key: String, names: Map<String, String>, avatarArea: Boolean = false): String = when {
    avatarArea && key == "all" -> stringResource(R.string.file_manager_avatars_all)
    avatarArea && key == "user" -> stringResource(R.string.file_manager_avatar_user)
    avatarArea && key == "shared" -> stringResource(R.string.file_manager_avatars_shared)
    avatarArea && key == "unassigned" -> stringResource(R.string.file_manager_avatar_historical)
    else -> when (key) {
    "all" -> stringResource(R.string.file_manager_all_assistants)
    "unassigned" -> stringResource(R.string.file_manager_unassigned)
    "shared" -> stringResource(R.string.file_manager_shared)
    "unknown" -> stringResource(R.string.file_manager_owner_unknown)
    else -> names[key.removePrefix("assistant:")] ?: stringResource(R.string.file_manager_unknown_assistant, key.removePrefix("assistant:").take(6))
    }
}

@Composable
internal fun avatarOwnerLabel(owners: Set<String>, names: Map<String, String>): String {
    if (owners.isEmpty()) return stringResource(R.string.file_manager_avatar_historical)
    val user = stringResource(R.string.file_manager_avatar_user)
    val removed = stringResource(R.string.file_manager_unknown_assistant, "…")
    val labels = owners.sorted().map { if (it == "user") user else names[it.removePrefix("assistant:")] ?: removed }
    return stringResource(R.string.file_manager_avatar_in_use, labels.joinToString("、"))
}

@Composable
internal fun catalogOwnerLabel(file: CatalogFile, names: Map<String, String>): String {
    val unknown = stringResource(R.string.file_manager_owner_unknown)
    if (file.assistantIds.isEmpty()) return if (file.isUnassigned) stringResource(R.string.file_manager_unassigned) else unknown
    val removed = stringResource(R.string.file_manager_unknown_assistant, "…")
    val owners = file.assistantIds.sorted().joinToString("、") { names[it] ?: removed }
    val label = if (file.isShared) stringResource(R.string.file_manager_shared_names, owners) else owners
    return if (file.ownershipComplete) label else "$label · $unknown"
}

@Composable
internal fun fileTypeLabel(type: ManagedFileType?): String = stringResource(when (type) {
    null -> R.string.file_manager_all_types
    ManagedFileType.IMAGE -> R.string.file_manager_images
    ManagedFileType.DOCUMENT -> R.string.file_manager_documents
    ManagedFileType.VIDEO -> R.string.file_manager_video
    ManagedFileType.AUDIO -> R.string.file_manager_audio
    ManagedFileType.OTHER -> R.string.file_manager_other
})

@Composable
internal fun sortLabel(sort: FileManagerSort): String = stringResource(when (sort) {
    FileManagerSort.NEWEST -> R.string.file_manager_sort_newest
    FileManagerSort.OLDEST -> R.string.file_manager_sort_oldest
    FileManagerSort.LARGEST -> R.string.file_manager_sort_largest
    FileManagerSort.NAME -> R.string.file_manager_sort_name
})

@Composable
internal fun FileItem(
    file: CatalogFile,
    fileOnDisk: File,
    owner: String,
    onPreview: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    actionsEnabled: Boolean,
    deleteEnabled: Boolean = actionsEnabled,
) {
    val entity = file.entity
    val previewDescription = stringResource(R.string.file_manager_preview_named, entity.displayName)
    Surface(
        onClick = onPreview,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = previewDescription },
        shape = MaterialTheme.shapes.large,
        color = CustomColors.listItemColors.containerColor,
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).padding(4.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                if (file.type == ManagedFileType.IMAGE) {
                    AsyncImage(model = fileOnDisk, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(when (file.type) {
                        ManagedFileType.AUDIO -> HugeIcons.FileAudio
                        ManagedFileType.VIDEO -> HugeIcons.FileVideo
                        else -> HugeIcons.File01
                    }, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entity.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) { Icon(HugeIcons.MoreVertical, stringResource(R.string.file_manager_actions_named, entity.displayName), Modifier.size(20.dp)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.file_manager_preview)) }, leadingIcon = { Icon(HugeIcons.View, null) }, onClick = { menu = false; onPreview() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.file_manager_export)) }, leadingIcon = { Icon(HugeIcons.Download01, null) }, onClick = { menu = false; onExport() }, enabled = actionsEnabled)
                            DropdownMenuItem(text = { Text(stringResource(R.string.setting_files_page_delete_action)) }, leadingIcon = { Icon(HugeIcons.Delete01, null) }, onClick = { menu = false; onDelete() }, enabled = deleteEnabled)
                        }
                    }
                }
                Text("${fileTypeLabel(file.type)} · ${entity.sizeBytes.coerceAtLeast(0).fileSizeToString()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(owner, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private enum class CleanRange {
    DAYS_7, DAYS_14, DAYS_30, ALL;

    val days: Int? get() = when (this) {
        DAYS_7 -> 7
        DAYS_14 -> 14
        DAYS_30 -> 30
        ALL -> null
    }
}

@Composable
internal fun CleanFilesSheet(snapshot: List<CatalogFile>, onClean: (List<CatalogFile>) -> Unit) {
    var range by remember { mutableStateOf(CleanRange.DAYS_7) }
    var onlyUnassigned by remember { mutableStateOf(true) }
    val now = remember { System.currentTimeMillis() }
    val targets = remember(snapshot, range, onlyUnassigned, now) { cleanupManagedFiles(snapshot, range.days, onlyUnassigned, now) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.setting_files_page_clean_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.file_manager_clean_scope), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CardGroup {
            item(
                onClick = { onlyUnassigned = !onlyUnassigned },
                headlineContent = { Text(stringResource(R.string.file_manager_clean_unused)) },
                supportingContent = { Text(stringResource(R.string.file_manager_clean_unused_hint)) },
                leadingContent = { Checkbox(checked = onlyUnassigned, onCheckedChange = null) },
            )
        }
        CardGroup(modifier = Modifier.selectableGroup()) {
            CleanRange.entries.forEach { value ->
                item(
                    onClick = { range = value },
                    modifier = Modifier.semantics { role = Role.RadioButton; selected = range == value },
                    headlineContent = { Text(value.days?.let { stringResource(R.string.setting_files_page_clean_older_than_days, it) } ?: stringResource(R.string.setting_files_page_clean_all)) },
                    leadingContent = { RadioButton(selected = range == value, onClick = null) },
                )
            }
        }
        Text(stringResource(R.string.file_manager_clean_summary, targets.size, managedFilesSize(targets).fileSizeToString()), style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = { onClean(targets.toList()) }, enabled = targets.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        ) {
            Icon(HugeIcons.Clean, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.setting_files_page_clean_action))
        }
    }
}
