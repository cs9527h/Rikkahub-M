package me.rerere.rikkahub.ui.pages.assistant.detail

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.MemoryConsolidationStatus
import me.rerere.rikkahub.data.ai.memory.HISTORY_INPUT_CHARS
import me.rerere.rikkahub.data.ai.memory.HISTORY_OUTPUT_TOKENS
import me.rerere.rikkahub.data.ai.memory.HistoryMemoryManager
import me.rerere.rikkahub.data.db.entity.HistoryMemoryCandidate
import me.rerere.rikkahub.data.db.entity.HistoryMemoryJob
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.util.Locale

private enum class MemorySection { HOME, REVIEW, USAGE, SETTINGS }

private enum class MemoryGroup(val label: String) {
    ABOUT("关于我"),
    PREFERENCE("喜好与习惯"),
    PEOPLE("重要的人"),
    GOAL("长期目标"),
    ONGOING("持续事项"),
    OTHER("其他"),
}

/**
 * 记忆中心：一个页面回答“记住了什么、要不要确认、怎么整理”，技术细节收进高级设置。
 */
@Composable
fun AssistantMemoryPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val deletedMemories by vm.deletedMemories.collectAsStateWithLifecycle()
    val consolidation by vm.memoryConsolidation.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val history: HistoryMemoryManager = koinInject()
    val scope = rememberCoroutineScope()
    val memoryId = vm.memoryId

    var section by rememberSaveable { mutableStateOf(MemorySection.HOME) }
    BackHandler(enabled = section != MemorySection.HOME) { section = MemorySection.HOME }

    val job by remember(memoryId) { history.observe(memoryId) }.collectAsStateWithLifecycle(initialValue = null)
    val jobId = job?.id.orEmpty()
    val pending by remember(jobId) { history.candidates(jobId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val savedCount by remember(jobId) { history.count(jobId, "saved") }.collectAsStateWithLifecycle(initialValue = 0)
    val state by remember(memoryId) { history.state(memoryId) }.collectAsStateWithLifecycle(initialValue = null)

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<HistoryMemoryManager.Plan?>(null) }
    var autoSave by rememberSaveable { mutableStateOf(true) }
    var autoJobId by rememberSaveable { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<HistoryMemoryManager.BulkResult?>(null) }
    var editing by remember { mutableStateOf<HistoryMemoryCandidate?>(null) }

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message?.takeIf { e is IllegalStateException || e is IllegalArgumentException }
                    ?: "操作失败，请重试"
            } finally {
                busy = false
            }
        }
    }

    fun startOrganize() = run {
        plan = null
        val proposed = history.prepareIncremental(memoryId, assistant)
        if (proposed == null) {
            notice = "最近没有新的聊天需要整理，你之前整理的记忆都还在。"
        } else {
            section = MemorySection.HOME
            plan = proposed
        }
    }

    // 整理完成后按“保守模式”自动落库：只保存明确的新事实，需要确认的留给用户。
    LaunchedEffect(job?.id, job?.status) {
        val current = job ?: return@LaunchedEffect
        if (current.id != autoJobId || current.status != "finished") return@LaunchedEffect
        autoJobId = null
        val keys = history.pendingKeys(current.id)
        if (keys.isEmpty()) return@LaunchedEffect
        busy = true
        try {
            result = history.saveSelected(
                memoryId,
                current.id,
                keys.toSet(),
                if (autoSave) HistoryMemoryManager.BulkMode.SAVE_NEW_ONLY else HistoryMemoryManager.BulkMode.SAVE_SELECTED,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = "整理完成，但自动保存没有成功；候选都还在，可以点“去确认”。"
        } finally {
            busy = false
        }
    }

    val title = when (section) {
        MemorySection.HOME -> "记忆"
        MemorySection.REVIEW -> "确认记忆"
        MemorySection.USAGE -> "记忆使用记录"
        MemorySection.SETTINGS -> "记忆设置"
    }
    val subtitle = when (section) {
        MemorySection.HOME -> "让我记住对你重要的事情"
        MemorySection.REVIEW -> "确认之后才写入，随时可以撤销"
        MemorySection.USAGE -> "这一轮到底带上了哪些记忆"
        MemorySection.SETTINGS -> "共享范围、预算和后台整理"
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                subtitle = { Text(subtitle) },
                navigationIcon = {
                    if (section == MemorySection.HOME) {
                        BackButton()
                    } else {
                        SectionBack { section = MemorySection.HOME }
                    }
                },
                actions = {
                    if (section == MemorySection.HOME) {
                        FilledTonalIconButton(
                            onClick = { navController.navigate(Screen.MemoryFile(memoryId, null)) },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.add))
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
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (section) {
                MemorySection.HOME -> MemoryHome(
                    assistant = assistant,
                    memories = memories,
                    deletedMemories = deletedMemories,
                    consolidation = consolidation,
                    job = job,
                    pending = pending,
                    savedCount = savedCount,
                    lastOrganizedAt = state?.lastOrganizedAt ?: 0,
                    lastResult = state?.lastResult.orEmpty(),
                    busy = busy,
                    notice = notice,
                    onNoticeShown = { notice = null },
                    onOrganize = { startOrganize() },
                    onOpenReview = { section = MemorySection.REVIEW },
                    onOpenUsage = { section = MemorySection.USAGE },
                    onOpenSettings = { section = MemorySection.SETTINGS },
                    onOpenMemory = { path -> navController.navigate(Screen.MemoryFile(memoryId, path)) },
                    onDelete = { vm.deleteMemory(it) },
                    onRestore = { vm.restoreMemory(it) },
                    onPurge = { vm.purgeMemory(it) },
                    onPause = { run { history.control(memoryId, "paused") } },
                    onResume = { run { history.control(memoryId, "running") } },
                    onCancel = { run { history.control(memoryId, "cancelled") } },
                    previewRecall = { query -> vm.previewRecall(query) },
                )

                MemorySection.REVIEW -> {
                    val current = job
                    if (current == null) {
                        MissingJob { section = MemorySection.HOME }
                    } else {
                        MemoryReviewScreen(
                            scope = memoryId,
                            job = current,
                            pending = pending,
                            history = history,
                            onResult = { result = it },
                            onEdit = { editing = it },
                        )
                    }
                }

                MemorySection.USAGE -> MemoryUsageScreen(assistant) { navController.navigate(Screen.Chat(it)) }

                MemorySection.SETTINGS -> MemorySettingsScreen(
                    assistant = assistant,
                    onUpdateAssistant = { vm.update(it) },
                    onOpenModels = { navController.navigate(Screen.SettingModels) },
                )
            }

            error?.let { message ->
                Card(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                        TextButton(onClick = { error = null }) { Text("知道了") }
                    }
                }
            }
        }
    }

    plan?.let { proposed ->
        OrganizeDialog(
            plan = proposed,
            targetLabel = if (assistant.useGlobalMemory) "所有助手共享的记忆" else "「${assistant.name.ifBlank { "这个助手" }}」的记忆",
            autoSave = autoSave,
            onAutoSaveChange = { autoSave = it },
            busy = busy,
            onDismiss = { plan = null },
            onConfirm = {
                run {
                    val id = history.start(proposed)
                    autoJobId = id
                    plan = null
                    section = MemorySection.HOME
                }
            },
        )
    }

    editing?.let { candidate ->
        HistoryCandidateDialog(
            history = history,
            target = memoryId,
            candidate = candidate,
            onDismiss = { editing = null },
            openSource = { navController.navigate(Screen.Chat(it)) },
            openMemory = { target, path -> navController.navigate(Screen.MemoryFile(target, path)) },
        ) { corrected, mergeInto ->
            editing = null
            run { history.resolve(memoryId, candidate.jobId, candidate.key, corrected, mergeInto) }
        }
    }

    result?.let { outcome ->
        BulkResultDialog(
            result = outcome,
            onUndo = {
                run {
                    result = history.undoLastBulk(memoryId) ?: outcome
                    notice = "已撤销上一次批量操作。"
                }
            },
            onReview = {
                result = null
                section = MemorySection.REVIEW
            },
            onDismiss = { result = null },
        )
    }
}

@Composable
private fun SectionBack(onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = CustomColors.listItemColors.containerColor,
        ),
    ) {
        Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
    }
}

@Composable
private fun MissingJob(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("还没有需要确认的内容。")
        TextButton(onClick = onBack) { Text("返回记忆") }
    }
}

@Composable
private fun MemoryHome(
    assistant: Assistant,
    memories: List<MemoryFile>,
    deletedMemories: List<MemoryFile>,
    consolidation: MemoryConsolidationStatus,
    job: HistoryMemoryJob?,
    pending: List<HistoryMemoryCandidate>,
    savedCount: Int,
    lastOrganizedAt: Long,
    lastResult: String,
    busy: Boolean,
    notice: String?,
    onNoticeShown: () -> Unit,
    onOrganize: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMemory: (String) -> Unit,
    onDelete: (MemoryFile) -> Unit,
    onRestore: (MemoryFile) -> Unit,
    onPurge: (MemoryFile) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    previewRecall: suspend (String) -> List<MemoryFile>,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<MemoryFile?>(null) }
    var purgeTarget by remember { mutableStateOf<MemoryFile?>(null) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    val preview by produceState<List<MemoryFile>?>(initialValue = null, key1 = query) {
        value = if (query.isBlank()) null else withContext(Dispatchers.Default) {
            runCatching { previewRecall(query) }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(4_000)
            onNoticeShown()
        }
    }

    val groups = remember(memories) {
        memories.groupBy(::memoryGroupOf)
            .toList()
            .sortedBy { (group, _) -> group.ordinal }
            .map { (group, files) -> group to files.sortedByDescending { it.updatedAt } }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "overview") {
            OverviewCard(
                count = memories.size,
                pending = pending.size,
                lastOrganizedAt = lastOrganizedAt,
                lastResult = lastResult,
                shared = assistant.useGlobalMemory,
            )
        }
        item(key = "organize") {
            OrganizeCard(
                assistant = assistant,
                consolidation = consolidation,
                job = job,
                busy = busy,
                onOrganize = onOrganize,
                onPause = onPause,
                onResume = onResume,
                onCancel = onCancel,
            )
        }
        if (pending.isNotEmpty()) {
            item(key = "pending") {
                Card(
                    onClick = onOpenReview,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("需要你确认", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${pending.size} 条可能值得记住的事，可以一次全选处理。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text("去确认", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        notice?.let { message ->
            item(key = "notice") {
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (memories.isNotEmpty()) {
            item(key = "search") {
                SearchField(query) { query = it }
            }
        }

        val searching = query.isNotBlank()
        if (searching) {
            item(key = "search-heading") {
                Text(
                    if ((preview ?: emptyList()).isEmpty()) "没有找到相关记忆" else "聊天时会带上这些记忆",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            items(preview ?: emptyList(), key = { "search:${it.path}" }) { memory ->
                MemoryRow(memory, onOpen = { onOpenMemory(memory.path) }, onDelete = { deleteTarget = memory })
            }
        } else if (memories.isEmpty() && pending.isEmpty() && savedCount == 0) {
            item(key = "empty") {
                ExtensionEmptyState(
                    message = "还没有记住什么",
                    description = "聊过几次之后再点“整理我的记忆”，我会把值得长期记住的事整理出来。",
                    icon = HugeIcons.Brain02,
                )
            }
        } else {
            groups.forEach { (group, files) ->
                item(key = "group:${group.name}") {
                    Text(group.label, style = MaterialTheme.typography.titleSmall)
                }
                items(files, key = { "memory:${it.path}" }) { memory ->
                    MemoryRow(memory, onOpen = { onOpenMemory(memory.path) }, onDelete = { deleteTarget = memory })
                }
            }
        }

        if (deletedMemories.isNotEmpty()) {
            item(key = "trash") {
                TextButton(onClick = { showTrash = !showTrash }) {
                    Text(if (showTrash) "收起最近删除" else "最近删除（${deletedMemories.size}）")
                }
            }
            if (showTrash) {
                items(deletedMemories, key = { "trash:${it.path}" }) { memory ->
                    CardGroup {
                        item(
                            trailingContent = {
                                ItemActionMenu(
                                    actions = listOf(
                                        ItemAction(
                                            text = stringResource(R.string.memory_restore),
                                            icon = HugeIcons.ArrowTurnBackward,
                                            onClick = { onRestore(memory) },
                                        ),
                                        ItemAction(
                                            text = stringResource(R.string.memory_delete_forever),
                                            icon = HugeIcons.Delete01,
                                            destructive = true,
                                            onClick = { purgeTarget = memory },
                                        ),
                                    )
                                )
                            },
                            headlineContent = { Text(memory.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    stringResource(R.string.memory_deleted_at, relativeTimeOf(memory.deletedAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            }
        }

        item(key = "advanced") {
            CardGroup {
                item(
                    headlineContent = { Text("记忆使用记录") },
                    supportingContent = { Text("看看每轮聊天实际带上了哪些记忆") },
                    onClick = onOpenUsage,
                )
                item(
                    headlineContent = { Text("高级设置") },
                    supportingContent = { Text("共享范围、整理预算、后台维护、召回预算") },
                    leadingContent = { Icon(HugeIcons.Settings03, contentDescription = null) },
                    onClick = onOpenSettings,
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let(onDelete)
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(deleteTarget?.title.orEmpty())
                Text("会移到最近删除，可以恢复，也不会再被召回。")
            }
        },
    )
    RikkaConfirmDialog(
        show = purgeTarget != null,
        title = stringResource(R.string.memory_delete_forever),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            purgeTarget?.let(onPurge)
            purgeTarget = null
        },
        onDismiss = { purgeTarget = null },
        text = { Text("永久删除后无法恢复。") },
    )
}

@Composable
private fun OverviewCard(
    count: Int,
    pending: Int,
    lastOrganizedAt: Long,
    lastResult: String,
    shared: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("已记住 $count 件事", style = MaterialTheme.typography.headlineSmall)
            Text(
                when {
                    lastOrganizedAt == 0L -> "还没有整理过历史聊天"
                    lastResult.isNotBlank() -> "最近整理：${relativeTimeOf(lastOrganizedAt)} · $lastResult"
                    else -> "最近整理：${relativeTimeOf(lastOrganizedAt)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (pending > 0) {
                Text(
                    "有 $pending 条需要你确认",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                if (shared) "这些记忆由所有助手共享" else "这些记忆只属于当前助手",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun OrganizeCard(
    assistant: Assistant,
    consolidation: MemoryConsolidationStatus,
    job: HistoryMemoryJob?,
    busy: Boolean,
    onOrganize: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val active = job
    val status = active?.status
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("智能整理", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    status == "running" && active != null ->
                        "正在整理 ${active.sourceIndex}/${active.sourceCount} 个聊天，可以先去别的页面。"
                    status == "paused" -> active?.failure ?: "整理已暂停"
                    status == "failed" -> active?.failure ?: "整理中断，可以重试"
                    else -> "看看最近的聊天里有什么值得长期记住，自动合并重复、更新过时信息。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status == "running" && active != null) {
                LinearProgressIndicator(
                    progress = { active.sourceIndex.toFloat() / active.sourceCount.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onPause, enabled = !busy) { Text("暂停") }
                    TextButton(onClick = onCancel, enabled = !busy) { Text("结束") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onOrganize, enabled = !busy) { Text("整理我的记忆") }
                    if (status == "paused" || status == "failed") {
                        TextButton(onClick = onResume, enabled = !busy) {
                            Text(if (status == "failed") "重试" else "继续")
                        }
                    }
                }
                if (consolidation.running) {
                    Text("后台正在整理最近的对话…", style = MaterialTheme.typography.labelSmall)
                } else if (consolidation.pendingTurns > 0) {
                    Text(
                        "有 ${consolidation.pendingTurns} 轮最近对话等待自动整理",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (!assistant.enableMemory) {
                    Text("给这个助手开启记忆后，整理结果才能在聊天里用上。", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    androidx.compose.material3.TextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("问一句，比如“我之前说过的阅读习惯”") },
        singleLine = true,
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun MemoryRow(memory: MemoryFile, onOpen: () -> Unit, onDelete: () -> Unit) {
    CardGroup {
        item(
            onClick = onOpen,
            trailingContent = {
                ItemActionMenu(
                    actions = listOf(
                        ItemAction(
                            text = stringResource(R.string.delete),
                            icon = HugeIcons.Delete01,
                            destructive = true,
                            onClick = onDelete,
                        ),
                    )
                )
            },
            headlineContent = {
                Text(
                    text = if (memory.isOutdated) "已过时 · ${memory.title}" else memory.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text(
                    memory.displayBody.lineSequence().firstOrNull { it.isNotBlank() }?.take(80)
                        ?: stringResource(R.string.assistant_page_memory_updated_at, relativeTimeOf(memory.updatedAt)),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }
}

@Composable
private fun OrganizeDialog(
    plan: HistoryMemoryManager.Plan,
    targetLabel: String,
    autoSave: Boolean,
    onAutoSaveChange: (Boolean) -> Unit,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var priceInput by rememberSaveable { mutableStateOf("") }
    val requests = plan.sources.size + plan.serializedChars / HISTORY_INPUT_CHARS + plan.nodes / 12L
    val inputTokens = plan.serializedChars / 2 + requests * 240
    val outputTokens = requests * HISTORY_OUTPUT_TOKENS
    val price = priceInput.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开始整理记忆？") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("会用 ${plan.destination} 阅读 ${plan.sources.size} 个最近有变化的聊天，整理后保存到$targetLabel。")
                Text("只发送你在这几个聊天里说过的话，不发送附件、助手回复、人格提示词或已有记忆。")
                Text(
                    "大约 $inputTokens 输入 Token、$outputTokens 输出 Token（估算，实际取决于分批结果）。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("已经处理过的内容不会重复发送，也不会重复计费。", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = autoSave, onCheckedChange = onAutoSaveChange)
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text("整理完自动保存有把握的", style = MaterialTheme.typography.bodyMedium)
                        Text("需要确认的会留给你，保存后可以撤销", style = MaterialTheme.typography.labelSmall)
                    }
                }
                androidx.compose.material3.TextField(
                    value = priceInput,
                    onValueChange = { priceInput = it },
                    label = { Text("每百万 Token 价格（选填，用来估算费用）") },
                    singleLine = true,
                )
                if (price != null) {
                    val cost = price * (inputTokens + outputTokens) / 1_000_000
                    Text("估算费用 ${String.format(Locale.ROOT, "%.4f", cost)}（按你填写的币种）")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onConfirm) { Text("开始整理") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BulkResultDialog(
    result: HistoryMemoryManager.BulkResult,
    onUndo: () -> Unit,
    onReview: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("整理完成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("新增 ${result.saved} 条 · 合并 ${result.merged} 条 · 更新 ${result.updated} 条")
                Text("忽略 ${result.ignored} 条 · 重复 ${result.duplicate} 条 · 需要你确认 ${result.pending} 条")
                if (result.failed > 0) {
                    Text("有 ${result.failed} 条没保存成功，内容还在候选里。", color = MaterialTheme.colorScheme.error)
                    result.failureReasons.forEach { Text("· $it", style = MaterialTheme.typography.labelSmall) }
                }
                if (result.restoredFiles > 0 || result.skippedFiles > 0) {
                    Text("已恢复 ${result.restoredFiles} 条记忆，${result.skippedFiles} 条因为后来被修改而跳过。")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            Row {
                if (result.changed > 0) TextButton(onClick = onUndo) { Text("撤销") }
                if (result.pending > 0) TextButton(onClick = onReview) { Text("去确认") }
            }
        },
    )
}

private fun memoryGroupOf(file: MemoryFile): MemoryGroup {
    val path = file.path
    return when {
        path == MemoryFile.PROFILE_PATH || path.count { it == '/' } == 1 -> MemoryGroup.ABOUT
        path == MemoryFile.PREFERENCES_PATH -> MemoryGroup.PREFERENCE
        path.startsWith("/people") -> MemoryGroup.PEOPLE
        path.startsWith("/areas") -> {
            val text = file.title + " " + file.description
            if (listOf("目标", "计划", "规划", "goal", "plan").any { it in text.lowercase() }) {
                MemoryGroup.GOAL
            } else {
                MemoryGroup.ONGOING
            }
        }
        else -> MemoryGroup.OTHER
    }
}

private fun relativeTimeOf(time: Long): String = DateUtils.getRelativeTimeSpanString(
    time,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
    DateUtils.FORMAT_ABBREV_MONTH,
).toString()
