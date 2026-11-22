package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.memory.HistoryMemoryManager
import me.rerere.rikkahub.data.ai.memory.MemorySuggestion
import me.rerere.rikkahub.data.ai.memory.MemoryTriage
import me.rerere.rikkahub.data.db.entity.HistoryMemoryCandidate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class CandidateTab(val label: String, val status: String) {
    PENDING("待审核", "pending"),
    SKIPPED("已跳过", "discarded"),
    SAVED("已保存", "saved"),
    DUPLICATE("重复", "duplicate"),
}

/**
 * 批量审核：待审核可以一次处理，已跳过的可以整批找回。
 *
 * 误触“全部跳过”是真实事故：这里所有破坏性操作都要确认，并且任何一次批量操作之后
 * 撤销入口一直可见，而不是只在“有新增”时才出现。
 */
@Composable
internal fun MemoryReviewScreen(
    scope: String,
    history: HistoryMemoryManager,
    listState: LazyListState,
    onResult: (HistoryMemoryManager.BulkResult?) -> Unit,
    onEdit: (HistoryMemoryCandidate) -> Unit,
) {
    var tab by remember { mutableStateOf(CandidateTab.PENDING) }
    var suggestions by remember { mutableStateOf<Map<String, MemoryTriage>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf<TriFilter>(TriFilter.ALL) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var seeded by remember { mutableStateOf(false) }
    var confirmSkip by remember { mutableStateOf(false) }
    var undoAvailable by remember { mutableStateOf(false) }
    val coroutine = rememberCoroutineScope()

    fun tabFlow(status: String) = history.candidates(scope, status)
    val pending by remember(scope) { tabFlow("pending") }.collectAsStateWithLifecycle(initialValue = emptyList())
    val skipped by remember(scope) { tabFlow("discarded") }.collectAsStateWithLifecycle(initialValue = emptyList())
    val saved by remember(scope) { tabFlow("saved") }.collectAsStateWithLifecycle(initialValue = emptyList())
    val duplicates by remember(scope) { tabFlow("duplicate") }.collectAsStateWithLifecycle(initialValue = emptyList())
    val visible = when (tab) {
        CandidateTab.PENDING -> pending
        CandidateTab.SKIPPED -> skipped
        CandidateTab.SAVED -> saved
        CandidateTab.DUPLICATE -> duplicates
    }

    suspend fun refreshMeta() {
        loading = true
        suggestions = try {
            history.suggestions(scope)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = "分析建议失败，可直接手动选择"
            emptyMap()
        } finally {
            loading = false
        }
        undoAvailable = history.undoAvailable(scope)
    }

    fun run(action: suspend () -> HistoryMemoryManager.BulkResult?) {
        if (busy) return
        busy = true
        error = null
        coroutine.launch {
            try {
                onResult(action())
                // 操作结束后重新读建议和撤销状态：界面状态不能靠猜测
                refreshMeta()
                selected = emptySet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "操作失败，请重试"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(scope, pending.size) {
        refreshMeta()
        if (!seeded && pending.isNotEmpty()) {
            selected = pending.filter { suggestions[it.key]?.suggestion == MemorySuggestion.SAVE }
                .map { it.key }.toSet()
            seeded = true
        }
    }

    val filtered = remember(visible, filter, suggestions) {
        if (tab != CandidateTab.PENDING) visible else visible.filter { candidate ->
            when (filter) {
                TriFilter.ALL -> true
                TriFilter.SAVE -> suggestions[candidate.key]?.suggestion == MemorySuggestion.SAVE
                TriFilter.MERGE -> suggestions[candidate.key]?.suggestion.let {
                    it == MemorySuggestion.MERGE || it == MemorySuggestion.UPDATE
                }
                TriFilter.CONFIRM -> suggestions[candidate.key]?.suggestion == MemorySuggestion.CONFIRM
                TriFilter.IGNORE -> suggestions[candidate.key]?.suggestion == MemorySuggestion.IGNORE
            }
        }
    }
    val confirmed = pending.count { suggestions[it.key]?.suggestion == MemorySuggestion.CONFIRM }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "summary") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        when (tab) {
                            CandidateTab.PENDING -> "我整理出 ${pending.size} 条可能值得记住的事"
                            CandidateTab.SKIPPED -> "有 ${skipped.size} 条候选被跳过，可以随时找回"
                            CandidateTab.SAVED -> "已经保存 ${saved.size} 条候选"
                            CandidateTab.DUPLICATE -> "有 ${duplicates.size} 条内容已经记过"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "待审核 ${pending.size} · 已跳过 ${skipped.size} · 已保存 ${saved.size}" +
                            (if (confirmed > 0) " · 其中 $confirmed 条建议你确认" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "tabs") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CandidateTab.entries.forEach { item ->
                        FilterChip(
                            selected = tab == item,
                            onClick = {
                                tab = item
                                selected = emptySet()
                            },
                            label = {
                                Text(
                                    when (item) {
                                        CandidateTab.PENDING -> "待审核 ${pending.size}"
                                        CandidateTab.SKIPPED -> "已跳过 ${skipped.size}"
                                        CandidateTab.SAVED -> "已保存 ${saved.size}"
                                        CandidateTab.DUPLICATE -> "重复 ${duplicates.size}"
                                    }
                                )
                            },
                        )
                    }
                }
            }
            if (loading && tab == CandidateTab.PENDING) {
                item(key = "loading") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ContainedLoadingIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("正在整理建议…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (tab == CandidateTab.PENDING) {
                item(key = "tools") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = selected.size == filtered.size && filtered.isNotEmpty(),
                                onClick = {
                                    val keys = filtered.map { it.key }.toSet()
                                    selected = if (selected.containsAll(keys) && keys.isNotEmpty()) {
                                        selected - keys
                                    } else {
                                        selected + keys
                                    }
                                },
                                label = { Text(if (selected.size == filtered.size && filtered.isNotEmpty()) "取消全选" else "全选") },
                            )
                            TextButton(onClick = {
                                val keys = filtered.map { it.key }.toSet()
                                selected = (keys - selected) + (selected - keys)
                            }) { Text("反选") }
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            TriFilter.entries.forEach { item ->
                                FilterChip(
                                    selected = filter == item,
                                    onClick = { filter = item },
                                    label = { Text(item.label) },
                                )
                            }
                        }
                    }
                }
            }
            if (tab == CandidateTab.SKIPPED && skipped.isNotEmpty()) {
                item(key = "restore-all") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            enabled = !busy,
                            onClick = {
                                run {
                                    val restored = history.restoreSkipped(scope)
                                    HistoryMemoryManager.BulkResult(pending = restored)
                                }
                            },
                        ) { Text("恢复全部跳过（${skipped.size}）") }
                        if (selected.isNotEmpty()) {
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    run {
                                        val restored = history.restoreSkipped(scope, selected)
                                        HistoryMemoryManager.BulkResult(pending = restored)
                                    }
                                },
                            ) { Text("恢复选中的 ${selected.size} 条") }
                        }
                    }
                }
            }
            error?.let { message ->
                item(key = "error") { Text(message, color = MaterialTheme.colorScheme.error) }
            }
            if (filtered.isEmpty() && !loading) {
                item(key = "empty") {
                    Text(
                        when (tab) {
                            CandidateTab.PENDING -> "这里暂时没有待审核的候选。"
                            CandidateTab.SKIPPED -> "没有已跳过的候选。"
                            CandidateTab.SAVED -> "还没有保存过的候选。"
                            CandidateTab.DUPLICATE -> "没有判定为重复的候选。"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(filtered, key = { "${tab.name}:${it.jobId}:${it.key}" }) { candidate ->
                CandidateRow(
                    candidate = candidate,
                    triage = suggestions[candidate.key].takeIf { tab == CandidateTab.PENDING },
                    statusLabel = if (tab == CandidateTab.PENDING) null else tab.label,
                    checked = candidate.key in selected && tab != CandidateTab.SAVED && tab != CandidateTab.DUPLICATE,
                    selectable = tab == CandidateTab.PENDING || tab == CandidateTab.SKIPPED,
                    onToggle = {
                        selected = if (candidate.key in selected) selected - candidate.key else selected + candidate.key
                    },
                    onClick = { if (tab == CandidateTab.PENDING) onEdit(candidate) },
                )
            }
            item(key = "spacer") { Spacer(Modifier.height(72.dp)) }
        }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                HorizontalDivider(Modifier.padding(bottom = 10.dp))
                when (tab) {
                    CandidateTab.PENDING -> {
                        FilledTonalButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy && selected.isNotEmpty(),
                            onClick = {
                                run { history.saveSelected(scope, selected, HistoryMemoryManager.BulkMode.SAVE_SELECTED) }
                            },
                        ) { Text("保存选中的 ${selected.size} 条") }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                enabled = !busy && pending.isNotEmpty(),
                                onClick = {
                                    run {
                                        history.saveSelected(
                                            scope,
                                            pending.map { it.key }.toSet(),
                                            HistoryMemoryManager.BulkMode.APPLY_SUGGESTIONS,
                                        )
                                    }
                                },
                            ) { Text("按建议整理") }
                            TextButton(
                                enabled = !busy && pending.isNotEmpty(),
                                onClick = { confirmSkip = true },
                            ) { Text("全部跳过") }
                        }
                    }

                    CandidateTab.SKIPPED -> {
                        Text(
                            "找回不会重新提取、不消耗 Token；候选正文、证据和来源都按原样恢复。\n" +
                                "恢复只改状态、不删除数据。需要完整备份时，可先到「设置 → 备份」导出数据库。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (undoAvailable) {
                            TextButton(
                                enabled = !busy,
                                onClick = { run { history.undoLastBulk(scope) } },
                            ) { Text("撤销上一次批量操作") }
                        }
                    }

                    else -> Text(
                        "这里只展示历史结果，不会改动任何内容。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (undoAvailable && tab != CandidateTab.SKIPPED) {
                    TextButton(
                        enabled = !busy,
                        onClick = { run { history.undoLastBulk(scope) } },
                    ) { Text("撤销上一次批量操作") }
                }
            }
        }
    }

    if (confirmSkip) {
        AlertDialog(
            onDismissRequest = { confirmSkip = false },
            title = { Text("跳过全部 ${pending.size} 条候选？") },
            text = {
                Text(
                    "跳过后它们仍然保留在“已跳过”里，可以整批恢复，不会重新调用模型；" +
                        "但为了避免误操作，请确认数量是否正确。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSkip = false
                    run {
                        val skippedCount = history.skipSelected(scope, pending.map { it.key }.toSet())
                        HistoryMemoryManager.BulkResult(ignored = skippedCount)
                    }
                }) { Text("确认跳过 ${pending.size} 条") }
            },
            dismissButton = { TextButton(onClick = { confirmSkip = false }) { Text("取消") } },
        )
    }
}

private enum class TriFilter(val label: String) {
    ALL("全部"),
    SAVE("可保存"),
    MERGE("可合并"),
    CONFIRM("需确认"),
    IGNORE("可忽略"),
}

@Composable
private fun CandidateRow(
    candidate: HistoryMemoryCandidate,
    triage: MemoryTriage?,
    statusLabel: String?,
    checked: Boolean,
    selectable: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (selectable) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
        } else {
            Spacer(Modifier.size(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = candidate.subject,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = statusLabel ?: suggestionLabel(triage),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (statusLabel != null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        suggestionColor(triage)
                    },
                )
            }
            Text(candidate.fact, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row {
                Text(
                    categoryLabel(candidate.category) + " · " + localDate(candidate.sourceTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                triage?.reason?.takeIf { it.isNotBlank() }?.let {
                    Text(" · $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

private fun suggestionLabel(triage: MemoryTriage?): String = when (triage?.suggestion) {
    MemorySuggestion.SAVE -> "建议保存"
    MemorySuggestion.MERGE -> "可合并"
    MemorySuggestion.UPDATE -> "可更新"
    MemorySuggestion.CONFIRM -> "需确认"
    MemorySuggestion.IGNORE -> "建议忽略"
    null -> "分析中"
}

@Composable
private fun suggestionColor(triage: MemoryTriage?) = when (triage?.suggestion) {
    MemorySuggestion.SAVE, MemorySuggestion.MERGE, MemorySuggestion.UPDATE -> MaterialTheme.colorScheme.primary
    MemorySuggestion.CONFIRM -> MaterialTheme.colorScheme.tertiary
    MemorySuggestion.IGNORE -> MaterialTheme.colorScheme.outline
    null -> MaterialTheme.colorScheme.outline
}

internal fun categoryLabel(category: String): String = when (category) {
    "profile" -> "个人情况"
    "preferences" -> "长期偏好"
    "study" -> "学习"
    "projects" -> "项目"
    "decisions" -> "决定"
    "people" -> "人物与关系"
    "ongoing" -> "持续事项"
    "requirements" -> "要求与限制"
    else -> category
}

internal fun localDate(value: String): String = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDate().toString()
}.getOrElse { value.take(10) }

internal fun dateOf(millis: Long, pattern: String = "yyyy-MM-dd"): String =
    DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
