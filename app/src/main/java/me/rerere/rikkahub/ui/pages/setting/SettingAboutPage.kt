package me.rerere.rikkahub.ui.pages.setting

import me.rerere.common.util.runCatchingCancellable

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.center
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.drawAppLogo
import me.rerere.rikkahub.ui.components.ui.logoMorphShape
import me.rerere.rikkahub.ui.components.ui.rememberAppLogoLayers
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.utils.UpdateCheckResult
import me.rerere.rikkahub.utils.UpdateInfo
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalResources
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.InstallPermissionRequiredException
import me.rerere.rikkahub.utils.UpdateDownloadState
import me.rerere.rikkahub.utils.formatByteSize
import me.rerere.rikkahub.utils.UpdateDownloadService
import me.rerere.rikkahub.utils.UpdateInstaller
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.rerere.ui.easteregg.EmojiBurstHost

@Composable
fun SettingAboutPage() {
    val updateChecker = koinInject<me.rerere.rikkahub.utils.UpdateChecker>()
    val toaster = me.rerere.rikkahub.ui.context.LocalToaster.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var checking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var pendingUpdate by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<me.rerere.rikkahub.utils.UpdateInfo?>(null) }
    // 已校验通过、只等用户授权「安装未知应用」的安装包
    var needsInstallPermission by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<java.io.File?>(null)
    }
    var installFailure by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val context = LocalContext.current
    val downloadState by updateChecker.download.collectAsStateWithLifecycle()
    // 通知权限：下载前申请一次；拒绝也不阻塞下载，只是弹窗内改为显示应用内进度提示
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            updateChecker.reportNotificationProblem(null)
            UpdateDownloadService.start(context)
        } else {
            updateChecker.reportNotificationProblem("通知权限未授予，下载进度仅在应用内显示")
        }
    }
    val needsNotificationPermission =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    val navController = LocalNavController.current
    val soundOptions = remember { listOf(R.raw.bingbingbing, R.raw.gangguan) }
    val soundEffectPlayer = remember(context) { SoundEffectPlayer(context) }
    DisposableEffect(soundEffectPlayer) {
        soundEffectPlayer.preload(*soundOptions.toIntArray())
        onDispose {
            soundEffectPlayer.release()
        }
    }
    val emojiOptions = remember {
        listOf(
            "🎉", "✨", "🌟", "💫", "🎊", "🥳", "🎈", "🎆", "🎇", "🧨",
            "🌈", "🧧", "🎁", "🍬", "🍭", "🍉", "🍓", "🍒", "🍍", "🥭",
            "🐱", "🐶", "🦊", "🐼", "🦁", "🐯", "🐵", "🦄",
            "❤️", "🧡", "💛", "💚", "💙", "💜",
            "🇨🇳", "🌏", "🌍", "🌎",
            "🤗", "🤩", "😆", "😺", "😸", "🤡",
            "💡", "🔥", "💥", "🚀", "⭐", "🌙"
        )
    }
    val logoLayers = rememberAppLogoLayers()
    // 表情从 logo 中心喷出，坐标要换算到 EmojiBurstHost 里
    var hostCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var logoCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.about_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        EmojiBurstHost(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { hostCoordinates = it },
            emojiOptions = emojiOptions,
            burstCount = 12
        ) { onBurst ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    val interactionSource = remember { MutableInteractionSource() }
                    val pressed by interactionSource.collectIsPressedAsState()
                    val logoScale by animateFloatAsState(
                        targetValue = if (pressed) 0.88f else 1f,
                        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                    )
                    // 每点一次，轮廓变形到下一个形状
                    var shapeIndex by remember { mutableIntStateOf(0) }
                    val shapePosition by animateFloatAsState(
                        targetValue = shapeIndex.toFloat(),
                        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 8.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(136.dp)
                                .semantics { contentDescription = "Logo" }
                                .onGloballyPositioned { logoCoordinates = it }
                                .graphicsLayer {
                                    scaleX = logoScale
                                    scaleY = logoScale
                                    shape = logoMorphShape(shapePosition)
                                    clip = true
                                }
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = LocalIndication.current,
                                ) {
                                    val host = hostCoordinates
                                    val logo = logoCoordinates
                                    if (host != null && logo != null && host.isAttached && logo.isAttached) {
                                        onBurst(host.localPositionOf(logo, logo.size.center.toOffset()))
                                    }
                                    soundEffectPlayer.play(soundOptions.random())
                                    shapeIndex++
                                }
                                .drawAppLogo(logoLayers)
                        )

                        Text(
                            text = "Rikkahub-M",
                            style = MaterialTheme.typography.headlineLargeEmphasized.copy(
                                fontWeight = FontWeight.Black,
                            ),
                        )
                    }
                }


                item {
                    val containerColor = MaterialTheme.colorScheme.primaryContainer
                    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    CardGroup {
                        item(
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    if (downloadState is UpdateDownloadState.Failed) {
                                        updateChecker.retryDownload()
                                    } else if (pendingUpdate == null && updateChecker.targetInfo != null &&
                                        downloadState !is UpdateDownloadState.Idle
                                    ) {
                                        // 后台下载进行中：点版本行直接回到进度弹窗，不重新检查
                                        pendingUpdate = updateChecker.targetInfo
                                    } else if (checking) {
                                        // 已在检查中，忽略重复点击。
                                    } else {
                                    checking = true
                                    scope.launch {
                                        when (val result = updateChecker.checkNow()) {
                                            is UpdateCheckResult.UpToDate ->
                                                toaster.show(resources.getString(R.string.about_page_check_latest), type = ToastType.Success)
                                            is UpdateCheckResult.Available -> pendingUpdate = result.info
                                            is UpdateCheckResult.Failed ->
                                                toaster.show(resources.getString(R.string.about_page_check_failed, result.reason), type = ToastType.Error)
                                        }
                                        checking = false
                                    }
                                    }
                                },
                                onLongClick = { navController.navigate(Screen.Debug) },
                            ),
                            leadingContent = { CardGroupIcon(HugeIcons.Code, containerColor = containerColor, contentColor = contentColor) },
                            supportingContent = {
                                Text(
                                    when (val state = downloadState) {
                                        is UpdateDownloadState.Preparing -> "正在准备下载 ${state.version}"
                                        is UpdateDownloadState.Running -> {
                                            val percent = state.fraction?.let { (it * 100).toInt() }
                                            if (percent == null) "正在下载 ${state.version} · ${formatByteSize(state.read)}"
                                            else "正在下载 ${state.version} · $percent%"
                                        }
                                        is UpdateDownloadState.Verifying -> "正在校验更新包 ${state.version}"
                                        is UpdateDownloadState.Ready -> "更新包已就绪，点击继续安装"
                                        is UpdateDownloadState.Failed -> "下载失败，点击重试或改用浏览器下载"
                                        UpdateDownloadState.Idle -> "versionCode ${BuildConfig.VERSION_CODE} · 点击检查更新"
                                    }
                                )
                            },
                            headlineContent = { Text(if (checking) stringResource(R.string.about_page_checking_update) else BuildConfig.VERSION_NAME) },
                        )
                    }
                }

                item {
                    val containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    val contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    CardGroup {
                        item(
                            onClick = { navController.navigate(Screen.MateChangelog) },
                            leadingContent = { CardGroupIcon(HugeIcons.Code, containerColor = containerColor, contentColor = contentColor) },
                            supportingContent = { Text("内置说明与已发布版本") },
                            headlineContent = { Text("更新日志") },
                        )
                        item(
                            onClick = { context.openUrl("https://qm.qq.com/q/Yvc3ugiCoE") },
                            leadingContent = { CardGroupIcon(HugeIcons.SmartPhone01, containerColor = containerColor, contentColor = contentColor) },
                            supportingContent = { Text("通过QQ联系") },
                            headlineContent = { Text("联系作者") },
                        )
                        item(
                            onClick = { context.openUrl("https://github.com/cs9527h/Rikkahub-M") },
                            leadingContent = { CardGroupIcon(HugeIcons.Earth, containerColor = containerColor, contentColor = contentColor) },
                            supportingContent = { Text("github.com/cs9527h/Rikkahub-M") },
                            headlineContent = { Text("Rikkahub-M") },
                        )
                        item(
                            onClick = { context.openUrl("https://github.com/cs9527h/Rikkahub-M/blob/master/LICENSE") },
                            leadingContent = { CardGroupIcon(HugeIcons.File02, containerColor = containerColor, contentColor = contentColor) },
                            supportingContent = { Text("AGPL-3.0 · 上游版权保留") },
                            headlineContent = { Text(stringResource(R.string.about_page_license)) },
                        )
                    }
                }
            }

            needsInstallPermission?.let { apk ->
                val lifecycleOwner = LocalLifecycleOwner.current
                androidx.compose.runtime.DisposableEffect(lifecycleOwner, apk, context) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME &&
                            UpdateInstaller.canRequestPackageInstalls(context)
                        ) {
                            runCatchingCancellable { UpdateInstaller.startSystemInstall(context, apk) }
                                .onSuccess {
                                    needsInstallPermission = null
                                    toaster.show("已获取安装权限，正在打开系统安装器", type = ToastType.Success)
                                }
                                .onFailure { error ->
                                    if (error !is InstallPermissionRequiredException) {
                                        reportSettingsFailure("settings.update.install.resume", error)
                                        needsInstallPermission = null
                                        installFailure = error.message ?: "安装器启动失败"
                                    }
                                }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                AlertDialog(
                    onDismissRequest = { needsInstallPermission = null },
                    title = { Text("需要允许安装应用") },
                    text = {
                        Text(
                            "系统当前不允许 Rikkahub-M 安装应用，所以点击更新后没有弹出安装界面。\n\n" +
                                "请在下一页里为 Rikkahub-M 打开「允许安装未知应用」，返回后会自动继续安装。"
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { UpdateInstaller.openInstallPermissionSettings(context) }) {
                            Text("去设置")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { needsInstallPermission = null }) { Text("稍后") }
                    },
                )
            }

            installFailure?.let { message ->
                AlertDialog(
                    onDismissRequest = { installFailure = null },
                    title = { Text("更新未完成") },
                    text = { Text(message) },
                    confirmButton = {
                        TextButton(onClick = { installFailure = null }) { Text("知道了") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            installFailure = null
                            runCatchingCancellable { context.openUrl("https://github.com/cs9527h/Rikkahub-M/releases") }
                                .onFailure { reportSettingsFailure("settings.update.release-page", it) }
                        }) { Text("打开发布页") }
                    },
                )
            }

            pendingUpdate?.let { info ->
                AlertDialog(
                    onDismissRequest = { pendingUpdate = null },
                    title = { Text(if (info.prerelease) "发现预览版本 ${info.version}" else "发现新版本 ${info.version}") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = info.summary.ifBlank {
                                    "更新包会先校验 SHA-256、包名、版本号和签名，再交给系统安装。"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val notificationProblem by updateChecker.notificationProblem.collectAsStateWithLifecycle()
                            notificationProblem?.let { warning ->
                                Text(
                                    text = warning,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                            when (val state = downloadState) {
                                is UpdateDownloadState.Running -> {
                                    val fraction = state.fraction
                                    if (fraction == null) {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                        Text(
                                            "已下载 ${formatByteSize(state.read)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    } else {
                                        LinearProgressIndicator(
                                            progress = { fraction },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Text(
                                            "${formatByteSize(state.read)} / ${formatByteSize(state.total)} · ${(fraction * 100).toInt()}%" +
                                                if (state.source.isNotBlank()) " · 来源 ${state.source}" else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                is UpdateDownloadState.Preparing -> {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Text(
                                        "正在连接下载源…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                is UpdateDownloadState.Verifying -> {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Text(
                                        "正在校验 SHA-256、包名、版本号和签名…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                is UpdateDownloadState.Failed -> Text(
                                    "下载失败：${state.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                is UpdateDownloadState.Ready -> Text(
                                    "校验通过，正在打开系统安装器",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                UpdateDownloadState.Idle -> {}
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { navController.navigate(Screen.MateChangelog) }) {
                                    Text("查看完整更新日志")
                                }
                                TextButton(onClick = {
                                    updateChecker.ignoreVersion(info.version)
                                    pendingUpdate = null
                                }) {
                                    Text("跳过此版本")
                                }
                            }
                        }
                    },
                    confirmButton = {
                        when (val state = downloadState) {
                            is UpdateDownloadState.Preparing,
                            is UpdateDownloadState.Running,
                            is UpdateDownloadState.Verifying
                            -> TextButton(onClick = { updateChecker.cancelDownload() }) { Text("取消下载") }

                            is UpdateDownloadState.Failed -> Row {
                                TextButton(onClick = {
                                    val page = downloadState.let { (it as? UpdateDownloadState.Failed)?.pageUrl }
                                        ?: "https://github.com/cs9527h/Rikkahub-M/releases"
                                    runCatchingCancellable { context.openUrl(page) }
                                        .onFailure { reportSettingsFailure("settings.update.browser", it) }
                                }) { Text("浏览器下载") }
                                TextButton(onClick = { updateChecker.retryDownload() }) { Text("重试") }
                            }

                            is UpdateDownloadState.Ready ->
                                // 系统安装器被取消后还能再点一次，不会因为状态被清空而只能重新下载
                                TextButton(onClick = {
                                    val ready = downloadState as? UpdateDownloadState.Ready ?: return@TextButton
                                    val result = runCatchingCancellable { UpdateInstaller.startSystemInstall(context, ready.apk) }
                                    val error = result.exceptionOrNull()
                                    if (error != null && error !is InstallPermissionRequiredException) {
                                        reportSettingsFailure("settings.update.install", error)
                                    }
                                    if (error is InstallPermissionRequiredException) {
                                        needsInstallPermission = error.apk
                                    } else if (error != null) {
                                        installFailure = error.message ?: "无法打开系统安装器"
                                    }
                                }) { Text("安装") }

                            UpdateDownloadState.Idle ->
                                TextButton(onClick = {
                                    updateChecker.startDownload(info)
                                    if (needsNotificationPermission) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }) { Text("立即更新") }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingUpdate = null }) {
                            Text(
                                when (downloadState) {
                                    is UpdateDownloadState.Idle, is UpdateDownloadState.Failed -> "稍后"
                                    else -> "后台下载"
                                }
                            )
                        }
                    },
                )
            }

            // 下载完成：只在这里拉起一次系统安装器，失败按权限/其他原因分别给提示
            val readyState = downloadState as? UpdateDownloadState.Ready
            LaunchedEffect(readyState) {
                val ready = readyState ?: return@LaunchedEffect
                val result = runCatchingCancellable { UpdateInstaller.startSystemInstall(context, ready.apk) }
                val error = result.exceptionOrNull()
                if (error != null && error !is InstallPermissionRequiredException) reportSettingsFailure("settings.update.install", error)
                if (error != null) {
                    pendingUpdate = null
                    if (error is InstallPermissionRequiredException) {
                        needsInstallPermission = error.apk
                    } else {
                        installFailure = error.message ?: "未知错误"
                    }
                }
            }
        }
    }
}

