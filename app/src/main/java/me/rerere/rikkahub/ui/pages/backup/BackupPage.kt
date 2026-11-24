package me.rerere.rikkahub.ui.pages.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.FileExport
import me.rerere.hugeicons.stroke.FileZip
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.BackupReminderConfig
import me.rerere.rikkahub.data.datastore.BackupTarget
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.MissingBackupPassphraseException
import me.rerere.rikkahub.data.sync.S3BackupItem
import me.rerere.rikkahub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.s3ConnectionItems
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.components.RestorePassphraseDialog
import me.rerere.rikkahub.ui.pages.backup.components.rememberRestorePassphrasePrompt
import me.rerere.rikkahub.ui.pages.backup.components.BackupDialog
import me.rerere.rikkahub.ui.pages.backup.components.backupContentItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.toLocalDateTime
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

private const val MIN_BACKUP_PASSPHRASE = 6

private val IntervalOptions = listOf(6, 12, 24, 72)
private val RetentionOptions = listOf(0, 5, 10, 20)
private val RestoreSections = listOf(BackupTarget.LOCAL, BackupTarget.WEBDAV, BackupTarget.S3)

private fun BackupTarget.label(): String = when (this) {
    BackupTarget.LOCAL -> "本机文件"
    BackupTarget.WEBDAV -> "WebDAV"
    BackupTarget.S3 -> "S3"
}

/**
 * 单页「备份与恢复」：状态、立即备份、恢复、自动备份开关、存储位置、备份记录、高级设置。
 * 日常流程不出现任何备份口令输入框；旧口令归档只在恢复旧文件时弹一次。
 */
@Composable
fun BackupPage(vm: BackupVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.backupReminderConfig
    val target = config.backupTarget
    val passphrasePrompt = rememberRestorePassphrasePrompt()
    RestorePassphraseDialog(passphrasePrompt)

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val webDavItems by vm.webDavBackupItems.collectAsStateWithLifecycle()
    val s3Items by vm.s3BackupItems.collectAsStateWithLifecycle()

    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("备份中…") }
    var restoreFraction by remember { mutableStateOf<Float?>(null) }
    var showProgress by remember { mutableStateOf(false) }
    var showExportPassphrase by remember { mutableStateOf(false) }
    var pendingExportPassphrase by remember { mutableStateOf<CharArray?>(null) }
    var showRestart by remember { mutableStateOf(false) }
    var showStorage by remember { mutableStateOf(false) }
    var showRecords by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var restoringName by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<Pair<String, (suspend () -> Unit)?>?>(null) }

    fun toast(message: String, error: Boolean = false) {
        toaster.show(message, type = if (error) ToastType.Error else ToastType.Success)
    }

    val createLocalBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            restoreFraction = null
            busyLabel = "正在打包备份…"
            runCatching {
                val export = vm.exportToFile(pendingExportPassphrase)
                try {
                    // 200MB 级的包写到 SAF 上必须离开主线程：原来这段同步 copy 直接把界面冻住，
                    // 用户看到的就是「一直显示备份中，然后不动了」。
                    busyLabel = "正在写入文件…"
                    withContext(Dispatchers.IO) {
                        requireNotNull(context.contentResolver.openOutputStream(uri)) { "无法写入所选位置" }
                            .use { out -> FileInputStream(export).use { copyTo(out, it) } }
                    }
                    vm.recordLocalExportSuccess()
                } finally {
                    export.delete()
                    pendingExportPassphrase?.fill('\u0000')
                    pendingExportPassphrase = null
                }
            }.onSuccess { toast("备份已保存") }
                .onFailure { toast("备份失败：" + (it.message ?: "未知错误"), error = true) }
            busy = false
        }
    }

    val pickLocalBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            restoreFraction = null
            busyLabel = "正在读取备份文件…"
            try {
                busyLabel = "正在解密并校验备份…"
                // 双模式：先用本机密钥；本机没有密钥或密钥不匹配时，允许输入备份口令（至少 6 位）
                var typed: CharArray? = null
                while (true) {
                    try {
                        vm.restoreFromStream({ context.contentResolver.openInputStream(uri) }, typed) { done, total ->
                            if (total > 0) {
                                val next = (done.toFloat() / total).coerceIn(0f, 1f)
                                // 只在变化 >=1% 时更新，避免每 64KB 触发一次重组
                                if (restoreFraction == null || kotlin.math.abs(next - restoreFraction!!) >= 0.01f) {
                                    restoreFraction = next
                                }
                            }
                        }
                        break
                    } catch (_: MissingBackupPassphraseException) {
                        typed = passphrasePrompt.ask() ?: run {
                            toast("这台设备没有备份密钥，也没有输入口令：请导入恢复密钥文件，或输入备份口令", error = true)
                            showRecords = true
                            return@launch
                        }
                        if (typed.size < MIN_BACKUP_PASSPHRASE) {
                            toast("口令至少 $MIN_BACKUP_PASSPHRASE 位", error = true)
                            return@launch
                        }
                    } catch (e: IllegalStateException) {
                        if (e.message?.contains("口令") != true) throw e
                        // 口令错误不设次数上限：用户随时可以重试，取消由用户点取消决定
                        typed = passphrasePrompt.ask() ?: run {
                            toast("备份与本机密钥不匹配：请导入对应的恢复密钥文件，或输入备份口令", error = true)
                            return@launch
                        }
                    }
                }
                typed?.fill('\u0000')
                toast("恢复完成")
                showRestart = true
            } catch (e: Exception) {
                toast("恢复失败：" + (e.message ?: "未知错误"), error = true)
            } finally {
                busy = false
            }
        }
    }

    val exportKeyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            vm.exportRecoveryKey(uri)
                .onSuccess { toast("恢复密钥已导出，请与备份分开保存") }
                .onFailure { toast("导出失败：" + (it.message ?: "未知错误"), error = true) }
        }
    }

    val importKeyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            vm.importRecoveryKey(uri)
                .onSuccess { toast("恢复密钥已导入") }
                .onFailure { toast("导入失败：" + (it.message ?: "未知错误"), error = true) }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("备份与恢复") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CardGroup {
                item(
                    leadingContent = { CardGroupIcon(HugeIcons.Clock01) },
                    headlineContent = { Text("备份状态") },
                    supportingContent = {
                        Column {
                            Text(
                                if (config.lastBackupTime == 0L) {
                                    stringResource(R.string.backup_page_reminder_no_record)
                                } else {
                                    "最近备份：" + Instant.ofEpochMilli(config.lastBackupTime).toLocalDateTime()
                                }
                            )
                            Text("存储位置：" + target.label())
                            if (config.lastAutoBackupError.isNotBlank()) {
                                Text("上次自动备份失败：" + config.lastAutoBackupError)
                            }
                        }
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        if (busy) return@Button
                        when (target) {
                            BackupTarget.LOCAL -> showExportPassphrase = true
                            else -> scope.launch {
                                busy = true
                                runCatching { vm.backupNow(target) }
                                    .onSuccess {
                                        toast("备份已完成")
                                        vm.loadBackupFileItems()
                                        vm.loadS3BackupFileItems()
                                    }
                                    .onFailure { toast("备份失败：" + (it.message ?: "未知错误"), error = true) }
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text(if (busy) busyLabel else "立即备份") }
                FilledTonalButton(
                    onClick = {
                        vm.loadBackupFileItems()
                        vm.loadS3BackupFileItems()
                        showRecords = true
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("恢复备份") }
            }

            CardGroup {
                switchItem(
                    checked = config.autoBackupOnAppLifecycle,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            vm.setAutoBackupEnabled(enabled)
                            toast(if (enabled) "自动备份已开启" else "自动备份已关闭")
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.backup_page_auto_backup)) },
                    supportingContent = { Text("保存聊天、助手、记忆和设置；需要可用的备份位置") },
                )
                item(
                    onClick = { showStorage = true },
                    leadingContent = { CardGroupIcon(HugeIcons.SmartPhone01) },
                    headlineContent = { Text("存储位置") },
                    supportingContent = { Text(target.label()) },
                )
                item(
                    onClick = {
                        vm.loadBackupFileItems()
                        vm.loadS3BackupFileItems()
                        showRecords = true
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.FileZip) },
                    headlineContent = { Text("备份记录") },
                    supportingContent = { Text("恢复、删除、刷新") },
                )
                item(
                    onClick = { showImport = true },
                    leadingContent = { CardGroupIcon(HugeIcons.Folder01) },
                    headlineContent = { Text("从其他应用导入") },
                    supportingContent = { Text("Chatbox、DeepSeek、Cherry Studio") },
                )
                item(
                    onClick = { showAdvanced = true },
                    leadingContent = { CardGroupIcon(HugeIcons.Settings02) },
                    headlineContent = { Text("高级设置") },
                    supportingContent = { Text("备份范围、周期、保留份数、恢复密钥") },
                )
            }
        }
    }

    if (showStorage) {
        StorageSheet(
            vm = vm,
            target = target,
            onSelect = { selected ->
                vm.updateSettings { it.copy(backupReminderConfig = it.backupReminderConfig.copy(backupTarget = selected)) }
            },
            onRestoreLocal = {
                showStorage = false
                pickLocalBackup.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            },
            onDismiss = { showStorage = false },
        )
    }

    if (showExportPassphrase) {
        ExportPassphraseDialog(
            onDismiss = { showExportPassphrase = false },
            onConfirm = { passphrase ->
                showExportPassphrase = false
                pendingExportPassphrase = passphrase
                createLocalBackup.launch("Rikkahub-M-backup-${System.currentTimeMillis()}.rkmbak")
            },
        )
    }

    if (busy) {
        // 不用整屏铺色：一个带百分比的小对话框，背景页面保持可见
        AlertDialog(
            onDismissRequest = { /* 进行中不可点掉，避免半成品 */ },
            title = { Text(busyLabel) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val fraction = restoreFraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        "大备份（几百 MB）需要几十秒到几分钟，请不要退出本页面。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
        )
    }

    if (showRecords) {
        RecordsSheet(
            vm = vm,
            target = target,
            webDavItems = webDavItems,
            s3Items = s3Items,
            restoringName = restoringName,
            onLocalRestore = {
                showRecords = false
                pickLocalBackup.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            },
            onImportKey = {
                showRecords = false
                importKeyLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
            },
            onRestore = { action ->
                restoringName = "restoring"
                showRecords = false
                scope.launch {
                    busy = true
                    busyLabel = "正在下载并校验备份…"
                    var typed: CharArray? = null
                    try {
                        while (true) {
                            try {
                                action(typed)
                                break
                            } catch (_: MissingBackupPassphraseException) {
                                typed = passphrasePrompt.ask() ?: run {
                                    toast("这台设备没有备份密钥，也没有输入口令：请先导入恢复密钥文件", error = true)
                                    return@launch
                                }
                            } catch (e: IllegalStateException) {
                                if (e.message?.contains("口令") != true) throw e
                                typed = passphrasePrompt.ask() ?: run {
                                    toast("备份与本机密钥不匹配：请导入对应的恢复密钥文件，或输入备份口令", error = true)
                                    return@launch
                                }
                            }
                        }
                        typed?.fill('\u0000')
                        toast("恢复完成")
                        showRecords = false
                        showRestart = true
                    } catch (e: Exception) {
                        toast("恢复失败：" + (e.message ?: "未知错误"), error = true)
                    } finally {
                        restoringName = null
                        busy = false
                    }
                }
            },
            onDelete = { name, action ->
                deleteTarget = name to action
            },
            onDismiss = { showRecords = false },
        )
    }

    if (showAdvanced) {
        AdvancedSheet(
            vm = vm,
            onExportKey = { exportKeyLauncher.launch("Rikkahub-M-recovery-key.txt") },
            onImportKey = { importKeyLauncher.launch(arrayOf("text/plain", "*/*")) },
            onDismiss = { showAdvanced = false },
        )
    }

    if (showImport) {
        ImportSheet(
            vm = vm,
            onDone = { showImport = false },
            onRestart = { showImport = false; showRestart = true },
        )
    }

    deleteTarget?.let { (name, action) ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除备份") },
            text = { Text("删除「$name」后无法恢复，确定删除？") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    action?.let { runDelete ->
                        scope.launch {
                            runCatching { runDelete() }
                                .onSuccess {
                                    toast("已删除")
                                    vm.loadBackupFileItems()
                                    vm.loadS3BackupFileItems()
                                }
                                .onFailure { toast("删除失败：" + (it.message ?: "未知错误"), error = true) }
                        }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showRestart) {
        BackupDialog()
    }
}

@Composable
private fun StorageSheet(
    vm: BackupVM,
    target: BackupTarget,
    onSelect: (BackupTarget) -> Unit,
    onRestoreLocal: () -> Unit,
    onDismiss: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val resources = LocalResources.current
    var testing by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("存储位置", style = MaterialTheme.typography.titleLarge)
            CardGroup {
                RestoreSections.forEach { option ->
                    item(
                        onClick = { onSelect(option) },
                        leadingContent = { CardGroupIcon(HugeIcons.SmartPhone01) },
                        headlineContent = { Text(option.label()) },
                        supportingContent = { Text(if (option == target) "当前使用" else "点击切换") },
                    )
                }
            }

            when (target) {
                BackupTarget.LOCAL -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本机备份保存为你选择的文件，文件本身已加密。")
                    Text(
                        "加密密钥保存在本机（Android Keystore）。换机、卸载重装或清除应用数据后，必须先用「导出恢复密钥文件」拿到的密钥才能打开这份备份；密钥丢了，备份无法恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = onRestoreLocal) { Text("从本机文件恢复") }
                }
                BackupTarget.WEBDAV -> CardGroup(title = { Text("WebDAV") }) {
                    // 明文地址 + Basic 认证 = 口令在局域网上裸奔，这里显式提示而不是静默拦下
                    val webDavUrl = settings.webDavConfig.url
                    val plaintextWarning = webDavUrl.startsWith("http://", ignoreCase = true) &&
                        settings.webDavConfig.password.isNotBlank()
                    formItem(headlineContent = { Text("地址") }) {
                        OutlinedTextField(
                            value = webDavUrl,
                            onValueChange = { value -> vm.updateSettings { it.copy(webDavConfig = it.webDavConfig.copy(url = value.trim())) } },
                            singleLine = true,
                            placeholder = { Text("https://dav.example.com") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (plaintextWarning) {
                        item(headlineContent = {
                            Text(
                                text = "当前地址是 http://，用户名与密码会以明文传输。建议改用 https:// 地址。",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        })
                    }
                    formItem(headlineContent = { Text("用户名") }) {
                        OutlinedTextField(
                            value = settings.webDavConfig.username,
                            onValueChange = { value -> vm.updateSettings { it.copy(webDavConfig = it.webDavConfig.copy(username = value.trim())) } },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    formItem(headlineContent = { Text("密码") }) {
                        OutlinedTextField(
                            value = settings.webDavConfig.password,
                            onValueChange = { value -> vm.updateSettings { it.copy(webDavConfig = it.webDavConfig.copy(password = value)) } },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    formItem(headlineContent = { Text("目录") }) {
                        OutlinedTextField(
                            value = settings.webDavConfig.path,
                            onValueChange = { value -> vm.updateSettings { it.copy(webDavConfig = it.webDavConfig.copy(path = value.trim())) } },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item(
                        onClick = {
                            scope.launch {
                                testing = true
                                runCatching { vm.testWebDav() }
                                    .onSuccess { toaster.show(resources.getString(R.string.backup_page_connection_success), type = ToastType.Success) }
                                    .onFailure { toaster.show(resources.getString(R.string.backup_page_connection_failed, it.message ?: ""), type = ToastType.Error) }
                                testing = false
                            }
                        },
                        headlineContent = { Text(if (testing) "测试中…" else "测试连接") },
                    )
                }
                BackupTarget.S3 -> CardGroup(title = { Text("S3") }) {
                    s3ConnectionItems(settings.s3Config) { cfg -> vm.updateSettings { it.copy(s3Config = cfg) } }
                    item(
                        onClick = {
                            scope.launch {
                                testing = true
                                runCatching { vm.testS3() }
                                    .onSuccess { toaster.show(resources.getString(R.string.backup_page_connection_success), type = ToastType.Success) }
                                    .onFailure { toaster.show(resources.getString(R.string.backup_page_connection_failed, it.message ?: ""), type = ToastType.Error) }
                                testing = false
                            }
                        },
                        headlineContent = { Text(if (testing) "测试中…" else "测试连接") },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordsSheet(
    vm: BackupVM,
    target: BackupTarget,
    webDavItems: UiState<List<WebDavBackupItem>>,
    s3Items: UiState<List<S3BackupItem>>,
    restoringName: String?,
    onLocalRestore: () -> Unit,
    onImportKey: () -> Unit,
    onRestore: (suspend (CharArray?) -> Unit) -> Unit,
    onDelete: (String, suspend () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var section by remember { mutableStateOf(target) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("恢复备份", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RestoreSections.forEach { option ->
                    FilledTonalButton(onClick = { section = option }) {
                        Text(option.label() + if (section == option) " ●" else "")
                    }
                }
            }
            when (section) {
                BackupTarget.LOCAL -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("选择本机保存的备份文件。备份用密钥加密，不再需要记口令：同机恢复直接用本机密钥；换机后先导入恢复密钥文件再恢复。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onLocalRestore, enabled = restoringName == null) { Text("选择文件") }
                        FilledTonalButton(onClick = onImportKey, enabled = restoringName == null) {
                            Text("导入恢复密钥文件")
                        }
                    }
                }
                BackupTarget.WEBDAV -> BackupList(
                    state = webDavItems,
                    restoringName = restoringName,
                    describe = { "${it.displayName}" to "${it.size.fileSizeToString()} · ${it.lastModified.toLocalDateTime()}" },
                    onRestore = { item -> onRestore { pass -> vm.restore(item, pass) } },
                    onDelete = { item -> onDelete(item.displayName) { vm.deleteWebDavBackupFile(item) } },
                )
                BackupTarget.S3 -> BackupList(
                    state = s3Items,
                    restoringName = restoringName,
                    describe = { "${it.displayName}" to "${it.size.fileSizeToString()} · ${it.lastModified.toLocalDateTime()}" },
                    onRestore = { item -> onRestore { pass -> vm.restoreFromS3(item, pass) } },
                    onDelete = { item -> onDelete(item.displayName) { vm.deleteS3BackupFile(item) } },
                )
            }
        }
    }
}

@Composable
private fun <T> BackupList(
    state: UiState<List<T>>,
    restoringName: String?,
    describe: (T) -> Pair<String, String>,
    onRestore: (T) -> Unit,
    onDelete: (T) -> Unit,
) {
    when (state) {
        is UiState.Success -> if (state.data.isEmpty()) {
            Text("这里还没有备份记录。")
        } else {
            state.data.forEach { item ->
                val (name, detail) = describe(item)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleSmall)
                        Text(detail, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onRestore(item) }, enabled = restoringName == null) { Text("恢复") }
                    TextButton(onClick = { onDelete(item) }, enabled = restoringName == null) { Text("删除") }
                }
            }
        }
        is UiState.Error -> Text("无法读取备份列表：" + (state.error.message ?: "网络错误"))
        else -> Text("加载中…")
    }
}

@Composable
private fun AdvancedSheet(
    vm: BackupVM,
    onExportKey: () -> Unit,
    onImportKey: () -> Unit,
    onDismiss: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.backupReminderConfig
    val items by vm.localBackupItems.collectAsStateWithLifecycle()
    val automaticPassphraseConfigured by vm.automaticPassphraseConfigured.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    fun toast(message: String, error: Boolean = false) {
        toaster.show(message, type = if (error) ToastType.Error else ToastType.Success)
    }
    var showSetPassphrase by remember { mutableStateOf(false) }
    if (showSetPassphrase) {
        SetPassphraseDialog(
            onDismiss = { showSetPassphrase = false },
            onConfirm = { value ->
                showSetPassphrase = false
                scope.launch {
                    runCatching { vm.setBackupPassphrase(value) }
                        .onSuccess { toast("自动备份口令已设置") }
                        .onFailure { toast("设置失败：" + (it.message ?: "未知错误"), error = true) }
                }
            },
        )
    }
    fun update(block: (BackupReminderConfig) -> BackupReminderConfig) {
        vm.updateSettings { it.copy(backupReminderConfig = block(it.backupReminderConfig)) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("高级设置", style = MaterialTheme.typography.titleLarge)
            CardGroup(title = { Text("备份范围") }) {
                backupContentItem(
                    options = WebDavConfig.BackupItem.entries,
                    selected = items,
                    onChange = { vm.updateLocalBackupItems(it) },
                ) { item -> if (item == WebDavConfig.BackupItem.DATABASE) "聊天与数据库" else "附件与文件" }
            }
            CardGroup(title = { Text("自动备份") }) {
                item(
                    onClick = { showSetPassphrase = true },
                    leadingContent = { CardGroupIcon(HugeIcons.Settings02) },
                    headlineContent = { Text("自动备份口令") },
                    supportingContent = {
                        Text(
                            if (automaticPassphraseConfigured) {
                                "已设置自定义口令：自动备份用它加密，任何设备输入同一口令即可恢复"
                            } else {
                                "使用本机随机密钥：同机恢复免输入，换机需要导入恢复密钥文件"
                            }
                        )
                    },
                    trailingContent = { CardGroupValue("设置") },
                )
                item(
                    onClick = {
                        scope.launch {
                            vm.resetBackupPassphraseToGeneratedKey()
                            toast("已重置为本机随机密钥")
                        }
                    },
                    headlineContent = { Text("重置为本机随机密钥") },
                    supportingContent = { Text("丢弃自定义口令；已生成的备份不受影响") },
                )
                formItem(
                    headlineContent = { Text(stringResource(R.string.backup_page_auto_settings_interval)) },
                    trailingContent = { CardGroupValue("${config.autoSettingsIntervalHours} 小时") },
                ) {
                    ConnectedChoiceButtons(
                        options = IntervalOptions,
                        selected = config.autoSettingsIntervalHours,
                        onSelect = { hours -> update { it.copy(autoSettingsIntervalHours = hours) } },
                    ) { hours, selected -> ConnectedChoiceLabel(hours.toString(), selected) }
                }
                formItem(
                    headlineContent = { Text(stringResource(R.string.backup_page_auto_chats_interval)) },
                    trailingContent = { CardGroupValue("${config.autoChatsIntervalHours} 小时") },
                ) {
                    ConnectedChoiceButtons(
                        options = IntervalOptions,
                        selected = config.autoChatsIntervalHours,
                        onSelect = { hours -> update { it.copy(autoChatsIntervalHours = hours) } },
                    ) { hours, selected -> ConnectedChoiceLabel(hours.toString(), selected) }
                }
                formItem(
                    headlineContent = { Text(stringResource(R.string.backup_page_auto_retention)) },
                    trailingContent = {
                        CardGroupValue(if (config.autoRetainVersions == 0) "保留全部" else "保留 ${config.autoRetainVersions} 份")
                    },
                ) {
                    ConnectedChoiceButtons(
                        options = RetentionOptions,
                        selected = config.autoRetainVersions,
                        onSelect = { keep -> update { it.copy(autoRetainVersions = keep) } },
                    ) { keep, selected -> ConnectedChoiceLabel(if (keep == 0) "全部" else keep.toString(), selected) }
                }
            }
            CardGroup(title = { Text("恢复密钥") }) {
                item(
                    leadingContent = { CardGroupIcon(HugeIcons.FileExport) },
                    headlineContent = { Text("换机或重装时使用") },
                    supportingContent = { Text("密钥文件属于敏感材料，请与备份分开保存，不要截图或分享。") },
                )
                item(onClick = onExportKey, headlineContent = { Text("导出恢复密钥文件") })
                item(onClick = onImportKey, headlineContent = { Text("导入恢复密钥文件") })
            }
            Text(
                "旧版口令备份仍然兼容：恢复旧文件时才会要求输入一次原口令；新备份不会出现口令框。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ImportSheet(
    vm: BackupVM,
    onDone: () -> Unit,
    onRestart: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var type by remember { mutableStateOf("chatbox") }
    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("备份中…") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                when (type) {
                    "chatbox" -> {
                        val temp = File(context.cacheDir, "chatbox_${System.currentTimeMillis()}.zip")
                        try {
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    FileOutputStream(temp).use { copyTo(it, input) }
                                }
                            }
                            vm.restoreFromChatBox(temp)
                        } finally {
                            temp.delete()
                        }
                    }
                    "deepseek" -> vm.restoreFromDeepSeek(uri)
                    else -> {
                        val temp = File(context.cacheDir, "cherry_${System.currentTimeMillis()}.zip")
                        try {
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    FileOutputStream(temp).use { copyTo(it, input) }
                                }
                            }
                            vm.restoreFromCherryStudio(temp)
                        } finally {
                            temp.delete()
                        }
                    }
                }
            }.onSuccess {
                toaster.show("导入完成", type = ToastType.Success)
                if (type != "deepseek") onRestart() else onDone()
            }.onFailure {
                toaster.show("导入失败：" + (it.message ?: "未知错误"), type = ToastType.Error)
            }
            busy = false
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDone,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("从其他应用导入", style = MaterialTheme.typography.titleLarge)
            CardGroup {
                listOf(
                    "chatbox" to "Chatbox 备份",
                    "deepseek" to "DeepSeek 导出",
                    "cherry" to "Cherry Studio 备份",
                ).forEach { (key, label) ->
                    item(
                        onClick = { type = key },
                        headlineContent = { Text(label) },
                        supportingContent = { Text(if (type == key) "已选择" else "点击选择") },
                    )
                }
            }
            Button(
                onClick = { picker.launch(arrayOf("application/zip", "application/json", "*/*")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "导入中…" else "选择文件并导入") }
        }
    }
}

/** 字节流复制：不解码内容，避免大备份对象在内存里变形。 */
private fun copyTo(output: java.io.OutputStream, input: java.io.InputStream) {
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
    }
}

/**
 * 导出前的可选口令：
 * - 留空 → 用本机密钥加密（同机恢复不需要任何输入；换机需要导入恢复密钥文件）；
 * - 填口令（至少 6 位）→ 用口令加密，任何设备只要知道口令就能恢复，不再依赖密钥。
 */
@Composable
private fun ExportPassphraseDialog(
    onDismiss: () -> Unit,
    onConfirm: (CharArray?) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val usingPassphrase = passphrase.isNotEmpty() || confirmation.isNotEmpty()
    val mismatch = usingPassphrase && passphrase != confirmation
    val tooShort = usingPassphrase && passphrase.length < MIN_BACKUP_PASSPHRASE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置备份口令（可留空）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "留空：用本机密钥加密，恢复时不需要输入，但换机必须导入恢复密钥文件。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "填写（至少 $MIN_BACKUP_PASSPHRASE 位）：任何设备只要输入这个口令就能恢复，不再依赖密钥。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("口令") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = tooShort,
                    supportingText = if (tooShort) {
                        { Text("至少 $MIN_BACKUP_PASSPHRASE 位") }
                    } else null,
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text("确认口令") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = mismatch,
                    supportingText = if (mismatch) {
                        { Text("两次输入不一致") }
                    } else null,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !tooShort && !mismatch,
                onClick = {
                    val value = runCatching {
                        me.rerere.rikkahub.data.sync.BackupArchiveFormat
                            .exportPassphrase(usingPassphrase, passphrase, confirmation, includeCredentials = false)
                    }.getOrNull()
                    passphrase = ""
                    confirmation = ""
                    onConfirm(value)
                },
            ) { Text("导出") }
        },
        dismissButton = {
            TextButton(onClick = {
                passphrase = ""
                confirmation = ""
                onDismiss()
            }) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** 设置自动备份口令：留空不提交，必须 ≥6 位且两次一致。 */
@Composable
private fun SetPassphraseDialog(
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val tooShort = passphrase.isNotEmpty() && passphrase.length < MIN_BACKUP_PASSPHRASE
    val mismatch = confirmation.isNotEmpty() && passphrase != confirmation
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置自动备份口令") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "自动备份与「立即备份」都会用这个口令加密；任何设备只要输入同一口令即可恢复，不再依赖密钥文件。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "口令至少 $MIN_BACKUP_PASSPHRASE 位。忘记口令后已生成的备份无法恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("口令") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = tooShort,
                    supportingText = if (tooShort) {
                        { Text("至少 $MIN_BACKUP_PASSPHRASE 位") }
                    } else null,
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text("确认口令") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = mismatch,
                    supportingText = if (mismatch) {
                        { Text("两次输入不一致") }
                    } else null,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = passphrase.length >= MIN_BACKUP_PASSPHRASE && passphrase == confirmation,
                onClick = {
                    val value = passphrase.toCharArray()
                    passphrase = ""
                    confirmation = ""
                    onConfirm(value)
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = {
                passphrase = ""
                confirmation = ""
                onDismiss()
            }) { Text(stringResource(R.string.cancel)) }
        },
    )
}
