package me.rerere.rikkahub.ui.pages.memory

import android.provider.Settings as AndroidSettings
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.TransactionHistory
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.MemoryConsolidationStatus
import me.rerere.rikkahub.data.ai.memory.HISTORY_INPUT_CHARS
import me.rerere.rikkahub.data.ai.memory.HistoryMemoryManager
import me.rerere.rikkahub.data.db.entity.HistoryMemoryCandidate
import me.rerere.rikkahub.data.db.entity.HistoryMemoryJob
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.assistant.detail.HistoryCandidateDialog
import me.rerere.rikkahub.ui.pages.assistant.detail.MemoryAdvancedSettings
import me.rerere.rikkahub.ui.pages.assistant.detail.MemoryReextractPicker
import me.rerere.rikkahub.ui.pages.assistant.detail.MemoryReviewScreen
import me.rerere.rikkahub.ui.pages.assistant.detail.MemoryStatusSwitches
import me.rerere.rikkahub.ui.pages.assistant.detail.MemoryTaskHistoryScreen
import me.rerere.rikkahub.ui.pages.assistant.detail.MemoryUsageScreen
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.util.Locale

private enum class CenterSection { HOME, REVIEW, USAGE, TASK_HISTORY }

private enum class MemoryGroup(val label: String) {
    ABOUT("关于我"),
    PREFERENCE("喜好与习惯"),
    PEOPLE("重要的人"),
    GOAL("长期目标"),
    ONGOING("持续事项"),
    OTHER("其他"),
}

/**
 * 记忆中心：长期记忆的一级页面。一个页面回答“记住了什么、要不要确认、怎么整理”。
 *
 * 从设置页或助手详情进入，首页直接启用记忆并管理记忆文件。
 *
 * @param assistantId 从助手详情进入时带助手 id；从设置进入时为 null，跟随当前选中的助手。
 * @param path 深链要直接打开的记忆文件，普通进入为 null。
 */
@Composable
fun MemoryCenterPage(assistantId: String? = null, path: String? = null) {
    val vm: MemoryCenterVM = koinViewModel(parameters = { parametersOf(assistantId) })
    val history: HistoryMemoryManager = koinInject()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val settings by vm.settings.collectAsStateWithLifecycle()
    val assistantState by vm.assistant.collectAsStateWithLifecycle()
    // Bind callbacks to the exact snapshot rendered by this page, not a newer delegated value.
    val assistant = assistantState
    val resolvedAssistant = settings.memorySettingsAssistant(assistantId)
    val assistantAvailable = resolvedAssistant != null
    val scope by vm.scope.collectAsStateWithLifecycle()
    val useGlobal by vm.useGlobalMemory.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val deletedMemories by vm.deletedMemories.collectAsStateWithLifecycle()
    val consolidation by vm.consolidation.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val pendingCount by vm.pendingCount.collectAsStateWithLifecycle()
    val skippedCount by vm.skippedCount.collectAsStateWithLifecycle()
    val savedCount by vm.savedCount.collectAsStateWithLifecycle()
    val lastState by vm.lastState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val autoSave by vm.autoSave.collectAsStateWithLifecycle()

    var section by rememberSaveable { mutableStateOf(CenterSection.HOME) }
    var reextract by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<HistoryMemoryCandidate?>(null) }
    var deepLinkHandled by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = section != CenterSection.HOME) { section = CenterSection.HOME }

    // 每个二级页保留自己的滚动位置：来回切换不会跳回顶部。
    val homeListState = rememberLazyListState()
    val reviewListState = rememberLazyListState()
    val usageListState = rememberLazyListState()
    val taskHistoryListState = rememberLazyListState()

    val reduceMotion = remember {
        AndroidSettings.Global.getFloat(
            context.contentResolver,
            AndroidSettings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }

    // 深链只在第一次进来时用：之后作用域切换不该再跳回那个文件
    LaunchedEffect(path, scope, assistant, resolvedAssistant) {
        val target = path
        if (!deepLinkHandled && target != null && assistantAvailable &&
            assistant == resolvedAssistant && scope == assistant.memoryId
        ) {
            deepLinkHandled = true
            navController.navigate(Screen.MemoryFile(scope, target))
        }
    }

    val title = when (section) {
        CenterSection.HOME -> "记忆中心"
        CenterSection.REVIEW -> "确认记忆"
        CenterSection.USAGE -> "记忆使用记录"
        CenterSection.TASK_HISTORY -> "任务历史"
    }
    // 顶栏副标题说明“正在看的这份记忆库”，浏览别处时直接说出来，避免和聊天实际配置混淆
    val assistantLabel = assistant.name.ifBlank { "这个助手" }
    val browsingElsewhere = scope != assistant.memoryId
    val subtitle = when (section) {
        CenterSection.HOME -> when {
            browsingElsewhere -> "正在浏览另一份记忆库 · 不影响聊天设置"
            useGlobal -> "所有助手共享这一份记忆"
            else -> "只属于「$assistantLabel」"
        }
        CenterSection.REVIEW -> "确认之后才写入，随时可以撤销"
        CenterSection.USAGE -> "这一轮到底带上了哪些记忆"
        CenterSection.TASK_HISTORY -> "每一轮整理留下了什么"
    }

    Scaffold(
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(title) },
                // 副标题沿用其它二级页的默认字号，不再单独指定，四个 section 之间也不会跳字号
                subtitle = { Text(subtitle) },
                navigationIcon = {
                    if (section == CenterSection.HOME) {
                        BackButton()
                    } else {
                        CenterSectionBack { section = CenterSection.HOME }
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
            if (!assistantAvailable) {
                MemoryAssistantUnavailable(settings = settings, requestedId = assistantId) { selected ->
                    if (vm.settings.value.assistants.any { it.id == selected.id }) {
                        navController.openMemoryAssistant(assistantId.orEmpty(), selected.id.toString())
                    }
                }
            } else AnimatedContent(
                targetState = section,
                label = "memory-center-section",
                transitionSpec = {
                    if (reduceMotion) {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else {
                        // 前进：新页从右侧滑入、旧页向左退；返回时反向。和仓库里现有二级页保持一致。
                        val forward = targetState.ordinal > initialState.ordinal
                        val enter = if (forward) 1 else -1
                        (slideInHorizontally(tween(320)) { width -> enter * width } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(320)) { width -> -enter * width } + fadeOut(tween(180)))
                    }
                },
            ) { current ->
                when (current) {
                    CenterSection.HOME -> MemoryCenterHome(
                        assistantName = assistant.name,
                        assistant = assistant,
                        scopeKey = scope,
                        useGlobal = useGlobal,
                        memories = memories,
                        deletedMemories = deletedMemories,
                        consolidation = consolidation,
                        job = job,
                        listState = homeListState,
                        pendingCount = pendingCount,
                        skippedCount = skippedCount,
                        savedCount = savedCount,
                        lastOrganizedAt = lastState?.lastOrganizedAt ?: 0,
                        lastResult = lastState?.lastResult.orEmpty(),
                        memoryEnabled = assistant.enableMemory,
                        reduceMotion = reduceMotion,
                        busy = busy,
                        notice = notice,
                        onNoticeShown = { vm.noticeShown() },
                        onSelectScope = { vm.selectGlobal(it) },
                        onFollowAssistantScope = { vm.followAssistantScope() },
                        onUpdateAssistant = { vm.updateAssistant(assistant, it) },
                        assistantSelector = {
                            MemoryAssistantSelector(
                                settings = settings,
                                assistant = assistant,
                                enabled = !busy,
                                onSelect = { selected ->
                                    if (vm.settings.value.assistants.any { it.id == selected.id }) {
                                        navController.openMemoryAssistant(assistant.id.toString(), selected.id.toString())
                                    }
                                },
                            )
                        },
                        onOpenModels = { navController.navigate(Screen.SettingModels) },
                        onOrganize = { vm.startOrganize() },
                        onReextract = { reextract = true },
                        onOpenReview = { section = CenterSection.REVIEW },
                        onOpenTaskHistory = { section = CenterSection.TASK_HISTORY },
                        onOpenUsage = { section = CenterSection.USAGE },
                        onOpenMemory = { memoryPath -> navController.navigate(Screen.MemoryFile(scope, memoryPath)) },
                        onCreateMemory = { navController.navigate(Screen.MemoryFile(scope, null)) },
                        onDelete = { vm.deleteMemory(it) },
                        onRestore = { vm.restoreMemory(it) },
                        onPurge = { vm.purgeMemory(it) },
                        onPause = { vm.control("paused") },
                        onResume = { vm.control("running") },
                        onCancel = { vm.control("cancelled") },
                        previewRecall = { query -> vm.previewRecall(query) },
                    )

                    CenterSection.REVIEW -> MemoryReviewScreen(
                        scope = scope,
                        history = history,
                        listState = reviewListState,
                        onResult = { vm.showResult(it) },
                        onEdit = { editing = it },
                    )

                    // 使用记录页按助手的 memoryId 取数据，所以把当前作用域表达成一个助手的快照
                    CenterSection.USAGE -> MemoryUsageScreen(
                        assistant = remember(assistant, useGlobal) { assistant.copy(useGlobalMemory = useGlobal) },
                        listState = usageListState,
                    ) { navController.navigate(Screen.Chat(it)) }

                    CenterSection.TASK_HISTORY -> MemoryTaskHistoryScreen(
                        scope = scope,
                        history = history,
                        listState = taskHistoryListState,
                        onOpenReview = { section = CenterSection.REVIEW },
                        onError = { vm.showError(it) },
                    )
                }
            }

            error?.let { message ->
                Card(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.dismissError() }) { Text("知道了") }
                    }
                }
            }
        }
    }

    // Do not leave actionable dialogs behind when an explicit assistant is deleted.
    if (!assistantAvailable) return

    if (reextract) {
        MemoryReextractPicker(
            history = history,
            onDismiss = { reextract = false },
            onChoose = { sources ->
                reextract = false
                vm.startReextract(sources)
            },
            // 助手自己的记忆库不该把别的角色卡的聊天列出来
            ownerAssistantId = if (useGlobal) null else assistant.id.toString(),
        )
    }

    plan?.let { proposed ->
        OrganizeDialog(
            plan = proposed,
            targetLabel = if (useGlobal) "所有助手共享的记忆" else "「${assistant.name.ifBlank { "这个助手" }}」的记忆",
            autoSave = autoSave,
            onAutoSaveChange = { vm.setAutoSave(it) },
            busy = busy,
            onDismiss = { vm.dismissPlan() },
            onConfirm = { vm.confirmPlan() },
        )
    }

    editing?.let { candidate ->
        HistoryCandidateDialog(
            history = history,
            target = scope,
            candidate = candidate,
            onDismiss = { editing = null },
            openSource = { navController.navigate(Screen.Chat(it)) },
            openMemory = { target, memoryPath -> navController.navigate(Screen.MemoryFile(target, memoryPath)) },
        ) { corrected, mergeInto ->
            editing = null
            vm.resolveCandidate(candidate, corrected, mergeInto)
        }
    }

    result?.let { outcome ->
        BulkResultDialog(
            result = outcome,
            skipped = skippedCount,
            onUndo = { vm.undoLastBulk(outcome) },
            onRestoreSkipped = { vm.restoreSkipped() },
            onReview = {
                vm.dismissResult()
                section = CenterSection.REVIEW
            },
            onDismiss = { vm.dismissResult() },
        )
    }
}

@Composable
private fun CenterSectionBack(onClick: () -> Unit) {
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
private fun MemoryCenterHome(
    assistantName: String,
    assistant: Assistant,
    // 检索预览也要跟着记忆库换：只按 query 做 key 的话，切换记忆库后预览还是旧库的结果
    scopeKey: String,
    useGlobal: Boolean,
    memories: List<MemoryFile>,
    deletedMemories: List<MemoryFile>,
    consolidation: MemoryConsolidationStatus,
    job: HistoryMemoryJob?,
    listState: LazyListState,
    pendingCount: Int,
    skippedCount: Int,
    savedCount: Int,
    lastOrganizedAt: Long,
    lastResult: String,
    memoryEnabled: Boolean,
    reduceMotion: Boolean,
    busy: Boolean,
    notice: String?,
    onNoticeShown: () -> Unit,
    onSelectScope: (Boolean) -> Unit,
    onFollowAssistantScope: () -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    onOpenModels: () -> Unit,
    onOrganize: () -> Unit,
    onReextract: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenTaskHistory: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenMemory: (String) -> Unit,
    onCreateMemory: () -> Unit,
    onDelete: (MemoryFile) -> Unit,
    onRestore: (MemoryFile) -> Unit,
    onPurge: (MemoryFile) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    previewRecall: suspend (String) -> List<MemoryFile>,
    assistantSelector: @Composable () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember(scopeKey) { mutableStateOf<MemoryFile?>(null) }
    var purgeTarget by remember(scopeKey) { mutableStateOf<MemoryFile?>(null) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var advancedVisible by rememberSaveable { mutableStateOf(false) }
    // 分组折叠状态用竖线拼成字符串存：Bundle 只保证能存基本类型，Set 会在进程重建后丢
    var collapsedGroups by rememberSaveable { mutableStateOf("") }
    val collapsed = remember(collapsedGroups) {
        collapsedGroups.split('|').filter { it.isNotBlank() }.toSet()
    }
    fun toggleGroup(name: String) {
        collapsedGroups = if (name in collapsed) {
            collapsed.filterNot { it == name }.joinToString("|")
        } else {
            (collapsed + name).joinToString("|")
        }
    }

    // 搜索框钉在列表上方：点“搜索”立刻能打字，关掉搜索会清空关键词并回到完整列表
    val searchFocus = remember { FocusRequester() }
    BackHandler(enabled = searchOpen) {
        searchOpen = false
        query = ""
    }

    val preview by produceState<List<MemoryFile>?>(initialValue = null, key1 = query, key2 = scopeKey) {
        value = if (query.isBlank()) null else withContext(Dispatchers.Default) {
            runCatching { previewRecall(query) }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(4_000)
            onNoticeShown()
        }
    }

    val groups = remember(memories) {
        memories.groupBy(::memoryGroupOf)
            .toList()
            .sortedBy { (group, _) -> group.ordinal }
            .map { (group, files) -> group to files.sortedByDescending { it.updatedAt } }
    }

    // 浏览记忆库只是换个视角看记忆，和聊天实际用哪一份是两件事，这里把差异写清楚
    val actuallyGlobal = assistant.useGlobalMemory
    val browsingElsewhere = scopeKey != assistant.memoryId
    val assistantLabel = assistantName.ifBlank { "当前助手" }
    val actualScopeLabel = if (actuallyGlobal) "所有助手共享的记忆" else "「$assistantLabel」自己的记忆"

    // 只有“正在整理 / 暂停 / 失败 / 有对话等着整理”时才多占一行；
    // 平时“最近整理”已经在统计行里，不再重复一块卡片
    val jobStatus = job?.status
    val jobNeedsAttention = jobStatus == "running" || jobStatus == "paused" ||
        jobStatus == "failed" || jobStatus == "interrupted"
    val statusVisible = jobNeedsAttention || consolidation.running || consolidation.pendingTurns > 0

    Column(Modifier.fillMaxSize()) {
        if (searchOpen) {
            // 等一帧再抢焦点：输入框刚组合出来时焦点修饰符还没挂上，直接 requestFocus 会抛异常
            LaunchedEffect(Unit) {
                withFrameNanos { }
                searchFocus.requestFocus()
            }
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                    .focusRequester(searchFocus),
                placeholder = { Text("问一句，比如“我之前说过的阅读习惯”") },
                leadingIcon = {
                    Icon(HugeIcons.Search01, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    IconButton(
                        onClick = {
                            searchOpen = false
                            query = ""
                        },
                    ) {
                        Icon(HugeIcons.Cancel01, contentDescription = "关闭搜索", modifier = Modifier.size(18.dp))
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
            )
        }

        // 搜索框占一行时列表自动让出高度，不用固定高度去算
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "assistant-selector") { assistantSelector() }

            // 1) 使用状态：三个开关决定所选助手的聊天到底用不用记忆
            item(key = "usage-switches") {
                MemoryStatusSwitches(assistant = assistant, onUpdateAssistant = onUpdateAssistant)
            }

            // 2) 记忆库浏览：只换视角，不动聊天配置
            item(key = "scope") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionTitle("浏览记忆库")
                    ScopeRow(
                        assistantName = assistantLabel,
                        useGlobal = useGlobal,
                        onSelectScope = onSelectScope,
                    )
                    Text(
                        text = if (browsingElsewhere) {
                            "只切换这里浏览哪一份记忆，不会改动聊天实际使用的记忆库。聊天现在用的是$actualScopeLabel。"
                        } else {
                            "这里显示的就是聊天实际使用的记忆库；切到别的库只改浏览，不改聊天设置。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (browsingElsewhere) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (browsingElsewhere) {
                        FilledTonalButton(
                            onClick = onFollowAssistantScope,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text("回到实际使用的记忆库")
                        }
                    }
                }
            }

            // 3) 统计：一行读完，不占一张大卡
            item(key = "stats") {
                StatsStrip(
                    files = memories.size,
                    pending = pendingCount,
                    skipped = skippedCount,
                    lastOrganizedAt = lastOrganizedAt,
                )
            }

            if (statusVisible) {
                item(key = "status") {
                    OrganizeStatusRow(
                        job = job,
                        consolidation = consolidation,
                        lastOrganizedAt = lastOrganizedAt,
                        lastResult = lastResult,
                        memoryEnabled = memoryEnabled,
                        busy = busy,
                        onPause = onPause,
                        onResume = onResume,
                        onCancel = onCancel,
                    )
                }
            }

            // 4) 功能分组：整理 / 审阅 / 内容，全部平铺，不进右上角 ⋮
            item(key = "actions") {
                ActionChips(
                    pendingCount = pendingCount,
                    skippedCount = skippedCount,
                    busy = busy,
                    searchOpen = searchOpen,
                    onOrganize = onOrganize,
                    onReextract = onReextract,
                    onOpenReview = onOpenReview,
                    onOpenTaskHistory = onOpenTaskHistory,
                    onOpenUsage = onOpenUsage,
                    onToggleSearch = {
                        searchOpen = !searchOpen
                        if (!searchOpen) query = ""
                    },
                    onCreateMemory = onCreateMemory,
                )
            }

            notice?.let { message ->
                item(key = "notice") {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // 5) 记忆列表：搜索时换成召回预览，平时按分组折叠
            item(key = "list-title") {
                SectionTitle(
                    when {
                        query.isBlank() -> "记住了什么"
                        (preview ?: emptyList()).isEmpty() -> "没有找到相关记忆"
                        else -> "召回预览 · 聊天时会带上这几条"
                    },
                )
            }
            if (query.isNotBlank()) {
                items(preview ?: emptyList(), key = { "search:${it.path}" }) { memory ->
                    MemoryRow(memory, onOpen = { onOpenMemory(memory.path) }, onDelete = { deleteTarget = memory })
                }
            } else if (memories.isEmpty() && pendingCount == 0 && savedCount == 0) {
                item(key = "empty") {
                    ExtensionEmptyState(
                        message = "还没有记住什么",
                        description = "聊过几次之后再点“智能整理”，我会把值得长期记住的事整理出来；也可以自己先写一条。",
                        buttonText = "新建一条记忆",
                        onAction = onCreateMemory,
                        icon = HugeIcons.Brain02,
                    )
                }
            } else {
                groups.forEach { (group, files) ->
                    val isCollapsed = group.name in collapsed
                    item(key = "group:${group.name}") {
                        GroupHeader(
                            label = group.label,
                            count = files.size,
                            collapsed = isCollapsed,
                            onClick = { toggleGroup(group.name) },
                        )
                    }
                    if (!isCollapsed) {
                        items(files, key = { "memory:${it.path}" }) { memory ->
                            MemoryRow(memory, onOpen = { onOpenMemory(memory.path) }, onDelete = { deleteTarget = memory })
                        }
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
                                        ),
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

            // 6) 高级设置：预算、模型、时间提醒这些低频项折叠在最后，展开有过渡
            item(key = "advanced") {
                MemoryAdvancedSettings(
                    assistant = assistant,
                    expanded = advancedVisible,
                    reduceMotion = reduceMotion,
                    onExpandedChange = { advancedVisible = it },
                    onUpdateAssistant = onUpdateAssistant,
                    onOpenModels = onOpenModels,
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

/** 分区标题：和 CardGroup 的分组标题同字号同色，几个分区看起来是一个层级。 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp),
    )
}

/** 记忆库选择器：两个 chip 就够了，助手名直接写在 chip 上，不用再点开下拉才知道看的是谁。 */
@Composable
private fun ScopeRow(
    assistantName: String,
    useGlobal: Boolean,
    onSelectScope: (Boolean) -> Unit,
) {
    // 窄屏 / 大字体下两个 chip 会换行，不用固定宽度，避免文字被裁掉
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = !useGlobal,
            onClick = { onSelectScope(false) },
            label = {
                Text(
                    text = "「$assistantName」",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
            },
        )
        FilterChip(
            selected = useGlobal,
            onClick = { onSelectScope(true) },
            label = { Text("所有助手共享") },
        )
    }
}

/**
 * 统计行：四个数字一行读完，不占一张大卡。
 *
 * 文件数是记忆库里真实存在的 markdown 文件，【待确认】【已跳过】是候选数，
 * 【最近整理】是上一次整理完成的时间——对不上时用户能一眼看出“数字和列表长度为什么不同”。
 */
@Composable
private fun StatsStrip(files: Int, pending: Int, skipped: Int, lastOrganizedAt: Long) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatCell(label = "记忆文件", value = files.toString(), modifier = Modifier.weight(1f))
            StatCell(
                label = "待确认",
                value = pending.toString(),
                modifier = Modifier.weight(1f),
                valueColor = if (pending > 0) MaterialTheme.colorScheme.primary else null,
            )
            StatCell(
                label = "已跳过",
                value = skipped.toString(),
                modifier = Modifier.weight(1f),
                valueColor = if (skipped > 0) MaterialTheme.colorScheme.tertiary else null,
            )
            StatCell(
                label = "最近整理",
                value = if (lastOrganizedAt > 0) compactRelativeTime(lastOrganizedAt) else "还没有",
                modifier = Modifier.weight(1.2f),
                valueStyle = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    valueStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = valueStyle,
            color = valueColor ?: MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 最近整理状态：一行读完，失败时必须当场能点“重试”，不能让用户去别处找入口。 */
@Composable
private fun OrganizeStatusRow(
    job: HistoryMemoryJob?,
    consolidation: MemoryConsolidationStatus,
    lastOrganizedAt: Long,
    lastResult: String,
    memoryEnabled: Boolean,
    busy: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val status = job?.status
    val failed = status == "failed" || status == "interrupted"
    val icon: ImageVector = when {
        failed -> HugeIcons.AlertCircle
        status == "running" -> HugeIcons.Sparkles
        else -> HugeIcons.Time02
    }
    val tint = when {
        failed -> MaterialTheme.colorScheme.error
        status == "running" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // 这一行只在“需要动作”时才出现，所以文字优先说正在发生/失败的事，
    // 平时那句“最近整理”已经交给上面的统计行了，不在这里重复一遍
    val text = when {
        status == "running" && job != null ->
            "正在整理 ${job.sourceIndex}/${job.sourceCount.coerceAtLeast(1)} 个聊天"
        status == "paused" -> job?.failure?.takeIf { it.isNotBlank() } ?: "整理已暂停"
        failed -> job?.failure?.takeIf { it.isNotBlank() }?.let { "上次整理失败：$it" } ?: "上次整理中断，可以重试"
        consolidation.running -> "后台正在整理最近的对话…"
        consolidation.pendingTurns > 0 -> "有 ${consolidation.pendingTurns} 轮最近对话等待自动整理"
        lastOrganizedAt > 0 -> buildString {
            append("最近整理：")
            append(relativeTimeOf(lastOrganizedAt))
            if (lastResult.isNotBlank()) {
                append(" · ")
                append(lastResult)
            }
        }
        else -> "还没有整理过历史聊天"
    }
    val hint = if (!memoryEnabled) {
        "这个助手还没开启记忆，整理结果暂时不会在聊天里用上"
    } else {
        null
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status == "running") {
                    ContainedLoadingIndicator(modifier = Modifier.size(16.dp))
                } else {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    status == "running" -> {
                        TextButton(onClick = onPause, enabled = !busy) { Text("暂停") }
                        TextButton(onClick = onCancel, enabled = !busy) { Text("结束") }
                    }
                    status == "paused" -> TextButton(onClick = onResume, enabled = !busy) { Text("继续") }
                    failed -> TextButton(onClick = onResume, enabled = !busy) { Text("重试") }
                }
            }
            if (status == "running" && job != null) {
                LinearProgressIndicator(
                    progress = { job.sourceIndex.toFloat() / job.sourceCount.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            hint?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 功能分组：整理 / 审阅 / 内容。都用 chip 平铺，不藏进右上角 ⋮。 */
@Composable
private fun ActionChips(
    pendingCount: Int,
    skippedCount: Int,
    busy: Boolean,
    searchOpen: Boolean,
    onOrganize: () -> Unit,
    onReextract: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenTaskHistory: () -> Unit,
    onOpenUsage: () -> Unit,
    onToggleSearch: () -> Unit,
    onCreateMemory: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("功能")
        ActionGroup(label = "整理") {
            AssistChip(
                onClick = onOrganize,
                enabled = !busy,
                leadingIcon = { Icon(HugeIcons.Sparkles, null, Modifier.size(AssistChipDefaults.IconSize)) },
                label = { Text("智能整理") },
            )
            AssistChip(
                onClick = onReextract,
                enabled = !busy,
                leadingIcon = { Icon(HugeIcons.Time02, null, Modifier.size(AssistChipDefaults.IconSize)) },
                label = { Text("从历史提取") },
            )
        }
        ActionGroup(label = "审阅") {
            AssistChip(
                onClick = onOpenReview,
                leadingIcon = { Icon(HugeIcons.CheckList, null, Modifier.size(AssistChipDefaults.IconSize)) },
                label = { Text(if (pendingCount > 0) "确认记忆 $pendingCount" else "确认记忆") },
            )
            // 审核页自带「待审核 / 已跳过」分页，这里只负责把用户送进去
            AssistChip(
                onClick = onOpenReview,
                enabled = skippedCount > 0,
                leadingIcon = { Icon(HugeIcons.ArrowTurnBackward, null, Modifier.size(AssistChipDefaults.IconSize)) },
                label = { Text(if (skippedCount > 0) "已跳过 $skippedCount" else "已跳过") },
            )
            AssistChip(
                onClick = onOpenTaskHistory,
                leadingIcon = { Icon(HugeIcons.TransactionHistory, null, Modifier.size(AssistChipDefaults.IconSize)) },
                label = { Text("任务历史") },
            )
            AssistChip(
                onClick = onOpenUsage,
                leadingIcon = { Icon(HugeIcons.ChartColumn, null, Modifier.size(AssistChipDefaults.IconSize)) },
                label = { Text("使用记录") },
            )
        }
        ActionGroup(label = "内容") {
            AssistChip(
                onClick = onToggleSearch,
                leadingIcon = { Icon(HugeIcons.Search01, null, Modifier.size(AssistChipDefaults.IconSize)) },
                label = { Text(if (searchOpen) "收起搜索" else "搜索") },
            )
            AssistChip(
                onClick = onCreateMemory,
                leadingIcon = { Icon(HugeIcons.Add01, null, Modifier.size(AssistChipDefaults.IconSize)) },
                label = { Text("新建") },
            )
        }
    }
}

/** 一组带标题的 chip；FlowRow 保证 360dp 窄屏与 1.3 倍字体下自动换行。 */
@Composable
private fun ActionGroup(label: String, content: @Composable FlowRowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** 分组标题：可折叠，长列表里用户能把自己不关心的分组收起来。 */
@Composable
private fun GroupHeader(label: String, count: Int, collapsed: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (collapsed) HugeIcons.ArrowDown01 else HugeIcons.ArrowUp01,
                contentDescription = if (collapsed) "展开" else "折叠",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
    // 只按“真正会发送的用户文字”和分批数估算。
    // 旧公式用 message_node 总数 /12 当请求数，会把助手回复、附件与空节点也算进去，
    // 于是 291 个会话被估成几百万输出 Token——那是估算 Bug，不是真实消耗。
    val requests = maxOf(plan.sources.size.toLong(), plan.serializedChars / HISTORY_INPUT_CHARS + 1L)
        .coerceAtLeast(1L)
    val inputTokens = plan.serializedChars / 2 + requests * 240
    // 每批事实数量有限，输出按“上限 512、约为输入字符的 1/12”估算
    val outputTokens = minOf(requests * 512L, plan.serializedChars / 12 + requests * 40)
    val price = priceInput.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开始整理记忆？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("会用 ${plan.destination} 阅读 ${plan.sources.size} 个最近有变化的聊天，整理后保存到$targetLabel。")
                Text("只发送你在这几个聊天里说过的话，不发送附件、助手回复、人格提示词或已有记忆。")
                Text(
                    "估算：输入约 $inputTokens Token，输出最多约 $outputTokens Token。" +
                        "已经处理过的批次会自动跳过，实际发送量通常更少。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = autoSave, onCheckedChange = onAutoSaveChange)
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text("整理完自动保存有把握的", style = MaterialTheme.typography.bodyMedium)
                        Text("需要确认的会留给你，保存后可以撤销", style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextField(
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
    skipped: Int,
    onUndo: () -> Unit,
    onRestoreSkipped: () -> Unit,
    onReview: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 任何一次批量操作都可能需要撤销，不能因为“没有新增记忆”就把撤销藏起来。
    val undoable = result.changed > 0 || result.ignored > 0 || result.pending > 0 || result.restoredFiles > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("操作完成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("新增 ${result.saved} 条 · 合并 ${result.merged} 条 · 更新 ${result.updated} 条")
                Text("跳过 ${result.ignored} 条 · 重复 ${result.duplicate} 条 · 待确认 ${result.pending} 条")
                if (result.failed > 0) {
                    Text("有 ${result.failed} 条没保存成功，内容还在候选里。", color = MaterialTheme.colorScheme.error)
                    result.failureReasons.forEach { Text("· $it", style = MaterialTheme.typography.labelSmall) }
                }
                if (result.restoredFiles > 0 || result.skippedFiles > 0) {
                    Text("已恢复 ${result.restoredFiles} 条记忆，${result.skippedFiles} 条因为后来被修改而跳过。")
                }
                if (skipped > 0) {
                    Text("还有 $skipped 条候选在“已跳过”里，可以整批恢复。")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            Column {
                if (undoable) TextButton(onClick = onUndo) { Text("撤销这次操作") }
                if (skipped > 0) TextButton(onClick = onRestoreSkipped) { Text("恢复全部跳过") }
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

/** 统计行里的相对时间：去掉空格，挤出四列也不换行。 */
private fun compactRelativeTime(time: Long): String = relativeTimeOf(time).replace(" ", "")

private fun relativeTimeOf(time: Long): String = DateUtils.getRelativeTimeSpanString(
    time,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
    DateUtils.FORMAT_ABBREV_MONTH,
).toString()
