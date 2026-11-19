package me.rerere.rikkahub.mate

import androidx.compose.foundation.background
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.ui.components.nav.BackButton
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
    var toolsVisible by remember { mutableStateOf(false) }

    DisposableEffect(journal) { onDispose { journal.close() } }

    LaunchedEffect(Unit) {
        try {
            val saved = withContext(Dispatchers.IO) { credentials.load() }
            if (saved == null) {
                status = "尚未配对，请先进入设置 → 连接李鱼服务器"
                return@LaunchedEffect
            }
            connection = saved
            messages = journal.recent(saved.instanceId)
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

    // The scroll index is updated only when the message count changes.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && !listState.isScrollInProgress) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    fun refreshLocal(instance: String) {
        scope.launch { messages = journal.recent(instance) }
    }

    fun queryExistingRequest(id: String, current: MateConnection) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                transport.capabilities(current)
                val snapshot = transport.requestResult(current, id)
                journal.updateReply(current.instanceId, id, snapshot.text, snapshot.state)
                messages = journal.recent(current.instanceId)
                status = when (snapshot.state) {
                    "done" -> "原请求已经完成"
                    "running" -> "原请求仍在服务器处理中；请稍后查询"
                    else -> "原请求状态：" + snapshot.state + "（不会自动重新发送）"
                }
                if (snapshot.state in listOf("done", "expired", "failed", "uncertain", "error")) {
                    lastRequestId = if (snapshot.state == "uncertain") id else null
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
            try {
                // Verify server identity before accepting a new request.
                val caps = transport.capabilities(current)
                if (!caps.optBoolean("text")) error("服务端未提供文字聊天")
                journal.savePair(current.instanceId, id, body)
                journal.saveDraft(current.instanceId, "")
                messages = journal.recent(current.instanceId)
                draft = ""
                lastRequestId = id
                status = "李鱼正在回复…"
                var latestText = ""
                var latestState = "running"
                var shownAt = 0L
                transport.stream(current, id, body) { snapshot ->
                    latestText = snapshot.text
                    latestState = snapshot.state
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (snapshot.terminal || now - shownAt >= 120L) {
                        journal.updateReply(current.instanceId, id, latestText, latestState)
                        messages = journal.recent(current.instanceId)
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
                        journal.updateReply(current.instanceId, id,
                            journal.recent(current.instanceId).lastOrNull { it.id == id + "_reply" }?.body.orEmpty(),
                            "uncertain")
                    }
                    messages = journal.recent(current.instanceId)
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
                                            val count = result.optJSONArray("items")?.length() ?: 0
                                            "服务器已响应：本页返回 " + count + " 条。当前首版暂不导入或改写私人记忆。"
                                        }
                                    } catch (_: Exception) { memoryResult = "记忆检索不可用或无权限" }
                                }
                            }) { Text("只读检索") }
                        if (memoryResult.isNotBlank()) Text(memoryResult)
                    }
                    item {
                        Text("旧 Mate 与新聊天数据库独立；此页面不会修改 AstrBot 人格、生产记忆或 QQ/飞书/钉钉机器人配置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
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
                    items(messages, key = { it.id }) { item ->
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
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(item.body.ifBlank { if (item.state == "running") "正在回复…" else "暂无回复" })
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
                        if (next.length <= 6000) {
                            draft = next
                            connection?.let { current ->
                                scope.launch {
                                    delay(400)
                                    if (draft == next) journal.saveDraft(current.instanceId, next)
                                }
                            }
                        }
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
                        FilledTonalButton(onClick = { activeJob?.cancel() }) {
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
    }
}
