package me.rerere.rikkahub.mate

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.utils.plus
import org.json.JSONObject

internal data class MateDevice(
    val id: String,
    val label: String,
    val revoked: Boolean,
    val isCurrent: Boolean
)

internal object MateInviteCode {
    fun url(baseUrl: String, code: String): String {
        require(code.length in 24..80)
        val base = MateGatewayAddress.normalize(baseUrl)
        val safeBase = java.net.URLEncoder.encode(base, "UTF-8")
        val safeCode = java.net.URLEncoder.encode(code, "UTF-8")
        return "mate://pair?server=$safeBase&code=$safeCode"
    }
}

@Composable
fun MateDevicesPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { MateGatewayStore(context) }
    val transport = remember { MateChatTransport() }
    var connection by remember { mutableStateOf<MateConnection?>(null) }
    var devices by remember { mutableStateOf<List<MateDevice>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在读取设备授权…") }
    var inviteCode by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var revokeTarget by remember { mutableStateOf<MateDevice?>(null) }

    suspend fun refresh(current: MateConnection) {
        val result = transport.devices(current)
        val currentId = result.optString("current_device")
        val source = result.optJSONArray("devices")
        devices = buildList {
            for (i in 0 until (source?.length() ?: 0)) {
                val row = source?.optJSONObject(i) ?: continue
                val id = row.optString("id")
                if (!id.matches(Regex("[0-9a-f]{24}")) || row.optBoolean("revoked")) continue
                add(MateDevice(
                    id, row.optString("label").take(40), row.optBoolean("revoked"),
                    id == currentId
                ))
            }
        }
        status = "已读取 " + devices.size + " 台设备的授权状态"
    }

    LaunchedEffect(Unit) {
        try {
            val current = withContext(Dispatchers.IO) { store.load() }
            if (current == null) {
                status = "请先在设置中配对当前设备"
                return@LaunchedEffect
            }
            connection = current
            refresh(current)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            status = "无法读取设备列表，未更改服务端授权"
        }
    }

    LaunchedEffect(inviteCode) {
        if (inviteCode.isNotBlank()) {
            delay(600_000)
            inviteCode = ""
            qrBitmap = null
            status = "上一个配对码已过期，请重新生成"
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("设备管理") }, navigationIcon = { BackButton() })
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                Text("邀请新设备", style = MaterialTheme.typography.titleMedium)
                Text("只有已配对的设备才能生成新的十分钟一次性码。扫码仅传递服务器地址和临时代码，不包含已保存的设备 Token。")
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && connection != null,
                    onClick = {
                        val current = connection ?: return@Button
                        busy = true
                        scope.launch {
                            try {
                                val result = transport.createInvite(current)
                                val code = result.getString("code")
                                val qrPayload = MateInviteCode.url(current.baseUrl, code)
                                val bitmap = withContext(Dispatchers.Default) {
                                    val bits = QRCodeWriter().encode(qrPayload, BarcodeFormat.QR_CODE, 300, 300)
                                    val pixels = IntArray(300 * 300)
                                    for (y in 0 until 300) for (x in 0 until 300) {
                                        pixels[y * 300 + x] =
                                            if (bits[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                                    }
                                    Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply {
                                        setPixels(pixels, 0, 300, 0, 0, 300, 300)
                                    }
                                }
                                inviteCode = code
                                qrBitmap = bitmap
                                status = "配对码已生成，有效期十分钟且只能使用一次"
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                // 提供不包含授权凭据的错误分类
                                status = when (error) {
                                    is java.net.UnknownHostException -> "服务器地址无法解析"
                                    is java.net.SocketTimeoutException -> "服务器连接超时"
                                    is javax.net.ssl.SSLException -> "HTTPS 证书验证失败"
                                    is java.io.IOException -> when {
                                        error.message?.contains("HTTP 404") == true -> "服务器不支持设备邀请接口"
                                        error.message?.contains("HTTP 401") == true -> "设备授权失效，请重新配对"
                                        error.message?.contains("HTTP 403") == true -> "当前设备没有邀请权限"
                                        else -> "生成失败：服务器未返回有效配对信息"
                                    }
                                    else -> "二维码生成失败，请检查网关设备邀请功能"
                                }
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text("生成一次性配对二维码") }
            }
            if (inviteCode.isNotBlank()) {
                item {
                    qrBitmap?.let {
                        Image(bitmap = it.asImageBitmap(), contentDescription = "一次性设备配对二维码",
                            modifier = Modifier.fillMaxWidth())
                    }
                    Text("临时配对码（只显示在此设备上）：", style = MaterialTheme.typography.labelSmall)
                    Text(inviteCode, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { inviteCode = ""; qrBitmap = null }) {
                        Text("立即隐藏配对码")
                    }
                }
            }
            item {
                Text("已授权设备", style = MaterialTheme.typography.titleMedium)
                FilledTonalButton(enabled = connection != null && !busy, onClick = {
                    val current = connection ?: return@FilledTonalButton
                    scope.launch {
                        try { refresh(current) }
                        catch (_: Exception) { status = "读取设备列表失败" }
                    }
                }) { Text("刷新设备列表") }
            }
            items(devices, key = { it.id }) { device ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(12.dp)) {
                    Text(device.label + if (device.isCurrent) " · 本机" else "")
                    Text(
                        "已授权",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(enabled = !busy, onClick = { revokeTarget = device }) {
                        Text(if (device.isCurrent) "撤销本机授权" else "撤销此设备")
                    }
                }
                }
            }
            item {
                Text("撤销操作由服务端执行，不能撤销 QQ、飞书或钉钉机器人，也不会修改服务器记忆。撤销本机会失去当前连接权限。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    val target = revokeTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text("确认撤销设备授权？") },
            text = {
                Text("确定撤销“" + target.label + "”吗？此操作立即使对应 Token 失效，不能在本机撤销后自动恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    revokeTarget = null
                    val current = connection ?: return@TextButton
                    busy = true
                    scope.launch {
                        try {
                            if (transport.revokeDevice(current, target.id)) {
                                status = "服务器已撤销设备授权"
                                if (target.isCurrent) {
                                    withContext(Dispatchers.IO) { store.forgetLocal() }
                                    connection = null
                                    devices = emptyList()
                                } else refresh(current)
                            } else status = "服务器未确认撤销"
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            status = "撤销状态未知，请通过仍然授权的设备核验"
                        } finally { busy = false }
                    }
                }) { Text("确认撤销") }
            },
            dismissButton = {
                TextButton(onClick = { revokeTarget = null }) { Text("取消") }
            }
        )
    }
}
