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
internal fun SettingFilesContent(
    state: FileManagerState,
    catalogResult: Result<List<CatalogFile>>?,
    avatarOwners: Map<String, Set<String>>,
    assistantNames: Map<String, String>,
    fileOnDisk: (ManagedFileEntity) -> File,
    deleteFile: suspend (CatalogFile) -> Boolean,
    exportFile: (ManagedFileEntity) -> Unit,
    onPreview: (ManagedFileEntity) -> Unit,
    onRefresh: () -> Unit,
    onDeletionResult: (FileDeletionSummary, Boolean, Boolean) -> Unit,
    navigationIcon: @Composable () -> Unit = { BackButton() },
) = with(state) {
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyStaggeredGridState()
    val catalogFiles = remember(catalogResult, storageArea, avatarOwners) {
        areaFiles(catalogResult?.getOrNull().orEmpty(), avatarOwners.keys)
    }
    val catalogLoading = catalogResult == null
    val catalogFailed = catalogResult?.isFailure == true
    val visible = remember(catalogFiles, avatarArea, avatarOwners, selectedType, ownerKey, query, sort, removedPaths) {
        visibleFiles(catalogFiles, avatarOwners)
    }
    LaunchedEffect(catalogResult) { catalogResult?.getOrNull()?.let(::reconcile) }
    val ownerIds = remember(catalogFiles, assistantNames) {
        (assistantNames.keys + catalogFiles.flatMap { it.assistantIds }).sortedWith(
            compareBy<String> { assistantNames[it].orEmpty() }.thenBy { it }
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
                    val deletingAvatar = avatarArea
                    scope.launch {
                        deleteFiles(listOf(target), deleteFile, ::reportFileDeletionFailure)?.let {
                            onDeletionResult(it, false, deletingAvatar)
                        }
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
                    val deletingAvatar = avatarArea
                    scope.launch {
                        deleteFiles(targets, deleteFile, ::reportFileDeletionFailure)?.let {
                            onDeletionResult(it, true, deletingAvatar)
                        }
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
                navigationIcon = navigationIcon,
                actions = {
                    IconButton(onClick = onRefresh, enabled = !catalogLoading && !busyDeleting) {
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
                                onClick = { switchArea(area) },
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
                            Button(onClick = onRefresh) { Text(stringResource(R.string.file_manager_retry)) }
                        }
                    }
                }
                visible.isEmpty() -> item(key = "empty", span = StaggeredGridItemSpan.FullLine) {
                    ExtensionEmptyState(
                        message = stringResource(if (catalogFiles.isNotEmpty()) R.string.file_manager_no_matches else if (avatarArea) R.string.file_manager_avatar_empty else R.string.setting_files_page_no_files),
                        icon = HugeIcons.FolderOpen,
                        buttonText = if (catalogFiles.isNotEmpty()) stringResource(R.string.file_manager_reset) else null,
                        onAction = { resetFilters() },
                    )
                }
                else -> items(visible, key = { it.entity.id }, contentType = { it.type }) { file ->
                    FileItem(
                        file = file, fileOnDisk = fileOnDisk(file.entity),
                        owner = if (avatarArea) avatarOwnerLabel(avatarOwners[file.entity.relativePath].orEmpty(), assistantNames) else catalogOwnerLabel(file, assistantNames),
                        onPreview = { onPreview(file.entity) },
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

private fun reportFileDeletionFailure(error: Exception) {
    android.util.Log.e("SettingFiles", me.rerere.common.util.formatExceptionForLog("files.delete", error))
}
