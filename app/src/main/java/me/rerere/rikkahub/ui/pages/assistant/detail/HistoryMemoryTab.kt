package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.memory.HISTORY_INPUT_CHARS
import me.rerere.rikkahub.data.ai.memory.HISTORY_OUTPUT_TOKENS
import me.rerere.rikkahub.data.ai.memory.HistoryMemoryManager
import me.rerere.rikkahub.data.ai.memory.RecallExcerpt
import me.rerere.rikkahub.data.db.dao.HistoryConversationSource
import me.rerere.rikkahub.data.db.entity.HistoryMemoryCandidate
import me.rerere.rikkahub.data.db.entity.MemoryRecallRecord
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.memoryId
import org.koin.compose.koinInject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun HistoryMemoryTab(
    assistant: Assistant,
    openSource: (String) -> Unit,
    openMemory: (scope: String, path: String) -> Unit,
) {
    val history: HistoryMemoryManager = koinInject()
    var global by rememberSaveable(assistant.id.toString()) { mutableStateOf(assistant.useGlobalMemory) }
    val target = if (global) MemoryRepository.GLOBAL_MEMORY_ID else assistant.id.toString()
    val job by remember(target) { history.observe(target) }.collectAsStateWithLifecycle(initialValue = null)
    val active = job
    val jobId = active?.id.orEmpty()
    val candidates by remember(jobId) { history.candidates(jobId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val pending by remember(jobId) { history.count(jobId, "pending") }.collectAsStateWithLifecycle(initialValue = 0)
    val saved by remember(jobId) { history.count(jobId, "saved") }.collectAsStateWithLifecycle(initialValue = 0)
    val duplicates by remember(jobId) { history.count(jobId, "duplicate") }.collectAsStateWithLifecycle(initialValue = 0)
    val discarded by remember(jobId) { history.count(jobId, "discarded") }.collectAsStateWithLifecycle(initialValue = 0)
    val scope = rememberCoroutineScope()
    var error by remember(target) { mutableStateOf<String?>(null) }
    var busy by remember(target) { mutableStateOf(false) }
    var choosing by remember(target) { mutableStateOf(false) }
    var plan by remember(target) { mutableStateOf<HistoryMemoryManager.Plan?>(null) }
    var review by remember(target) { mutableStateOf<HistoryMemoryCandidate?>(null) }
    var cancel by remember(target) { mutableStateOf(false) }

    fun execute(action: suspend () -> Unit) {
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !global, onClick = { global = false }, label = { Text("此助手") }, enabled = !busy)
                FilterChip(selected = global, onClick = { global = true }, label = { Text("全局记忆") }, enabled = !busy)
            }
            if (!assistant.enableMemory || global != assistant.useGlobalMemory) {
                Text("保存后，在设置中启用记忆并选择此记忆库，聊天即可召回。")
            }
        }
        if (active == null || active.status == "cancelled" || (active.status == "finished" && pending == 0)) {
            item {
                FilledTonalButton(onClick = { choosing = true }, enabled = !busy) { Text("从历史聊天提取记忆") }
            }
        }
        if (active != null && active.status != "cancelled") {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val status = when (active.status) {
                        "running" -> "已排队或正在提取"
                        "paused" -> "已暂停"
                        "failed" -> "提取中断"
                        else -> "提取完成"
                    }
                    Text(status, style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(
                        progress = { active.sourceIndex.toFloat() / active.sourceCount.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("已扫描 ${active.sourceIndex}/${active.sourceCount} 个会话 · 已提取 ${active.batches} 批")
                    Text("候选 ${pending + saved + discarded + duplicates} 条 · 待审核 $pending 条 · 已保存 $saved 条 · 重复 $duplicates 条")
                    Text("累计约 ${active.estimatedTokens} Token")
                    active.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (active.status) {
                            "running" -> {
                                TextButton(enabled = !busy, onClick = { execute { history.control(target, "paused") } }) { Text("暂停") }
                            }
                            "paused", "failed" -> {
                                TextButton(enabled = !busy, onClick = { execute { history.control(target, "running") } }) {
                                    Text(if (active.status == "failed") "重试" else "继续")
                                }
                            }
                        }
                        if (active.status != "finished" || pending > 0) {
                            TextButton(enabled = !busy, onClick = { cancel = true }) {
                                Text(if (active.status == "finished") "丢弃待审核候选" else "取消任务")
                            }
                        }
                    }
                }
            }
        }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        items(candidates, key = { it.key }) { candidate ->
            ListItem(
                modifier = Modifier.clickable(enabled = !busy) { review = candidate },
                overlineContent = { Text(categoryLabel(candidate.category)) },
                headlineContent = { Text(candidate.subject) },
                supportingContent = { Text(candidate.fact, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
    if (choosing) {
        HistorySourcePicker(history, onDismiss = { choosing = false }) { sources ->
            choosing = false
            execute { plan = history.prepare(target, assistant, sources) }
        }
    }
    plan?.let { proposed ->
        HistoryConsentDialog(proposed, if (global) "全局记忆" else assistant.name.ifBlank { "此助手" }, { plan = null }) {
            plan = null
            execute { history.start(proposed) }
        }
    }
    review?.let { candidate ->
        HistoryCandidateDialog(history, target, candidate, { review = null }, openSource, openMemory) { corrected, mergeInto ->
            review = null
            execute { history.resolve(target, candidate.jobId, candidate.key, corrected, mergeInto) }
        }
    }
    if (cancel) {
        AlertDialog(
            onDismissRequest = { cancel = false },
            title = { Text("丢弃待审核候选？") },
            text = { Text("提取任务将结束。已经保存的记忆可在记忆管理中查看。") },
            confirmButton = {
                TextButton(onClick = { cancel = false; execute { history.control(target, "cancelled") } }) { Text("确认丢弃") }
            },
            dismissButton = { TextButton(onClick = { cancel = false }) { Text("返回") } },
        )
    }
}

@Composable
private fun HistorySourcePicker(
    history: HistoryMemoryManager,
    onDismiss: () -> Unit,
    onChoose: (List<HistoryConversationSource>) -> Unit,
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
    LaunchedEffect(fromTime, untilTime) {
        sources = null
        failed = false
        if (fromTime != null && untilTime != null && valid) {
            try {
                sources = history.sources(fromTime, untilTime)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            }
        }
    }
    val filtered = remember(sources, query) { sources.orEmpty().filter { it.title.contains(query, ignoreCase = true) } }
    val chosen = remember(sources, all, selected) {
        if (all) sources.orEmpty() else sources.orEmpty().filter { it.id in selected }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择历史会话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(from, { from = it }, label = { Text("起始日期 YYYY-MM-DD") }, singleLine = true)
                OutlinedTextField(through, { through = it }, label = { Text("结束日期 YYYY-MM-DD") }, singleLine = true)
                Text("按会话创建日期筛选，日期留空表示不限。")
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
                        LazyColumn(modifier = Modifier.heightIn(max = 190.dp)) {
                            items(filtered, key = { it.id }) { source ->
                                val checked = source.id in selected
                                val toggle = { selected = if (checked) selected - source.id else selected + source.id }
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable(onClick = toggle),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked, onCheckedChange = null)
                                    Column {
                                        Text(source.title.ifBlank { "未命名聊天" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(localDate(source.createdAt), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
                Text("已选择 ${chosen.size} 个会话")
            }
        },
        confirmButton = { TextButton(enabled = valid && chosen.isNotEmpty(), onClick = { onChoose(chosen) }) { Text("查看费用与发送范围") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun HistoryConsentDialog(
    plan: HistoryMemoryManager.Plan,
    target: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var inputPrice by rememberSaveable { mutableStateOf("") }
    var outputPrice by rememberSaveable { mutableStateOf("") }
    val requests = plan.sources.size + plan.serializedChars / HISTORY_INPUT_CHARS + plan.nodes / 12L
    val inputTokens = plan.serializedChars / 2 + requests * 240
    val outputTokens = requests * HISTORY_OUTPUT_TOKENS
    val inputRate = inputPrice.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    val outputRate = outputPrice.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认发送历史消息") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("发送到 ${plan.destination}")
                Text("${plan.sources.size} 个会话 · ${plan.nodes} 个消息节点 · 保存到 $target")
                Text("仅分批发送选中会话的用户文字（含编辑分支）。不发送附件、助手回复、人格提示词或现有记忆库。候选由你审核后保存。")
                Text("粗估输入 $inputTokens Token，按输出额度估算 $outputTokens Token。实际计费取决于分批结果和提供商，重试可能重复计费。")
                OutlinedTextField(inputPrice, { inputPrice = it }, label = { Text("每百万输入 Token 价格（选填）") }, singleLine = true)
                OutlinedTextField(outputPrice, { outputPrice = it }, label = { Text("每百万输出 Token 价格（选填）") }, singleLine = true)
                if (inputRate != null && outputRate != null) {
                    val cost = (inputRate * inputTokens + outputRate * outputTokens) / 1_000_000
                    Text("估算费用 ${String.format(Locale.ROOT, "%.4f", cost)}（按你填写的币种）")
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("同意发送并提取") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun HistoryCandidateDialog(
    history: HistoryMemoryManager,
    target: String,
    candidate: HistoryMemoryCandidate,
    onDismiss: () -> Unit,
    openSource: (String) -> Unit,
    openMemory: (scope: String, path: String) -> Unit,
    onResolve: (String?, MemoryFile?) -> Unit,
) {
    var mergeInto by remember(candidate.key) { mutableStateOf<MemoryFile?>(null) }
    var fact by rememberSaveable(candidate.key) { mutableStateOf(candidate.fact) }
    var related by remember(candidate.key) { mutableStateOf<List<MemoryFile>>(emptyList()) }
    LaunchedEffect(target, candidate.key) {
        try {
            related = history.related(target, candidate)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Review can still proceed using the original evidence.
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(candidate.subject) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${categoryLabel(candidate.category)} · ${candidate.sourceTime.replace('T', ' ')}")
                OutlinedTextField(fact, { fact = it }, label = { Text("记忆内容") })
                Text("用户原话")
                Text(candidate.evidence)
                TextButton(onClick = { onDismiss(); openSource(candidate.conversationId) }) { Text("打开来源会话") }
                if (related.isNotEmpty()) {
                    Text("相关旧记忆 · 请检查重复、冲突或已过时的信息", style = MaterialTheme.typography.titleSmall)
                    related.forEach { file ->
                        Text("${localDate(file.updatedAt)} · ${file.title}\n${file.displayBody.take(500)}")
                        TextButton(onClick = { onDismiss(); openMemory(target, file.path) }) { Text("打开旧记忆") }
                        FilterChip(
                            selected = mergeInto?.path == file.path,
                            onClick = { mergeInto = if (mergeInto?.path == file.path) null else file },
                            label = { Text("合并到这条记忆") },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = fact.trim().length in 4..600, onClick = { onResolve(fact, mergeInto) }) {
                Text(if (mergeInto == null) "保存记忆" else "确认合并")
            }
        },
        dismissButton = { TextButton(onClick = { onResolve(null, null) }) { Text("跳过候选") } },
    )
}

@Composable
internal fun MemoryRecallTab(assistant: Assistant, openChat: (String) -> Unit) {
    val history: HistoryMemoryManager = koinInject()
    val records by remember(assistant.memoryId) { history.recalls(assistant.memoryId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var detail by remember(assistant.memoryId) { mutableStateOf<MemoryRecallRecord?>(null) }
    var clear by remember(assistant.memoryId) { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("最近 50 轮 · 记录请求组装时的检索、注入和已读文件。模型是否采纳无法从这些记录判断。")
            if (records.isEmpty()) Text(if (assistant.enableMemory) "暂无记录" else "在设置中开启记忆后可查看记录")
            else TextButton(onClick = { clear = true }) { Text("清空记录") }
        }
        items(records, key = { it.id }) { record ->
            val matches = remember(record.matches) { Json.decodeFromString<List<String>>(record.matches) }
            val injected = remember(record.injected) { Json.decodeFromString<List<RecallExcerpt>>(record.injected) }
            ListItem(
                modifier = Modifier.clickable { detail = record },
                headlineContent = { Text(localDate(record.recordedAt, "yyyy-MM-dd HH:mm:ss")) },
                supportingContent = { Text("命中 ${matches.size} 条 · 注入 ${injected.size} 条 · ${record.elapsedMillis} ms") },
            )
        }
    }
    detail?.let { record ->
        val matched = remember(record.matches) { Json.decodeFromString<List<String>>(record.matches) }
        val injected = remember(record.injected) { Json.decodeFromString<List<RecallExcerpt>>(record.injected) }
        val reads = remember(record.toolReads) { Json.decodeFromString<List<String>>(record.toolReads) }
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text("本轮记忆记录") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已检索 · 约 ${record.tokens} Token · ${record.elapsedMillis} ms")
                    Text("检索命中\n${matched.joinToString("\n").ifEmpty { "无" }}")
                    Text("工具读取并返回到请求\n${reads.joinToString("\n").ifEmpty { "无" }}")
                    Text("注入的记忆片段")
                    if (injected.isEmpty()) Text("无")
                    injected.forEach { Text("${it.path}\n${it.content}") }
                    TextButton(onClick = { detail = null; openChat(record.conversationId) }) { Text("打开对应聊天") }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } },
        )
    }
    if (clear) {
        AlertDialog(
            onDismissRequest = { clear = false },
            title = { Text("清空记忆使用记录？") },
            confirmButton = {
                TextButton(onClick = { clear = false; scope.launch { history.clearRecalls(assistant.memoryId) } }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } },
        )
    }
}

private fun categoryLabel(category: String): String = when (category) {
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

private fun dateBoundary(value: String, nextDay: Boolean): Long? {
    if (value.isBlank()) return if (nextDay) Long.MAX_VALUE else Long.MIN_VALUE
    return runCatching {
        LocalDate.parse(value.trim()).plusDays(if (nextDay) 1 else 0)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
}

private fun localDate(millis: Long, pattern: String = "yyyy-MM-dd"): String =
    DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
