package me.rerere.rikkahub.mate

import androidx.compose.foundation.layout.Arrangement
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
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("Mate Android") }
    var paired by remember { mutableStateOf<MateConnection?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("正在读取本机连接…") }
    var restoring by remember { mutableStateOf(true) }

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

    Scaffold(topBar = { TopAppBar(title = { Text("Mate · 连接李鱼") },
        navigationIcon = { BackButton() }) }) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = insets + PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("AstrBot 私人服务器", style = MaterialTheme.typography.titleMedium)
                Text("此连接使用 Mate 网关协议 3，不是普通 OpenAI API。配对不会修改原 AstrBot、QQ 或李鱼记忆。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                OutlinedTextField(
                    value = address, onValueChange = { address = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("HTTPS 网关地址") },
                    placeholder = { Text("https://your-gateway.example") },
                    enabled = !busy
                )
            }
            if (!restoring && paired == null) {
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
                    Button(
                        enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    gateway.verify(paired!!)
                                    message = "连接已验证 · 协议 3"
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    message = "连接检测失败；本机授权已保留"
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
            item {
                Text(
                    "第一阶段：仅设备连接与授权检测。聊天请求、远端记忆和设备管理尚未接入本次 Fork，不会显示虚假的已同步状态。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
