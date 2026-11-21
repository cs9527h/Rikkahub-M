package me.rerere.rikkahub.ui.pages.setting

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import me.rerere.hugeicons.stroke.Github
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
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
import me.rerere.hugeicons.stroke.Download01
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.easteregg.EmojiBurstHost

@Composable
fun SettingAboutPage() {
    val updateChecker = koinInject<me.rerere.rikkahub.utils.UpdateChecker>()
    val toaster = me.rerere.rikkahub.ui.context.LocalToaster.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var checking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var pendingUpdate by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<me.rerere.rikkahub.utils.UpdateInfo?>(null) }
    var installing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var installStage by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val context = LocalContext.current
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
                                    if (checking) {
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
                            supportingContent = { Text("versionCode ${BuildConfig.VERSION_CODE} · 点击检查更新") },
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
                            supportingContent = { Text("AGPL-3.0 · 上游 RikkaHub 版权保留") },
                            headlineContent = { Text(stringResource(R.string.about_page_license)) },
                        )
                    }
                }
            }

            pendingUpdate?.let { info ->
                AlertDialog(
                    onDismissRequest = { if (!installing) pendingUpdate = null },
                    title = { Text(if (info.prerelease) "发现预览版本 ${info.version}" else "发现新版本 ${info.version}") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (installStage.isNullOrBlank()) "更新包会先校验 SHA-256、包名、版本号和签名，再交给系统安装。" else installStage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MarkdownBlock(
                                content = info.changelog.ifBlank { "该版本没有填写更新说明。" },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = !installing,
                            onClick = {
                                installing = true
                                installStage = "正在下载更新包…"
                                scope.launch {
                                    val result = updateChecker.installUpdate(info)
                                    installing = false
                                    if (result.isSuccess) {
                                        installStage = "校验通过，请在系统弹窗中确认安装"
                                        pendingUpdate = null
                                    } else {
                                        installStage = "下载或校验失败：" + (result.exceptionOrNull()?.message ?: "未知错误")
                                    }
                                }
                            },
                        ) { Text("立即更新") }
                    },
                    dismissButton = {
                        TextButton(enabled = !installing, onClick = { pendingUpdate = null }) { Text("稍后") }
                    },
                )
            }
        }
    }
}

