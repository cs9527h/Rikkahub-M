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
    var writeEnabled by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在读取记忆…") }

    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var editText by remember { mutableStateOf("") }
    var editTags by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<MemoryItem?>(null) }
    var creating by remember { mutableStateOf(false) }

    suspend fun connection(): MateConnection? = withContext(Dispatchers.IO) { store.load() }

    suspend fun load(reset: Boolean) {
        loading = true
        try {
            val connection = connection()
            if (connection == null) {
                status = "还没有连接云服务器，请先在「云服务器」完成配对"
                items.clear(); nextAfter = null
                return
            }
            val path = buildString {
                append("/memory?limit=30&deleted=").append(if (showDeleted) "1" else "0")
                if (query.isNotBlank()) append("&q=").append(java.net.URLEncoder.encode(query, "UTF-8"))
                if (typeFilter.isNotBlank()) append("&type=").append(typeFilter)
                if (!reset && nextAfter != null) append("&after=").append(nextAfter)
            }
            val body = transport.get(connection, path)
            if (reset) items.clear()
            writeEnabled = body.optBoolean("write_enabled", false)
            val list = body.optJSONArray("items") ?: JSONArray()
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
            status = "读取失败：" + (error.message ?: "未知错误")
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
            snackbar.showSnackbar(error.message ?: "操作失败")
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
                    TextButton(enabled = !loading, onClick = { showDeleted = !showDeleted; scope.launch { load(true) } }) {
                        Text(if (showDeleted) "显示正常记忆" else "查看回收站")
                    }
                    if (writeEnabled) {
                        TextButton(onClick = { creating = true }) { Text("新增") }
                    }
                }
            }
            item {
                Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!writeEnabled) {
                item {
                    Text(
                        "服务器未开启记忆编辑（APP_MEMORY_WRITE_ENABLED）。当前只能查看与搜索。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            items(items, key = { it.id }) { item ->
                MemoryRow(
                    item = item,
                    canWrite = writeEnabled,
                    onEdit = { editing = item; editText = item.content; editTags = item.tags },
                    onDelete = { pendingDelete = item },
                    onRestore = { scope.launch { sendAction(JSONObject().put("action", "restore").put("id", item.id), "已恢复") } },
                )
            }
            if (nextAfter != null) {
                item {
                    TextButton(onClick = { scope.launch { load(false) } }, enabled = !loading) { Text("加载更多") }
                }
            }
        }
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
        if (canWrite) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit) { Text("编辑") }
                if (item.active) TextButton(onClick = onDelete) { Text("删除") } else TextButton(onClick = onRestore) { Text("恢复") }
            }
        }
    }
}
