package me.rerere.rikkahub.mate

import androidx.compose.foundation.layout.Arrangement
import android.net.Uri
import android.content.ClipboardManager
import android.content.Context
import org.json.JSONObject
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.QRCodeScannerDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.utils.plus

/**
 * Deliberately separate from provider configuration: protocol-v3 is not an
 * OpenAI-compatible endpoint. This page is available even on an empty install.
 */
@Composable
fun MateConnectionPage() {
    val context = LocalContext.current
    val store = remember(context) { MateGatewayStore(context) }
    val gateway = remember { MateGatewayClient() }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val navController = LocalNavController.current
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var deviceName by remember {
        mutableStateOf(android.os.Build.MODEL?.trim()?.take(40)?.ifBlank { "Android 手机" } ?: "Android 手机")
    }
    var showManualFields by remember { mutableStateOf(false) }
    var paired by remember { mutableStateOf<MateConnection?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("正在读取本机连接…") }
    var restoring by remember { mutableStateOf(true) }
    var capabilitySummary by remember { mutableStateOf("服务器能力尚未验证") }
    var showScanner by remember { mutableStateOf(false) }

    val diagnosticPrefs = remember(context) { MateDiagnostics.preferences(context) }
    var diagnosticResult by remember { mutableStateOf(diagnosticPrefs.getString(MateDiagnostics.RESULT, "").orEmpty()) }
    androidx.compose.runtime.DisposableEffect(diagnosticPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == MateDiagnostics.RESULT) diagnosticResult = prefs.getString(key, "").orEmpty()
        }
        diagnosticPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { diagnosticPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    if (showScanner) {
        QRCodeScannerDialog(
            onResult = { data ->
                showScanner = false
                try {
                    val (safeBase, parsedCode) = parseMatePairingLink(data)
                    address = safeBase
                    code = parsedCode
                    showManualFields = false
                    message = "已导入授权信息，确认服务器：" + Uri.parse(safeBase).host
                } catch (_: Exception) {
                    message = "二维码不是有效的 Mate 一次性配对格式"
                }
            },
            onPermissionDenied = {
                showScanner = false
                message = "没有摄像头权限，可从剪贴板导入或手动填写"
            },
            onDismissRequest = { showScanner = false },
        )
    }

    LaunchedEffect(Unit) {
        try {
            val current = withContext(Dispatchers.IO) { store.load() }
            address = withContext(Dispatchers.IO) { current?.baseUrl ?: store.savedAddress() }
            paired = current
            message = if (current == null) "尚未连接服务器" else "已保存授权，正在验证连接"
            if (current != null) {
                try {
                    gateway.verify(current)
                    val caps = MateChatTransport().capabilities(current)
                    capabilitySummary = describeMateCapabilities(caps)
                    message = "连接已验证 · 协议 3"
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    message = "已保存授权，但当前无法验证连接；可重试，不会清除聊天记录"
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            message = "读取本机授权失败；请检查设备密钥与备份恢复状态"
        } finally {
            restoring = false
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("云服务器连接") }, navigationIcon = { BackButton() }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = insets + PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("AstrBot 私人服务器", style = MaterialTheme.typography.titleMedium)
                Text("独立连接云服务器，不会修改原有机器人或服务器记忆。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (paired != null) Text(capabilitySummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!restoring && paired == null) {
                item {
                    Button(
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showScanner = true },
                    ) { Text("扫码连接服务器") }
                }
                item {
                    TextButton(
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            try {
                                // Read only after a user gesture; never log or retain the clipboard content.
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val link = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                val (server, oneTimeCode) = parseMatePairingLink(link)
                                address = server
                                code = oneTimeCode
                                showManualFields = false
                                message = "已导入授权信息，确认服务器：" + Uri.parse(server).host
                            } catch (_: Exception) {
                                message = "剪贴板中没有有效的配对链接，可扫码或选择手动输入"
                            }
                        },
                    ) { Text("同一手机？从剪贴板导入配对链接") }
                }
                item {
                    TextButton(
                        enabled = !busy,
                        onClick = { showManualFields = !showManualFields },
                    ) { Text(if (showManualFields) "收起手动填写" else "手动输入服务器地址及配对码") }
                }
                if (showManualFields) {
                    item {
                        OutlinedTextField(
                            value = address, onValueChange = { address = it },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("服务器地址（HTTPS）") },
                            placeholder = { Text("https://your-gateway.example") },
                            enabled = !busy,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = code, onValueChange = { code = it },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            label = { Text("一次性配对码") },
                            enabled = !busy,
                            supportingText = { Text("配对成功后不会保留一次性配对码") },
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = deviceName, onValueChange = { deviceName = it },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("本机名称") }, enabled = !busy,
                        )
                    }
                }
                if (code.isNotBlank() && address.isNotBlank()) {
                    item {
                        Text(
                            "即将连接：" + (Uri.parse(address).host ?: "未知服务器") +
                                " · 设备：" + deviceName,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && code.trim().length in 24..80 && address.isNotBlank() &&
                            deviceName.trim().isNotBlank(),
                        onClick = {
                            busy = true
                            message = "正在配对并验证…"
                            scope.launch {
                                try {
                                    val connection = gateway.pair(address, code, deviceName)
                                    withContext(Dispatchers.IO) { store.save(connection) }
                                    paired = connection
                                    code = ""
                                    message = "配对成功 · 连接已验证"
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    message = error.message ?: "配对失败，请检查网关与配对码"
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    ) { Text(if (busy) "正在连接…" else "确认并连接") }
                }
            } else if (!restoring) {
                item {
                    val instance = paired?.instanceId.orEmpty()
                    var enabled by remember(instance) { mutableStateOf(MateDiagnostics.allowed(context, instance)) }
                    Column {
                        val turnKey = "turn_" + instance
                        var summary by remember(instance) { mutableStateOf(diagnosticPrefs.getString(turnKey, "").orEmpty()) }
                        androidx.compose.runtime.DisposableEffect(instance) {
                            val observer = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                                if (key == turnKey) summary = prefs.getString(key, "").orEmpty()
                            }
                            diagnosticPrefs.registerOnSharedPreferenceChangeListener(observer)
                            onDispose { diagnosticPrefs.unregisterOnSharedPreferenceChangeListener(observer) }
                        }
                        if (summary.isNotBlank()) Text(summary)
                        var requestStatus by remember(instance) { mutableStateOf("") }
                        var checking by remember(instance) { mutableStateOf(false) }
                        TextButton(enabled = !checking, onClick = {
                            val current = paired ?: return@TextButton
                            val id = diagnosticPrefs.getString("request_" + instance, null)
                            if (id == null) {
                                requestStatus = "还没有已接收的请求"
                            } else {
                                checking = true
                                scope.launch {
                                    try {
                                        val snapshot = MateChatTransport().requestResult(current, id)
                                        requestStatus = when (snapshot.state) {
                                            "running" -> "服务器仍在生成"
                                            "done" -> "服务器已完成这次请求"
                                            "cancelled" -> "请求已取消"
                                            "expired" -> "请求记录已过期"
                                            "uncertain" -> "服务器无法确认最终结果"
                                            else -> "服务器报告生成失败"
                                        }
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        requestStatus = "查询失败，请检查服务器连接"
                                    } finally {
                                        checking = false
                                    }
                                }
                            }
                        }) { Text("查询最近一次请求") }
                        if (requestStatus.isNotBlank()) Text(requestStatus)
                        Text("自动诊断", style = MaterialTheme.typography.titleMedium)
                        Text("允许向当前云服务器发送版本、Android 版本和错误类别。聊天正文、设备令牌和异常原文不会上传。")
                        androidx.compose.material3.Switch(checked = enabled, onCheckedChange = {
                            MateDiagnostics.setConsent(context, instance, it)
                            enabled = it
                        })
                        if (enabled) {
                            TextButton(onClick = { MateDiagnostics.enqueue(context, DiagnosticEvent.MANUAL_CHECK) }) {
                                Text("发送诊断（每小时一次）")
                            }
                            if (diagnosticResult.isNotBlank()) Text(diagnosticResult)
                        }
                    }
                }
                item {
                    Button(
                        enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        onClick = { navController.navigate(Screen.MateDevices) }
                    ) { Text("设备管理 · 生成配对二维码") }
                }
                item {
                    Button(
                        enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val current = paired!!
                                    gateway.verify(current)
                                    capabilitySummary = describeMateCapabilities(MateChatTransport().capabilities(current))
                                    message = "服务器连接正常 · 授权有效"
                                    snackbarHostState.showSnackbar(message)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    message = when (error) {
                                        is java.net.UnknownHostException -> "无法解析服务器地址"
                                        is java.net.SocketTimeoutException -> "连接超时，请检查网络或服务器"
                                        is javax.net.ssl.SSLException -> "HTTPS 证书验证失败"
                                        else -> "服务器验证失败，请检查连接及设备授权"
                                    }
                                    snackbarHostState.showSnackbar(message)
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    ) { Text(if (busy) "正在检测…" else "检测服务器连接") }
                }
                item {
                    TextButton(
                        enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { store.forgetLocal() }
                                    paired = null
                                    code = ""
                                    message = "本机授权已移除；服务端设备授权仍需在后台撤销"
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    message = error.message ?: "移除失败"
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    ) { Text("移除本机授权并重新配对") }
                }
            }
        }
    }
}

/** Capability availability is not proof of successful execution; keep UI wording precise. */
internal fun describeMateCapabilities(caps: JSONObject): String {
    fun available(key: String): String = if (caps.optBoolean(key, false)) "支持" else "不可用"
    return "网关 " + caps.optString("version", "未知") +
        " · 长期记忆：" + available("memory_browser") +
        " · 记忆修改：" + available("memory_write") +
        " · 云端规则：" + available("ordered_context_v1") +
        " · 视频上传：" + available("video_upload") +
        "\n以上是服务器声明的功能能力，不代表实际操作已验收。"
}

/**
 * Parse a one-time invitation link without loading a web page or sending it elsewhere.
 * Both the QR code and same-phone clipboard flow use the same validated format.
 */
internal fun parseMatePairingLink(raw: String): Pair<String, String> {
    val uri = java.net.URI(raw.trim())
    require(uri.scheme == "mate" && uri.host == "pair") { "不是服务器配对链接" }
    val parts = uri.rawQuery?.split("&").orEmpty()
    require(parts.size == 2) { "配对信息不完整" }
    val values = parts.map { field ->
        val pair = field.split("=", limit = 2)
        require(pair.size == 2) { "配对信息格式错误" }
        pair[0] to java.net.URLDecoder.decode(pair[1], "UTF-8")
    }
    require(values.map { it.first }.toSet() == setOf("server", "code")) { "配对字段错误" }
    val server = MateGatewayAddress.normalize(values.first { it.first == "server" }.second)
    val code = values.first { it.first == "code" }.second
    require(code.length in 24..80 && code.all { it.code in 33..126 }) { "配对码格式不正确" }
    return server to code
}
