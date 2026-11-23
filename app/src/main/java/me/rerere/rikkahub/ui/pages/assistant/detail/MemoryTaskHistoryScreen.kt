package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.memory.HistoryMemoryManager
import me.rerere.rikkahub.data.ai.memory.MemoryTaskLogSummary
import me.rerere.rikkahub.data.db.entity.HistoryMemoryTaskLog

/**
 * 任务历史：每一轮整理留下一条记录，回答“上次整理到哪、花了多少、为什么没跑完”。
 *
 * 任务表按 scope 只存当前这一轮，被新任务替换后上一轮就查不到了；这里读的是
 * history_memory_task_log，所以历史轮次即使被替换也能回看，只是不能续跑。
 */
@Composable
internal fun MemoryTaskHistoryScreen(
    scope: String,
    history: HistoryMemoryManager,
    listState: LazyListState,
    onOpenReview: () -> Unit,
    onError: (String) -> Unit,
) {
    val logs by remember(scope) { history.observeTaskLogs(scope) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val coroutine = rememberCoroutineScope()
    val context = LocalContext.current
    var detail by remember(scope) { mutableStateOf<HistoryMemoryTaskLog?>(null) }
    var candidateRound by remember(scope) { mutableStateOf<HistoryMemoryTaskLog?>(null) }
    var busy by remember { mutableStateOf(false) }

    // 用户事后审核候选会改变 saved/skipped：进页面时按候选表对齐一次，历史不可能是过期数字。
    LaunchedEffect(scope) { history.refreshTaskLogs(scope) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("每一轮整理都留一条记录。", style = MaterialTheme.typography.titleSmall)
                Text(
                    "数字都来自这一轮任务本身：处理的会话数、提取出的候选、实际花掉的 Token。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (logs.isEmpty()) {
            item {
                Text(
                    "还没有整理记录。回到记忆页点“整理记忆”，跑过的每一轮都会出现在这里。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(logs, key = { it.id }) { log ->
            val token = MemoryTaskLogSummary.tokenLabel(log.inputTokens, log.outputTokens)
            ListItem(
                modifier = Modifier.clickable { detail = log },
                headlineContent = { Text(MemoryTaskLogSummary.headline(log)) },
                supportingContent = {
                    Column {
                        Text(
                            listOfNotNull(
                                localDateTime(log.createdAt),
                                log.rangeLabel.takeIf { it.isNotBlank() },
                                "候选 ${log.candidateCount} 条",
                                token,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        // 失败原因直接写在列表里：用户不该为了看“为什么没跑完”再点两层。
                        // 有阶段信息时写成“在哪个阶段失败”，旧记录退回原来的单行原因。
                        val failureLine = MemoryTaskLogSummary.failedLine(log)
                            ?: log.errorMessage?.takeIf { it.isNotBlank() }
                        failureLine?.let { reason ->
                            Text(
                                reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // 有批次被自动跳过时必须写出来，否则用户只会看到“进度不动”
                        MemoryTaskLogSummary.skipLabel(log)?.let { skipped ->
                            Text(
                                skipped,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        }
    }

    detail?.let { log ->
        val token = MemoryTaskLogSummary.tokenLabel(log.inputTokens, log.outputTokens)
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text("${MemoryTaskLogSummary.statusLabel(log.status)} · ${localDateTime(log.createdAt)}") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (log.rangeLabel.isNotBlank()) {
                        Text("来源：${log.rangeLabel}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(MemoryTaskLogSummary.progressLabel(log.sourceCount, log.processedCount))
                    Text(
                        MemoryTaskLogSummary.candidateLabel(
                            log.candidateCount,
                            log.savedCount,
                            log.skippedCount,
                        )
                    )
                    token?.let { Text(it) }
                    if (log.finishedAt > 0) {
                        Text("结束时间：${localDateTime(log.finishedAt)}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (MemoryTaskLogSummary.failedLine(log) != null) {
                        Text("失败在哪一步", style = MaterialTheme.typography.titleSmall)
                        MemoryTaskLogSummary.failedLine(log)?.let { Text(it) }
                        log.errorMessage?.takeIf { it.isNotBlank() }?.let { reason ->
                            // 阶段文案和任务行里的原因可能不完全相同，原始原因仍然给出来
                            Text(reason, style = MaterialTheme.typography.bodySmall)
                        }
                        MemoryTaskLogSummary.failureAdvice(log)?.let { advice ->
                            Text(
                                advice,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        log.errorMessage?.takeIf { it.isNotBlank() }?.let { reason ->
                            Text("没跑完的原因", style = MaterialTheme.typography.titleSmall)
                            Text(reason)
                        }
                    }
                    MemoryTaskLogSummary.skipLabel(log)?.let { skipped ->
                        Text("自动跳过：$skipped", style = MaterialTheme.typography.bodySmall)
                    }
                    if (log.status == "running") {
                        Text(
                            "这一轮还没结束，数字会跟着处理进度更新。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            copyDiagnostic(context, log)
                        }
                    ) { Text("复制诊断") }
                    TextButton(
                        onClick = {
                            detail = null
                            candidateRound = log
                        }
                    ) { Text("看本轮候选") }
                    if (MemoryTaskLogSummary.canRetry(log.status)) {
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                if (busy) return@TextButton
                                busy = true
                                coroutine.launch {
                                    val resumed = try {
                                        history.retryTask(scope, log.id)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        false
                                    } finally {
                                        busy = false
                                    }
                                    detail = null
                                    if (!resumed) {
                                        onError("这一轮任务已经被新的整理任务取代，请重新开始整理。")
                                    }
                                }
                            },
                        ) { Text("从这一批继续") }
                    }
                }
            },
        )
    }

    candidateRound?.let { log ->
        val candidates by remember(log.id) { history.candidates(log.id) }
            .collectAsStateWithLifecycle(initialValue = emptyList())
        AlertDialog(
            onDismissRequest = { candidateRound = null },
            title = { Text("这一轮的待确认候选") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (candidates.isEmpty()) {
                        Text("这一轮没有待确认的候选：可能已经保存、被跳过，或者还没提取出来。")
                    } else {
                        Text(
                            "共 ${candidates.size} 条待确认。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        candidates.forEach { candidate ->
                            Text("· ${candidate.subject}：${candidate.fact}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { candidateRound = null }) { Text("关闭") } },
            dismissButton = {
                TextButton(
                    onClick = {
                        candidateRound = null
                        onOpenReview()
                    }
                ) { Text("去确认") }
            },
        )
    }
}

/**
 * 复制的是结构化的诊断文本：阶段、错误码、异常类名、游标和 Token。
 * 不含聊天正文、候选正文和任何密钥，贴到反馈里不会泄漏内容。
 */
private fun copyDiagnostic(context: Context, log: HistoryMemoryTaskLog) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(
        ClipData.newPlainText("memory-task-diagnostic", MemoryTaskLogSummary.diagnosticText(log))
    )
    Toast.makeText(context, "已复制诊断信息", Toast.LENGTH_SHORT).show()
}
