package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.memory.HistoryMemoryManager
import me.rerere.rikkahub.data.ai.memory.RecallExcerpt
import me.rerere.rikkahub.data.db.entity.MemoryRecallRecord
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.memoryId
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 记忆使用记录：只回答三个问题——这轮找到了什么、注入了什么、有没有被模型读过。
 * 不假装知道模型最后有没有采用。
 */
@Composable
internal fun MemoryUsageScreen(assistant: Assistant, openChat: (String) -> Unit) {
    val history: HistoryMemoryManager = koinInject()
    val records by remember(assistant.memoryId) { history.recalls(assistant.memoryId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var detail by remember(assistant.memoryId) { mutableStateOf<MemoryRecallRecord?>(null) }
    var clear by remember(assistant.memoryId) { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("最近 50 轮聊天用到了哪些记忆。", style = MaterialTheme.typography.titleSmall)
            Text(
                "这里记录的是“找到”和“放进请求”的记忆。模型有没有真正使用，无法从记录判断，所以不写成“已使用”。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (assistant.enableMemory) {
                if (records.isEmpty()) Text("还没有记录。开启“保存记忆使用记录”后，新聊天会出现在这里。")
                else TextButton(onClick = { clear = true }) { Text("清空记录") }
            } else {
                Text("这个助手还没有开启记忆。")
            }
        }
        items(records, key = { it.id }) { record ->
            val matches = remember(record.matches) { runCatching { Json.decodeFromString<List<String>>(record.matches) }.getOrDefault(emptyList()) }
            val injected = remember(record.injected) { runCatching { Json.decodeFromString<List<RecallExcerpt>>(record.injected) }.getOrDefault(emptyList()) }
            ListItem(
                modifier = Modifier.clickable { detail = record },
                headlineContent = { Text(usageHeadline(matches.size, injected.size)) },
                supportingContent = {
                    Text(
                        "${localDateTime(record.recordedAt)} · ${record.elapsedMillis} ms",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
            )
        }
    }
    detail?.let { record ->
        val matched = remember(record.matches) { runCatching { Json.decodeFromString<List<String>>(record.matches) }.getOrDefault(emptyList()) }
        val injected = remember(record.injected) { runCatching { Json.decodeFromString<List<RecallExcerpt>>(record.injected) }.getOrDefault(emptyList()) }
        val reads = remember(record.toolReads) { runCatching { Json.decodeFromString<List<String>>(record.toolReads) }.getOrDefault(emptyList()) }
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(usageHeadline(matched.size, injected.size)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("找到的相关记忆", style = MaterialTheme.typography.titleSmall)
                    Text(matched.joinToString("\n").ifEmpty { "无" })
                    Text("放进本轮请求的内容", style = MaterialTheme.typography.titleSmall)
                    if (injected.isEmpty()) Text("无") else injected.forEach { Text("${it.path}\n${it.content}") }
                    if (reads.isNotEmpty()) {
                        Text("模型主动读取过的记忆", style = MaterialTheme.typography.titleSmall)
                        Text(reads.joinToString("\n"))
                    }
                    Text("约 ${record.tokens} Token", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { detail = null; openChat(record.conversationId) }) { Text("打开这轮聊天") }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } },
        )
    }
    if (clear) {
        AlertDialog(
            onDismissRequest = { clear = false },
            title = { Text("清空记忆使用记录？") },
            text = { Text("只删除记录，不影响已经记住的内容。") },
            confirmButton = {
                TextButton(onClick = {
                    clear = false
                    scope.launch { history.clearRecalls(assistant.memoryId) }
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } },
        )
    }
}

private fun usageHeadline(matched: Int, injected: Int): String = when {
    matched == 0 -> "这轮没有找到相关记忆"
    injected == 0 -> "本轮检索到 $matched 条相关记忆，但没有放进请求"
    else -> "本轮检索到 $matched 条相关记忆，其中 $injected 条放进了请求"
}

internal fun localDateTime(millis: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(millis))
