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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
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
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyStaggeredGridState()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    var refresh by remember { mutableStateOf(0) }
    var storageArea by rememberSaveable { mutableStateOf(STORAGE_AREA_CHAT) }
    val avatarArea = storageArea == STORAGE_AREA_AVATARS
    val avatarOwners = remember(settings) { AvatarReferences.localAvatarOwners(settings, context.filesDir) }
    val liveReferences = remember(chatService) { chatService.observeManagedFileReferences().map { it.catalogReferences() } }
    val catalogResult by produceState<Result<List<CatalogFile>>?>(null, repository, filesManager, avatarStorage, liveReferences, lifecycle, refresh) {
        value = null
        try {
            try {
                avatarStorage.synchronize()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed migration must not hide the original files; settings still classify them.
            }
            filesManager.syncFolder(FileFolders.UPLOAD)
            filesManager.syncFolder(FileFolders.AVATARS)
            combine(
                repository.observeCatalog(FileFolders.UPLOAD, context.filesDir, liveReferences),
                repository.listByFolder(FileFolders.AVATARS),
            ) { attachments, avatars ->
                attachments + avatars.map { CatalogFile(it, emptySet()) }
            }.flowWithLifecycle(lifecycle).collect { value = Result.success(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            value = Result.failure(error)
        }
    }
    val catalogFiles = remember(catalogResult, storageArea, avatarOwners) {
        storageAreaFiles(catalogResult?.getOrNull().orEmpty(), storageArea, avatarOwners.keys)
    }
    val catalogLoading = catalogResult == null
    val catalogFailed = catalogResult?.isFailure == true
    var query by rememberSaveable { mutableStateOf("") }
    var selectedTypeName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedType = selectedTypeName?.let { name -> ManagedFileType.entries.find { it.name == name } }
    var ownerKey by rememberSaveable(storageArea) { mutableStateOf("all") }
    val ownerFilter = when {
        ownerKey == "unassigned" -> CatalogAssistantFilter.Unassigned
        ownerKey == "shared" -> CatalogAssistantFilter.Shared
        ownerKey == "unknown" -> CatalogAssistantFilter.Unknown
        ownerKey.startsWith("assistant:") -> CatalogAssistantFilter.Assistant(ownerKey.removePrefix("assistant:"))
        else -> CatalogAssistantFilter.All
    }
    var sort by rememberSaveable { mutableStateOf(FileManagerSort.NEWEST) }
    val visible = remember(catalogFiles, avatarArea, avatarOwners, selectedType, ownerKey, query, sort) {
        if (avatarArea) filterAvatarFiles(catalogFiles, avatarOwners, selectedType, ownerKey, query, sort)
        else filterManagedFiles(catalogFiles, selectedType, ownerFilter, query, sort)
    }
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    val assistantNames = remember(settings.assistants, defaultAssistantName) {
        settings.assistants.associate { it.id.toString() to it.name.ifBlank { defaultAssistantName } }
    }
    val ownerIds = remember(catalogFiles, assistantNames) {
        (assistantNames.keys + catalogFiles.flatMap { it.assistantIds }).sortedWith(
            compareBy<String> { assistantNames[it].orEmpty() }.thenBy { it }
        )
    }
    var preview by remember { mutableStateOf<ManagedFileEntity?>(null) }
    var pendingDelete by remember(storageArea) { mutableStateOf<CatalogFile?>(null) }
    var cleanupSnapshot by remember(storageArea) { mutableStateOf<List<CatalogFile>?>(null) }
    var confirmedCleanup by remember(storageArea) { mutableStateOf<List<CatalogFile>?>(null) }
    var busyDeleting by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var pendingExportId by rememberSaveable { mutableStateOf<Long?>(null) }
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
        val id = pendingExportId
        pendingExportId = null
        if (uri != null && id != null) {
            scope.launch {
                exporting = true
                try {
                    val entity = checkNotNull(filesManager.get(id))
                    exportManagedFile(context, filesManager.getFile(entity), uri)
                    toaster.show(exportedMessage)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    toaster.show(exportFailedMessage)
                } finally {
                    exporting = false
                }
            }
        }
    }
    val exportFile: (ManagedFileEntity) -> Unit = { file ->
        if (!exporting && pendingExportId == null) {
            pendingExportId = file.id
            try {
                exportLauncher.launch(ManagedFileExportRequest(managedExportName(file.displayName, file.id), file.mimeType))
            } catch (_: Exception) {
                pendingExportId = null
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
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.setting_files_page_delete_file_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(target.entity.displayName)
                    Text(stringResource(if (target.isShared) R.string.file_manager_shared_warning else R.string.file_manager_delete_warning))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        busyDeleting = true
                        try {
                            val ok = deleteFile(target)
                            toaster.show(if (ok) deletedMessage else if (avatarArea) avatarRetainedMessage else deleteFailedMessage)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { toaster.show(deleteFailedMessage) }
                        finally { busyDeleting = false }
                    }
                }) { Text(stringResource(R.string.setting_files_page_delete_action)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.setting_files_page_cancel_action)) } },
        )
    }
    cleanupSnapshot?.let { snapshot ->
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
        ModalBottomSheet(onDismissRequest = { cleanupSnapshot = null }, sheetState = sheetState) {
            CleanFilesSheet(snapshot) { targets -> cleanupSnapshot = null; confirmedCleanup = targets }
        }
    }
    confirmedCleanup?.let { targets ->
        AlertDialog(
            onDismissRequest = { confirmedCleanup = null },
            title = { Text(stringResource(R.string.file_manager_clean_confirm, targets.size)) },
            text = { Text(stringResource(R.string.file_manager_clean_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmedCleanup = null
                    scope.launch {
                        busyDeleting = true
                        var allDeleted = true
                        try {
                            targets.forEach { file ->
                                try { if (!deleteFile(file)) allDeleted = false }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { allDeleted = false }
                            }
                            toaster.show(if (allDeleted) cleanedMessage else cleanFailedMessage)
                        } finally { busyDeleting = false }
                    }
                }) { Text(stringResource(R.string.setting_files_page_clean_action)) }
            },
            dismissButton = { TextButton(onClick = { confirmedCleanup = null }) { Text(stringResource(R.string.setting_files_page_cancel_action)) } },
        )
    }
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_files_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { refresh++ }, enabled = !catalogLoading && !busyDeleting) {
                        Icon(HugeIcons.Refresh01, stringResource(R.string.file_manager_refresh))
                    }
                    if (!avatarArea) {
                        IconButton(onClick = { cleanupSnapshot = visible.toList() }, enabled = visible.isNotEmpty() && !busyDeleting && !catalogLoading) {
                            Icon(HugeIcons.Clean, stringResource(R.string.setting_files_page_clean_content_description))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        val direction = LocalLayoutDirection.current
        // Filters scroll with the grid, so landscape and large-font screens retain usable space.
        LazyVerticalStaggeredGrid(
            modifier = Modifier.fillMaxSize().padding(
                top = innerPadding.calculateTopPadding(),
                start = innerPadding.calculateStartPadding(direction),
                end = innerPadding.calculateEndPadding(direction),
            ),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = innerPadding.calculateBottomPadding() + 16.dp),
            verticalItemSpacing = 10.dp,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            state = gridState,
            columns = StaggeredGridCells.Adaptive(160.dp),
        ) {
            item(key = "filters", span = StaggeredGridItemSpan.FullLine, contentType = "filters") {
                Column {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(STORAGE_AREA_CHAT, STORAGE_AREA_AVATARS).forEach { area ->
                            FilterChip(
                                selected = storageArea == area,
                                onClick = { storageArea = area; ownerKey = "all"; selectedTypeName = null; query = "" },
                                label = { Text(stringResource(if (area == STORAGE_AREA_AVATARS) R.string.file_manager_area_avatars else R.string.file_manager_area_chat)) },
                            )
                        }
                    }
                    if (avatarArea) {
                        Text(stringResource(R.string.file_manager_avatars_hint), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FileManagerFilters(
                        query = query, onQuery = { query = it },
                        selectedType = selectedType, onType = { selectedTypeName = it?.name },
                        ownerKey = ownerKey, onOwner = { ownerKey = it },
                        assistantNames = assistantNames, ownerIds = ownerIds,
                        sort = sort, onSort = { sort = it }, avatarArea = avatarArea,
                    )
                    Text(stringResource(R.string.file_manager_summary, visible.size, managedFilesSize(visible).fileSizeToString()), Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (visible.size != catalogFiles.size) {
                        Text(stringResource(R.string.file_manager_scope_summary, visible.size, catalogFiles.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (exporting || busyDeleting) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(if (exporting) R.string.file_manager_exporting else R.string.file_manager_deleting), Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                    }
                    if (catalogFiles.any { !it.ownershipComplete }) {
                        Text(stringResource(R.string.file_manager_owner_incomplete), Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            when {
                catalogLoading -> item(key = "loading", span = StaggeredGridItemSpan.FullLine) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 200.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.file_manager_loading), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                catalogFailed -> item(key = "error", span = StaggeredGridItemSpan.FullLine) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 200.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.file_manager_load_error))
                            Button(onClick = { refresh++ }) { Text(stringResource(R.string.file_manager_retry)) }
                        }
                    }
                }
                visible.isEmpty() -> item(key = "empty", span = StaggeredGridItemSpan.FullLine) {
                    ExtensionEmptyState(
                        message = stringResource(if (catalogFiles.isNotEmpty()) R.string.file_manager_no_matches else if (avatarArea) R.string.file_manager_avatar_empty else R.string.setting_files_page_no_files),
                        icon = HugeIcons.FolderOpen,
                        buttonText = if (catalogFiles.isNotEmpty()) stringResource(R.string.file_manager_reset) else null,
                        onAction = { query = ""; selectedTypeName = null; ownerKey = "all" },
                    )
                }
                else -> items(visible, key = { it.entity.id }, contentType = { it.type }) { file ->
                    FileItem(
                        file = file, fileOnDisk = filesManager.getFile(file.entity),
                        owner = if (avatarArea) avatarOwnerLabel(avatarOwners[file.entity.relativePath].orEmpty(), assistantNames) else catalogOwnerLabel(file, assistantNames),
                        onPreview = { preview = file.entity },
                        onExport = { exportFile(file.entity) },
                        onDelete = { pendingDelete = file },
                        actionsEnabled = !busyDeleting && !exporting && pendingExportId == null,
                        deleteEnabled = !busyDeleting && !exporting && pendingExportId == null && (!avatarArea || avatarOwners[file.entity.relativePath].isNullOrEmpty()),
                    )
                }
            }
        }
    }
}

@Composable
private fun FileManagerFilters(
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
private fun ownerFilterLabel(key: String, names: Map<String, String>, avatarArea: Boolean = false): String = when {
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
private fun avatarOwnerLabel(owners: Set<String>, names: Map<String, String>): String {
    if (owners.isEmpty()) return stringResource(R.string.file_manager_avatar_historical)
    val user = stringResource(R.string.file_manager_avatar_user)
    val removed = stringResource(R.string.file_manager_unknown_assistant, "…")
    val labels = owners.sorted().map { if (it == "user") user else names[it.removePrefix("assistant:")] ?: removed }
    return stringResource(R.string.file_manager_avatar_in_use, labels.joinToString("、"))
}

@Composable
private fun catalogOwnerLabel(file: CatalogFile, names: Map<String, String>): String {
    val unknown = stringResource(R.string.file_manager_owner_unknown)
    if (file.assistantIds.isEmpty()) return if (file.isUnassigned) stringResource(R.string.file_manager_unassigned) else unknown
    val removed = stringResource(R.string.file_manager_unknown_assistant, "…")
    val owners = file.assistantIds.sorted().joinToString("、") { names[it] ?: removed }
    val label = if (file.isShared) stringResource(R.string.file_manager_shared_names, owners) else owners
    return if (file.ownershipComplete) label else "$label · $unknown"
}

@Composable
private fun fileTypeLabel(type: ManagedFileType?): String = stringResource(when (type) {
    null -> R.string.file_manager_all_types
    ManagedFileType.IMAGE -> R.string.file_manager_images
    ManagedFileType.DOCUMENT -> R.string.file_manager_documents
    ManagedFileType.VIDEO -> R.string.file_manager_video
    ManagedFileType.AUDIO -> R.string.file_manager_audio
    ManagedFileType.OTHER -> R.string.file_manager_other
})

@Composable
private fun sortLabel(sort: FileManagerSort): String = stringResource(when (sort) {
    FileManagerSort.NEWEST -> R.string.file_manager_sort_newest
    FileManagerSort.OLDEST -> R.string.file_manager_sort_oldest
    FileManagerSort.LARGEST -> R.string.file_manager_sort_largest
    FileManagerSort.NAME -> R.string.file_manager_sort_name
})

@Composable
private fun FileItem(
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
private fun CleanFilesSheet(snapshot: List<CatalogFile>, onClean: (List<CatalogFile>) -> Unit) {
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
