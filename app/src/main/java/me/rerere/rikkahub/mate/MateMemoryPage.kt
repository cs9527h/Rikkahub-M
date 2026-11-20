package me.rerere.rikkahub.mate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.json.JSONArray
import org.json.JSONObject

/**
 * 服务器长期记忆管理。
 *
 * 所有数据都来自网关 /memory 接口：写入关闭时只读展示，并对每个写操作给出服务器原话的错误，
 * 不做本地假成功。删除是软删除，可恢复；列表可切换显示回收站。
 */
private data class AuditItem(
    val seq: Long,
    val memoryId: String,
    val action: String,
    val actor: String,
    val at: Long,
)

private data class MemoryItem(
    val id: String,
    val content: String,
    val type: String,
    val tags: String,
    val importance: String,
    val active: Boolean,
)

@Composable
fun MateMemoryPage() {
    val context = LocalContext.current
    val store = remember(context) { MateGatewayStore(context) }
    val transport = remember { MateChatTransport() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val items = remember { mutableStateListOf<MemoryItem>() }
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf("") }
    var showDeleted by remember { mutableStateOf(false) }
    var nextAfter by remember { mutableStateOf<String?>(null) }
    // Null = not yet verified; read failures must never imply write-disabled.
    var writeEnabled by remember { mutableStateOf<Boolean?>(null) }
    var memoryReady by remember { mutableStateOf(false) }
    var gatewayInfo by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在读取记忆…") }

    var detail by remember { mutableStateOf<MemoryItem?>(null) }
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var editText by remember { mutableStateOf("") }
    var editTags by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<MemoryItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var bulkConfirm by remember { mutableStateOf(false) }
    var showAudit by remember { mutableStateOf(false) }
    val auditItems = remember { mutableStateListOf<AuditItem>() }

    suspend fun connection(): MateConnection? = withContext(Dispatchers.IO) { store.load() }

    suspend fun loadAudit() {
        if (!memoryReady) return
        loading = true
        try {
            val connection = connection() ?: run {
                status = "还没有连接云服务器，请先在「云服务器」完成配对"
                auditItems.clear()
                return
            }
            auditItems.clear()
            val body = transport.get(connection, "/memory/audit?limit=40")
            val list = body.optJSONArray("items") ?: JSONArray()
            for (index in 0 until list.length()) {
                val row = list.optJSONObject(index) ?: continue
                auditItems += AuditItem(
                    seq = row.optLong("seq"),
                    memoryId = row.optString("memory_id"),
                    action = row.optString("action"),
                    actor = row.optString("actor"),
                    at = row.optLong("at"),
                )
            }
            status = if (auditItems.isEmpty()) "还没有操作记录" else "共 " + auditItems.size + " 条操作记录"
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            status = "读取审计失败：" + humanizeMemoryError(error.message)
        } finally {
            loading = false
        }
    }

    suspend fun load(reset: Boolean) {
        loading = true
        if (reset) {
            selected.clear()
            nextAfter = null
            writeEnabled = null
            memoryReady = false
            gatewayInfo = ""
        }
        try {
            val connection = connection()
            if (connection == null) {
                status = "还没有连接云服务器，请先在「云服务器」完成配对"
                return
            }
            // Query the authenticated gateway contract, not chat connectivity.
            val caps = transport.capabilities(connection)
            gatewayInfo = "网关版本：" + caps.optString("version", "未知") +
                " · 长期记忆：" + (if (caps.optBoolean("memory_read", false)) "已接入" else "未接入")
            if (!caps.optBoolean("memory_browser", caps.optBoolean("memory_read", false)) && !caps.optBoolean("memory_read", false)) {
                status = "服务端尚未接入长期记忆。需要部署新版网关并验证记忆库及用户归属。"
                return
            }
            // New gateways advertise this separately from legacy search-only recall.
            if (caps.has("memory_browser") && !caps.optBoolean("memory_browser", false)) {
                status = "服务端未配置记忆中心。请先核对记忆数据库、用户归属及网关权限。"
                return
            }
            val path = buildString {
                append("/memory?limit=30&deleted=").append(if (showDeleted) "1" else "0")
                if (query.isNotBlank()) append("&q=").append(java.net.URLEncoder.encode(query, "UTF-8"))
                if (typeFilter.isNotBlank()) append("&type=").append(typeFilter)
                if (!reset && nextAfter != null) append("&after=").append(nextAfter)
            }
            val body = transport.get(connection, path)
            // Old/invalid schemas must not masquerade as an empty successful list.
            if (!body.has("write_enabled") || body.optJSONArray("items") == null) {
                throw java.io.IOException("memory_list_invalid")
            }
            writeEnabled = body.optBoolean("write_enabled", false) && caps.optBoolean("memory_write", true)
            memoryReady = true
            val list = body.optJSONArray("items") ?: JSONArray()
            if (reset) items.clear()
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val memory = item.toMemoryItem()
                if (!showDeleted || !memory.active) items += memory
            }
            nextAfter = body.optString("next_after").takeIf { it.isNotBlank() && it != "null" }
            status = if (items.isEmpty()) "没有匹配的记忆" else "共加载 " + items.size + " 条"
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            memoryReady = false
            writeEnabled = null
            status = "读取失败：" + humanizeMemoryError(error.message)
        } finally {
            loading = false
        }
    }

    suspend fun sendAction(body: JSONObject, success: String) {
        try {
            val connection = connection() ?: throw IllegalStateException("还没有连接云服务器")
            val result = transport.post(connection, "/memory", body)
            snackbar.showSnackbar(
                if (result.optBoolean("needs_reindex", false)) success + "（向量索引需服务器重建）" else success
            )
            load(true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            snackbar.showSnackbar(humanizeMemoryError(error.message))
        }
    }

    LaunchedEffect(Unit) { load(true) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("记忆中心") }, navigationIcon = { BackButton() }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索记忆") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("" to "全部", "preference" to "偏好", "fact" to "事实", "event" to "经历").forEach { (value, label) ->
                        FilterChip(
                            selected = typeFilter == value,
                            onClick = { typeFilter = value; scope.launch { load(true) } }, enabled = !loading,
                            label = { Text(label) },
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { scope.launch { load(true) } }, enabled = !loading) {
                        Text(if (loading) "读取中…" else "搜索")
                    }
                    TextButton(enabled = memoryReady && !loading, onClick = {
                        showDeleted = !showDeleted
                        showAudit = false
                        scope.launch { load(true) }
                    }) {
                        Text(if (showDeleted) "显示正常记忆" else "查看回收站")
                    }
                    TextButton(enabled = memoryReady && !loading, onClick = {
                        showAudit = !showAudit
                        if (showAudit) scope.launch { loadAudit() } else scope.launch { load(true) }
                    }) {
                        Text(if (showAudit) "返回记忆" else "操作审计")
                    }
                    if (memoryReady && writeEnabled == true && !showAudit) {
                        TextButton(onClick = { creating = true }) { Text("新增") }
                    }
                }
            }
            if (gatewayInfo.isNotBlank()) {
                item {
                    Text(gatewayInfo, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Text(status, style = MaterialTheme.typography.bodySmall,
                    color = if (!memoryReady && !loading) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (memoryReady && writeEnabled == true && !showAudit && selected.isNotEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("已选 " + selected.size + " 条", style = MaterialTheme.typography.labelLarge)
                        TextButton(enabled = !loading, onClick = { selected.clear() }) { Text("取消选择") }
                        TextButton(enabled = !loading, onClick = { bulkConfirm = true }) { Text("删除所选") }
                    }
                }
            }
            if (memoryReady && writeEnabled == false) {
                item {
                    Text(
                        "记忆中心当前为只读模式。需要在服务器核实身份归属后开启写入权限。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (showAudit) {
                items(auditItems, key = { it.seq }) { row ->
                    AuditRow(row)
                }
            } else {
                items(items, key = { it.id }) { item ->
                    MemoryRow(
                        item = item,
                        canWrite = memoryReady && writeEnabled == true,
                        checked = selected.contains(item.id),
                        onToggle = {
                            if (selected.contains(item.id)) selected.remove(item.id) else selected.add(item.id)
                        },
                        onDetail = { scope.launch {
                            try {
                                val paired = connection() ?: throw IllegalStateException("请先配对云服务器")
                                val id = java.net.URLEncoder.encode(item.id, "UTF-8")
                                detail = transport.get(paired, "/memory/item?id=$id").toMemoryItem()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                snackbar.showSnackbar(humanizeMemoryError(error.message))
                            }
                        } },
                        onEdit = { editing = item; editText = item.content; editTags = item.tags },
                        onDelete = { pendingDelete = item },
                        onRestore = { scope.launch { sendAction(JSONObject().put("action", "restore").put("id", item.id), "已恢复") } },
                    )
                }
            }
            if (memoryReady && nextAfter != null) {
                item {
                    TextButton(onClick = { scope.launch { load(false) } }, enabled = !loading) { Text("加载更多") }
                }
            }
        }
    }

    detail?.let { record ->
        AlertDialog(onDismissRequest = { detail = null }, title = { Text("记忆详情") },
            text = { Text(record.content) },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } })
    }

    if (bulkConfirm) {
        val ids = selected.toList()
        AlertDialog(
            onDismissRequest = { bulkConfirm = false },
            title = { Text("删除选中的 " + ids.size + " 条记忆？") },
            text = { Text("服务器会一次性软删除；任何一条失败都会整体回滚，不会出现删一半。删除后可在回收站恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    bulkConfirm = false
                    val body = JSONObject()
                        .put("action", "delete")
                        .put("ids", JSONArray(ids))
                        .put("confirm", true)
                    selected.clear()
                    scope.launch { sendAction(body, "已批量删除 " + ids.size + " 条") }
                }) { Text("全部删除") }
            },
            dismissButton = { TextButton(onClick = { bulkConfirm = false }) { Text("取消") } },
        )
    }

    editing?.let { item ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("编辑记忆 #" + item.id) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = editText, onValueChange = { editText = it }, label = { Text("内容") }, minLines = 3)
                    OutlinedTextField(value = editTags, onValueChange = { editTags = it }, label = { Text("标签（可选）") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val body = JSONObject()
                        .put("action", "update")
                        .put("id", item.id)
                        .put("content", editText)
                        .put("tags", editTags)
                        // 带上原始正文，服务器可发现另一端已改过并拒绝覆盖
                        .put("expect_content", item.content)
                    editing = null
                    scope.launch { sendAction(body, "已保存") }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条记忆？") },
            text = { Text(item.content.take(120) + "\n\n删除后可在这里的回收站恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        sendAction(
                            JSONObject().put("action", "delete").put("id", item.id).put("confirm", true),
                            "已删除",
                        )
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    if (creating) {
        MateCreateMemoryDialog("", onDismiss = { creating = false }, onSaved = {
            creating = false
            scope.launch { load(true) }
        })
    }
}

@Composable
internal fun MateCreateMemoryDialog(initial: String, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { MateGatewayStore(context) }
    val transport = remember { MateChatTransport() }
    var text by remember { mutableStateOf(initial) }
    var kind by remember { mutableStateOf("fact") }
    var destination by remember { mutableStateOf<MateConnection?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在读取云服务器连接…") }
    LaunchedEffect(Unit) {
        val connection = withContext(Dispatchers.IO) { store.load() }
        destination = connection
        status = connection?.let { "保存到 " + android.net.Uri.parse(it.baseUrl).host } ?: "请先在云服务器设置中完成配对"
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("保存为长期记忆") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(status, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = text, onValueChange = { text = it }, enabled = !busy,
                    label = { Text("确认要记住的内容") }, minLines = 2, maxLines = 6,
                    supportingText = { Text("${text.length} / 6000") })
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("fact" to "事实", "preference" to "偏好", "event" to "经历").forEach { (value, label) ->
                        FilterChip(selected = value == kind, onClick = { kind = value }, enabled = !busy, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && destination != null && text.isNotBlank() && text.length <= 6000, onClick = {
                val connection = destination ?: return@TextButton
                busy = true
                scope.launch {
                    try {
                        val current = withContext(Dispatchers.IO) { store.load() }
                        check(current == connection) { "连接已更换，请重新打开" }
                        val reply = transport.post(connection, "/memory", JSONObject().put("action", "create")
                            .put("type", kind).put("content", text.trim()))
                        check(reply.optString("id").isNotBlank()) { "服务器未确认写入" }
                        android.widget.Toast.makeText(context, "服务器已保存这条记忆", android.widget.Toast.LENGTH_SHORT).show()
                        onSaved()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        status = error.message ?: "保存失败"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "正在保存…" else "保存") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}

private fun JSONObject.toMemoryItem(): MemoryItem {
    val active = when {
        has("is_active") -> optInt("is_active", 1) == 1
        has("status") -> optString("status") in setOf("active", "verified")
        else -> true
    }
    return MemoryItem(
        id = optString("id"),
        content = optString("content"),
        type = optString("type"),
        tags = optString("tags"),
        importance = optString("importance"),
        active = active,
    )
}

@Composable
private fun MemoryRow(
    item: MemoryItem,
    canWrite: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onDetail: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(item.content, style = MaterialTheme.typography.bodyMedium)
        Text(
            "#" + item.id + " · " + item.type + (if (item.tags.isBlank()) "" else " · " + item.tags) +
                (if (item.active) "" else " · 已删除"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onDetail) { Text("详情") }
        if (canWrite) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.active) {
                    Checkbox(checked = checked, onCheckedChange = { onToggle() })
                }
                TextButton(onClick = onEdit) { Text("编辑") }
                if (item.active) TextButton(onClick = onDelete) { Text("删除") } else TextButton(onClick = onRestore) { Text("恢复") }
            }
        }
    }
}

@Composable
private fun AuditRow(row: AuditItem) {
    val action = when (row.action) {
        "create" -> "新增"
        "update" -> "修改"
        "delete" -> "删除"
        "restore" -> "恢复"
        else -> row.action
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        Text(action + " · 记忆 #" + row.memoryId, style = MaterialTheme.typography.bodyMedium)
        Text(
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(row.at * 1000)) + " · 设备 " + row.actor.takeLast(6),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 把网关错误码翻成人话；认不出的原样显示，绝不假装成功。 */
internal fun humanizeMemoryError(raw: String?): String {
    val text = raw.orEmpty()
    val table = mapOf(
        "memory_write_disabled" to "服务器目前未授权记忆写入，请在服务端核实后开启",
        "memory_list_invalid" to "服务端记忆列表格式不兼容，需要更新网关",
        "memory_not_configured" to "服务器还没配置记忆库路径",
        "memory_unavailable" to "服务器记忆库暂时打不开，请稍后再试",
        "memory_not_found" to "这条记忆在服务器上已不存在",
        "memory_changed_elsewhere" to "另一端已经改过这条记忆，请重新读取后再编辑",
        "memory_rate_limited" to "操作太频繁，请稍后再试",
        "confirmation_required" to "服务器要求二次确认（confirm）",
        "nothing_to_update" to "没有需要保存的改动",
        "invalid_memory_type" to "记忆类型不合法",
        "invalid_memory_ids" to "要删除的记忆列表不合法",
        "invalid_importance" to "重要度必须是 0~1 之间的数字",
        "invalid_memory_query" to "查询条件不合法",
        "ambiguous_memory_index_owner" to "这条记忆的索引归属不明确，已拒绝删除以防误删他人内容",
        "device_authorization_required" to "这台设备还没有通过服务器授权",
    )
    table.forEach { (code, message) -> if (text.contains(code)) return message }
    if (text.contains("HTTP 404")) return "网关缺少记忆中心接口（HTTP 404），请更新服务器网关并核对反向代理路径"
    if (text.contains("HTTP 401") || text.contains("HTTP 403")) return "服务器拒绝访问，请检查设备配对与记忆权限"
    if (text.contains("HTTP 503")) return "服务器记忆服务尚未就绪（HTTP 503），请检查部署及数据库"
    return if (text.isBlank()) "操作失败，服务器没有返回原因" else text
}
