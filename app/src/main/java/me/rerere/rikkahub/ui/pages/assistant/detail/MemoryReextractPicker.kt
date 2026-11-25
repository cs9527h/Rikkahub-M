package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.ai.memory.HistoryMemoryManager
import me.rerere.rikkahub.data.db.dao.HistoryConversationSource
import java.time.LocalDate
import java.time.ZoneId

/**
 * 从历史重新整理：只有用户明确要走这条路时才重新扫描指定范围内的旧聊天。
 *
 * 日常“整理我的记忆”仍然只处理有新改动的会话；这里不改变增量逻辑，
 * 只是把选择范围的能力还给用户。相同批次内容仍会被全局指纹去重，不会重复计费。
 */
@Composable
internal fun MemoryReextractPicker(
    history: HistoryMemoryManager,
    onDismiss: () -> Unit,
    onChoose: (List<HistoryConversationSource>) -> Unit,
    /** 目标记忆库所属助手；全局记忆库为 null，允许挑选所有助手的聊天。 */
    ownerAssistantId: String? = null,
) {
    var from by rememberSaveable { mutableStateOf("") }
    var through by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var all by rememberSaveable { mutableStateOf(true) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var sources by remember { mutableStateOf<List<HistoryConversationSource>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val fromTime = dateBoundary(from, false)
    val untilTime = dateBoundary(through, true)
    val valid = fromTime != null && untilTime != null && fromTime < untilTime
    LaunchedEffect(fromTime, untilTime, ownerAssistantId) {
        sources = null
        failed = false
        if (fromTime != null && untilTime != null && valid) {
            try {
                sources = history.sources(fromTime, untilTime, ownerAssistantId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            }
        }
    }
    val filtered = remember(sources, query) {
        sources.orEmpty().filter { it.title.contains(query, ignoreCase = true) }
    }
    val chosen = remember(sources, all, selected) {
        if (all) sources.orEmpty() else sources.orEmpty().filter { it.id in selected }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从历史重新整理") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "只处理你在这里选中的聊天。已经整理过的相同内容会自动跳过，不会重复计费；" +
                        "已保存的记忆不会被覆盖。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(from, { from = it }, label = { Text("起始日期 YYYY-MM-DD") }, singleLine = true)
                OutlinedTextField(through, { through = it }, label = { Text("结束日期 YYYY-MM-DD") }, singleLine = true)
                Text("按会话创建日期筛选，留空表示不限。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(all, { all = true }, label = { Text("全部") })
                    FilterChip(!all, { all = false }, label = { Text("指定会话") })
                }
                when {
                    !valid -> Text("请检查日期范围", color = MaterialTheme.colorScheme.error)
                    failed -> Text("读取会话失败，请关闭后重试", color = MaterialTheme.colorScheme.error)
                    sources == null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    sources.isNullOrEmpty() -> Text("该范围内没有会话")
                    !all -> {
                        OutlinedTextField(query, { query = it }, label = { Text("搜索会话标题") }, singleLine = true)
                        LazyColumn(modifier = Modifier.heightIn(max = 170.dp)) {
                            items(filtered, key = { it.id }) { source ->
                                val checked = source.id in selected
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selected = if (checked) selected - source.id else selected + source.id
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked, onCheckedChange = null)
                                    Column {
                                        Text(
                                            source.title.ifBlank { "未命名聊天" },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            dateOf(source.createdAt),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Text("已选择 ${chosen.size} 个会话")
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && chosen.isNotEmpty(),
                onClick = { onChoose(chosen) },
            ) { Text("查看费用与范围") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun dateBoundary(value: String, nextDay: Boolean): Long? {
    if (value.isBlank()) return if (nextDay) Long.MAX_VALUE else Long.MIN_VALUE
    return runCatching {
        LocalDate.parse(value.trim()).plusDays(if (nextDay) 1 else 0)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
}
