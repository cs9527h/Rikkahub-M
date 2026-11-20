package me.rerere.rikkahub.mate

import androidx.compose.foundation.layout.Arrangement
import android.net.Uri
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
    var deviceName by remember { mutableStateOf("Mate Android") }
    var paired by remember { mutableStateOf<MateConnection?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("正在读取本机连接…") }
    var restoring by remember { mutableStateOf(true) }
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
                    val uri = Uri.parse(data)
                    require(uri.scheme == "mate" && uri.host == "pair") { "不是 Mate 配对码" }
                    val safeBase = MateGatewayAddress.normalize(
                        uri.getQueryParameter("server") ?: error("二维码缺少服务器地址")
                    )
                    val parsedCode = uri.getQueryParameter("code") ?: error("二维码缺少配对码")
                    require(parsedCode.length in 24..80 && parsedCode.all { it.code in 33..126 })
                    address = safeBase
                    code = parsedCode
                    message = "已读取一次性二维码，请核对服务器地址并点击配对"
                } catch (_: Exception) {
                    message = "二维码不是有效的 Mate 一次性配对格式"
                }
            },
            onPermissionDenied = {
                showScanner = false
                message = "没有摄像头权限，可手动输入服务器地址与配对码"
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
            }
            item {
                OutlinedTextField(
                    value = address, onValueChange = { address = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("服务器地址（HTTPS）") },
                    placeholder = { Text("https://your-gateway.example") },
                    enabled = !busy
                )
            }
            if (!restoring && paired == null) {
                item {
                    TextButton(
                        enabled = !busy, onClick = { showScanner = true }
                    ) { Text("扫码导入设备配对信息") }
                }
                item {
                    OutlinedTextField(
                        value = code, onValueChange = { code = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("一次性配对码") },
                        enabled = !busy,
                        supportingText = { Text("请在已授权的服务端生成；本页不会保存配对码") }
                    )
                }
                item {
                    OutlinedTextField(
                        value = deviceName, onValueChange = { deviceName = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("本机名称") }, enabled = !busy
                    )
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
                    ) { Text(if (busy) "正在连接…" else "配对并连接") }
                }
            } else if (!restoring) {
                item {
                    val instance = paired?.instanceId.orEmpty()
                    var enabled by remember(instance) { mutableStateOf(MateDiagnostics.allowed(context, instance)) }
                    Column {
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
                                    gateway.verify(paired!!)
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
