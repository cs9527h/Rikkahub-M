package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.memory.HistoryMemoryManager
import me.rerere.rikkahub.data.ai.memory.MemorySuggestion
import me.rerere.rikkahub.data.ai.memory.MemoryTriage
import me.rerere.rikkahub.data.db.entity.HistoryMemoryCandidate
import me.rerere.rikkahub.data.db.entity.HistoryMemoryJob
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class TriFilter(val label: String) {
    ALL("全部"),
    SAVE("可保存"),
    MERGE("可合并"),
    CONFIRM("需确认"),
    IGNORE("可忽略"),
}

/**
 * 批量审核：默认全选“建议保存”，一次按钮解决几十上百条，需要确认的仍然一条条看。
 */
@Composable
internal fun MemoryReviewScreen(
    scope: String,
    job: HistoryMemoryJob,
    pending: List<HistoryMemoryCandidate>,
    history: HistoryMemoryManager,
    onResult: (HistoryMemoryManager.BulkResult?) -> Unit,
    onEdit: (HistoryMemoryCandidate) -> Unit,
) {
    var suggestions by remember(job.id) { mutableStateOf<Map<String, MemoryTriage>>(emptyMap()) }
    var loading by remember(job.id) { mutableStateOf(true) }
    var busy by remember(job.id) { mutableStateOf(false) }
    var error by remember(job.id) { mutableStateOf<String?>(null) }
    var filter by remember(job.id) { mutableStateOf(TriFilter.ALL) }
    var selected by remember(job.id) { mutableStateOf(emptySet<String>()) }
    var seeded by remember(job.id) { mutableStateOf(false) }
    val coroutine = rememberCoroutineScope()

    suspend fun refreshSuggestions() {
        loading = true
        suggestions = try {
            history.suggestions(scope, job.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = "分析建议失败，可直接手动选择"
            emptyMap()
        } finally {
            loading = false
        }
    }

    fun run(action: suspend () -> HistoryMemoryManager.BulkResult?) {
        if (busy) return
        busy = true
        error = null
        coroutine.launch {
            try {
                onResult(action())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "操作失败，请重试"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(job.id, pending.size) {
        refreshSuggestions()
        // 第一次分析完成后，默认把“建议保存”的勾上：最省事的做法才是默认做法
        if (!seeded && pending.isNotEmpty()) {
            selected = pending.filter { suggestions[it.key]?.suggestion == MemorySuggestion.SAVE }
                .map { it.key }.toSet()
            seeded = true
        }
    }

    val visible = remember(pending, filter, suggestions) {
        pending.filter { candidate ->
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
    val confirmed = remember(pending, suggestions) {
        pending.count { suggestions[it.key]?.suggestion == MemorySuggestion.CONFIRM }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "summary") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("我整理出 ${pending.size} 条可能值得记住的事", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "建议保存 ${pending.count { suggestions[it.key]?.suggestion == MemorySuggestion.SAVE }} 条 · " +
                            "可合并 ${pending.count { suggestions[it.key]?.suggestion.let { s -> s == MemorySuggestion.MERGE || s == MemorySuggestion.UPDATE } }} 条 · " +
                            "需要你确认 $confirmed 条",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "建议只是字面比对的结果，不代表事实一定成立。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (loading) {
                item(key = "loading") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ContainedLoadingIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("正在整理建议…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item(key = "tools") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = selected.size == visible.size && visible.isNotEmpty(),
                            onClick = {
                                selected = if (selected.size == visible.size) {
                                    selected - visible.map { it.key }.toSet()
                                } else {
                                    selected + visible.map { it.key }
                                }
                            },
                            label = { Text(if (selected.size == visible.size && visible.isNotEmpty()) "取消全选" else "全选") },
                        )
                        TextButton(onClick = { selected = visible.map { it.key }.toSet() }) { Text("反选") }
                    }
                    LazyRowOfFilters(filter) { filter = it }
                }
            }
            error?.let { message ->
                item(key = "error") { Text(message, color = MaterialTheme.colorScheme.error) }
            }
            if (visible.isEmpty() && !loading) {
                item(key = "empty") {
                    Text("这里暂时没有需要你处理的记忆。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(visible, key = { it.key }) { candidate ->
                CandidateRow(
                    candidate = candidate,
                    triage = suggestions[candidate.key],
                    checked = candidate.key in selected,
                    onToggle = {
                        selected = if (candidate.key in selected) selected - candidate.key else selected + candidate.key
                    },
                    onClick = { onEdit(candidate) },
                )
            }
            item(key = "spacer") { Spacer(Modifier.height(72.dp)) }
        }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                HorizontalDivider(Modifier.padding(bottom = 10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        enabled = !busy && selected.isNotEmpty(),
                        onClick = {
                            run { history.saveSelected(scope, job.id, selected, HistoryMemoryManager.BulkMode.SAVE_SELECTED) }
                        },
                    ) { Text("保存选中的 ${selected.size} 条") }
                    TextButton(
                        enabled = !busy && pending.isNotEmpty(),
                        onClick = {
                            run { history.saveSelected(scope, job.id, pending.map { it.key }.toSet(), HistoryMemoryManager.BulkMode.APPLY_SUGGESTIONS) }
                        },
                    ) { Text("按建议整理") }
                    TextButton(
                        enabled = !busy && pending.isNotEmpty(),
                        onClick = { run { HistoryMemoryManager.BulkResult(ignored = history.skipSelected(scope, job.id, pending.map { it.key }.toSet())) } },
                    ) { Text("全部跳过") }
                }
                Text("按建议整理会直接合并或更新已有记忆，可以撤销。", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun LazyRowOfFilters(current: TriFilter, onSelect: (TriFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TriFilter.entries.forEach { item ->
            FilterChip(
                selected = current == item,
                onClick = { onSelect(item) },
                label = { Text(item.label) },
            )
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: HistoryMemoryCandidate,
    triage: MemoryTriage?,
    checked: Boolean,
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
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
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
                    text = suggestionLabel(triage),
                    style = MaterialTheme.typography.labelSmall,
                    color = suggestionColor(triage),
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

