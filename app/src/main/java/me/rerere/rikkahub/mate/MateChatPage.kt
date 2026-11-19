package me.rerere.rikkahub.mate

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.utils.plus

/** Separate Mate conversation, implemented with native Compose and gateway v3.
 * The regular RikkaHub ChatService and conversation database are untouched.
 */
@Composable
fun MateChatPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentials = remember(context) { MateGatewayStore(context) }
    val journal = remember(context) { MateLocalHistory(context) }
    val transport = remember { MateChatTransport() }
    val listState = rememberLazyListState()

    var connection by remember { mutableStateOf<MateConnection?>(null) }
    var messages by remember { mutableStateOf<List<MateMessage>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("正在连接李鱼…") }
    var lastRequestId by remember { mutableStateOf<String?>(null) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var serverInfo by remember { mutableStateOf("") }
    var memoryQuery by remember { mutableStateOf("") }
    var memoryResult by remember { mutableStateOf("") }
    var memoryRows by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var toolsVisible by remember { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var hasOlder by remember { mutableStateOf(false) }
    var loadingOlder by remember { mutableStateOf(false) }

    DisposableEffect(transport) {
        onDispose { transport.cancelStreaming() }
    }
    LaunchedEffect(journal) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { journal.close() }
        }
    }
    LaunchedEffect(draft, connection?.instanceId) {
        val instance = connection?.instanceId ?: return@LaunchedEffect
        delay(450)
        try {
            journal.saveDraft(instance, draft)
        } catch (_: Exception) { /* A failed draft write cannot block chat scrolling. */ }
    }

    LaunchedEffect(Unit) {
        try {
            val saved = withContext(Dispatchers.IO) { credentials.load() }
            if (saved == null) {
                status = "尚未配对，请先进入设置 → 连接李鱼服务器"
                return@LaunchedEffect
            }
            connection = saved
            messages = journal.recent(saved.instanceId)
            hasOlder = journal.count(saved.instanceId) > messages.size
            draft = journal.draft(saved.instanceId)
            // Read-only verification. Do not send messages or auto-retry interrupted requests.
            val caps = transport.capabilities(saved)
            status = if (caps.optBoolean("text")) "服务器已连接 · 李鱼" else "服务器未开放文字聊天"
            serverInfo = "协议 3 · " + (caps.optString("version").ifBlank { "版本未知" }) +
                if (caps.optBoolean("memory_read")) " · 可只读检索记忆" else " · 记忆不可读"
            val unresolved = journal.unresolved(saved.instanceId)
            lastRequestId = unresolved.lastOrNull()
            if (lastRequestId != null) {
                status = "上次请求结果待核对，请点击“查询原请求”，不会重复调用模型"
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            status = "服务器状态未验证，历史记录仍可查看"
        } finally {
            checking = false
        }
    }

    // Loading older rows must not yank the scroll position back to the bottom.
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty() && !listState.isScrollInProgress) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    fun queryExistingRequest(id: String, current: MateConnection) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                transport.capabilities(current)
                val snapshot = transport.requestResult(current, id)
                journal.updateReply(current.instanceId, id, snapshot.text, snapshot.state)
                messages = messages.map { row ->
                    if (row.id == id + "_reply") row.copy(body = snapshot.text, state = snapshot.state)
                    else row
                }
                status = when (snapshot.state) {
                    "done" -> "原请求已经完成"
                    "running" -> "原请求仍在服务器处理中；请稍后查询"
                    else -> "原请求状态：" + snapshot.state + "（不会自动重新发送）"
                }
                if (snapshot.state in listOf("done", "expired", "failed", "uncertain", "error")) {
                    lastRequestId = journal.unresolved(current.instanceId).lastOrNull()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                status = "查询失败；原请求编号已保留，不会自动重发"
            } finally {
                busy = false
            }
        }
    }

    fun sendMessage() {
        val current = connection ?: run { status = "请先完成配对"; return }
        val body = draft.trim()
        if (body.isEmpty() || body.length > 6000 || busy || checking || activeJob != null ||
            lastRequestId != null) return
        val id = MateWire.newId()
        // Do not show an optimistic sent state until local persistence succeeds.
        busy = true
        activeJob = scope.launch {
            var latestText = ""
            var lastState = "running"
            var persistedAt = 0L
            try {
                // Verify server identity before accepting a new request.
                val caps = transport.capabilities(current)
                if (!caps.optBoolean("text")) error("服务端未提供文字聊天")
                journal.savePair(current.instanceId, id, body)
                journal.saveDraft(current.instanceId, "")
                messages = messages + journal.recent(current.instanceId, 2)
                draft = ""
                lastRequestId = id
                status = "李鱼正在回复…"
                var shownAt = 0L
                transport.stream(current, id, body) { snapshot ->
                    latestText = snapshot.text
                    lastState = snapshot.state
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (snapshot.terminal || now - persistedAt >= 500L) {
                        journal.updateReply(current.instanceId, id, latestText, lastState)
                        persistedAt = now
                    }
                    if (snapshot.terminal || now - shownAt >= 120L) {
                        messages = messages.map { row ->
                            if (row.id == id + "_reply")
                                row.copy(body = latestText, state = lastState)
                            else row
                        }
                        shownAt = now
                    }
                    if (snapshot.terminal) {
                        status = if (snapshot.state == "done") "李鱼 · 已回复" else "服务器返回错误：" + snapshot.error
                        lastRequestId = if (snapshot.state == "done") null else id
                    }
                }
            } catch (error: CancellationException) {
                status = "已停止等待；请查询原请求状态，不要重复发送"
                throw error
            } catch (error: Exception) {
                status = error.message?.take(100) ?: "发送中断；请查询原请求状态"
            } finally {
                try {
                    // The interrupted request stays queryable even after client/network errors.
                    if (lastRequestId == id) {
                        val finalState = if (lastState == "running") "uncertain" else lastState
                        journal.updateReply(current.instanceId, id, latestText, finalState)
                        messages = messages.map { row ->
                            if (row.id == id + "_reply")
                                row.copy(body = latestText, state = finalState)
                            else row
                        }
                    }
                } catch (_: Exception) { }
                busy = false
                activeJob = null
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = {
            Column {
                Text("李鱼", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal)
                Text(
                    if (checking) "连接中" else status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }, navigationIcon = { BackButton() }, actions = {
            if (!toolsVisible) {
                TextButton(onClick = { searchVisible = !searchVisible }) {
                    Text(if (searchVisible) "关闭检索" else "检索")
                }
            }
            TextButton(onClick = { toolsVisible = !toolsVisible }) {
                Text(if (toolsVisible) "聊天" else "状态")
            }
        })
    }) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            if (toolsVisible) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text("连接与只读信息", style = MaterialTheme.typography.titleMedium)
                        Text(serverInfo.ifBlank { status })
                        FilledTonalButton(
                            enabled = connection != null && !busy,
                            onClick = {
                                val current = connection ?: return@FilledTonalButton
                                scope.launch {
                                    try {
                                        val caps = transport.capabilities(current)
                                        val state = transport.get(current, "/status")
                                        status = "服务器已验证"
                                        serverInfo = "协议 " + caps.optInt("protocol") +
                                            " · " + state.optString("peak_state", "状态未知")
                                    } catch (_: Exception) { status = "连接状态获取失败" }
                                }
                            }
                        ) { Text("刷新服务器状态") }
                    }
                    item {
                        Text("李鱼人格（仅服务端授权可读取）", style = MaterialTheme.typography.titleSmall)
                        FilledTonalButton(enabled = connection != null, onClick = {
                            val current = connection ?: return@FilledTonalButton
                            scope.launch {
                                try {
                                    val caps = transport.capabilities(current)
                                    if (!caps.optBoolean("persona_read")) {
                                        serverInfo = "服务器未授权读取人格"
                                    } else {
                                        val persona = transport.get(current, "/persona")
                                        serverInfo = "服务端人格：" + persona.optString("name", "未知") +
                                            " · 只读 · " + persona.optString("scope", "未验证")
                                    }
                                } catch (_: Exception) { serverInfo = "人格读取失败或未授权" }
                            }
                        }) { Text("检查李鱼人格") }
                    }
                    item {
                        Text("服务器记忆检索", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = memoryQuery, onValueChange = { if (it.length <= 120) memoryQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("检索关键字") },
                            singleLine = true
                        )
                        FilledTonalButton(enabled = connection != null && memoryQuery.isNotBlank(),
                            onClick = {
                                val current = connection ?: return@FilledTonalButton
                                scope.launch {
                                    try {
                                        val caps = transport.capabilities(current)
                                        memoryResult = if (!caps.optBoolean("memory_read")) {
                                            "服务器未启用经所有者验证的只读记忆检索"
                                        } else {
                                            val result = transport.memorySearch(current, memoryQuery)
                                            // Show only counts and ownership metadata; never persist sensitive results.
                                            require(result.optBoolean("read_only") &&
                                                result.optString("scope") == "configured_owner_only") {
                                                "记忆读取未通过所有者权限校验"
                                            }
                                            val items = result.optJSONArray("items")
                                            memoryRows = (0 until (items?.length() ?: 0)).mapNotNull { index ->
                                                val row = items?.optJSONObject(index) ?: return@mapNotNull null
                                                row.optString("id") to row.optString("content")
                                            }.filter { it.first.isNotBlank() }.take(30)
                                            "已返回 " + memoryRows.size + " 条服务器记忆（只读）。当前不会导入或删除。"
                                        }
                                    } catch (_: Exception) { memoryResult = "记忆检索不可用或无权限" }
                                }
                            }) { Text("只读检索") }
                        if (memoryResult.isNotBlank()) Text(memoryResult)
                    }
                    items(memoryRows, key = { "memory_" + it.first }) { row ->
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainer,
                                    RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("记忆 #" + row.first, style = MaterialTheme.typography.labelSmall)
                            Text(row.second.take(2000), style = MaterialTheme.typography.bodyMedium)
                            if (row.second.length > 2000) Text(
                                "内容较长，此处只展示前 2000 字；不会自动同步到本机数据库。",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    item {
                        Text("旧 Mate 与新聊天数据库独立；此页面不会修改 AstrBot 人格、生产记忆或 QQ/飞书/钉钉机器人配置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                if (searchVisible) {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it.take(120) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        singleLine = true,
                        label = { Text("搜索已加载的本机记录") }
                    )
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (messages.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                Text("这里是李鱼的独立聊天记录。首次使用请先完成服务器配对。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (hasOlder && searchText.isBlank()) {
                        item(key = "load_more_mate") {
                            FilledTonalButton(
                                enabled = !loadingOlder && connection != null,
                                onClick = {
                                    val current = connection ?: return@FilledTonalButton
                                    val first = messages.firstOrNull() ?: return@FilledTonalButton
                                    loadingOlder = true
                                    scope.launch {
                                        try {
                                            val older = journal.olderThan(current.instanceId, first.createdAt)
                                            messages = older + messages
                                            hasOlder = older.isNotEmpty() &&
                                                journal.count(current.instanceId) > messages.size
                                        } catch (_: Exception) {
                                            status = "加载旧记录失败"
                                        } finally { loadingOlder = false }
                                    }
                                }
                            ) { Text(if (loadingOlder) "加载中…" else "加载更早记录") }
                        }
                    }
                    items(
                        if (searchText.isBlank()) messages
                        else messages.filter { it.body.contains(searchText, ignoreCase = true) },
                        key = { it.id }
                    ) { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                if (item.role == "me") Arrangement.End else Arrangement.Start
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.87f)
                                    .background(
                                        if (item.role == "me") MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainer,
                                        RoundedCornerShape(18.dp)
                                    )
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = {
                                            if (!busy && item.state !in setOf("running", "uncertain")) {
                                                pendingDeleteId = item.requestId
                                            }
                                        }
                                    )
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (item.role == "bot" && item.state == "done" && item.body.isNotBlank()) {
                                        MarkdownBlock(content = item.body)
                                    } else {
                                        Text(item.body.ifBlank {
                                            if (item.state == "running") "正在回复…" else "暂无回复"
                                        })
                                    }
                                    if (item.role == "bot" && item.state != "done") {
                                        Text(
                                            if (item.state == "uncertain") "结果待核对" else item.state,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (lastRequestId != null) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            enabled = !busy && connection != null,
                            onClick = {
                                val id = lastRequestId ?: return@FilledTonalButton
                                val current = connection ?: return@FilledTonalButton
                                queryExistingRequest(id, current)
                            }
                        ) { Text("查询原请求") }
                        Text("为防重复生成，先确认上一条请求结果", style = MaterialTheme.typography.labelSmall)
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { next ->
                        if (next.length <= 6000) draft = next
                    },
                    enabled = connection != null && !checking,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    label = { Text("发送给李鱼") },
                    minLines = 1,
                    maxLines = 5,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    if (busy) {
                        FilledTonalButton(onClick = {
                            transport.cancelStreaming()
                            activeJob?.cancel()
                        }) {
                            Text("停止等待")
                        }
                    } else {
                        Button(
                            enabled = !checking && connection != null && draft.isNotBlank() &&
                                lastRequestId == null,
                            onClick = { sendMessage() }
                        ) { Text("发送") }
                    }
                }
            }
        }
        if (pendingDeleteId != null) {
            val current = connection
            AlertDialog(
                onDismissRequest = { pendingDeleteId = null },
                title = { Text("仅删除本机消息") },
                text = { Text("将删除这条提问和对应的李鱼回复。不会删除服务器端记录，也不会影响其他设备。") },
                confirmButton = {
                    TextButton(onClick = {
                        val id = pendingDeleteId ?: return@TextButton
                        pendingDeleteId = null
                        if (current != null) scope.launch {
                            try {
                                journal.deletePairLocally(current.instanceId, id)
                                messages = messages.filterNot { it.requestId == id }
                            } catch (_: Exception) { status = "本机删除失败" }
                        }
                    }) { Text("删除本机记录") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteId = null }) { Text("取消") }
                }
            )
        }
    }
}
