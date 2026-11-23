package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 助手详情里的「记忆」：只保留这个助手要不要记忆、用哪份记忆这类使用设置。
 *
 * 记住了什么、待确认、整理历史这些管理动作全都搬到了记忆中心（[Screen.MemoryCenter]）。
 * 之前两边是同一个页面，同一个功能有两套入口，用户不知道该在哪一边改。
 */
@Composable
fun AssistantMemoryPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val settingsScrollState = rememberScrollState()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("记忆") },
                subtitle = { Text("使用设置") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            MemoryCenterEntry(
                onOpen = { navController.navigate(Screen.MemoryCenter(assistantId = id)) },
            )
            Box(Modifier.weight(1f)) {
                MemorySettingsScreen(
                    assistant = assistant,
                    scrollState = settingsScrollState,
                    onUpdateAssistant = { vm.update(it) },
                    onOpenModels = { navController.navigate(Screen.SettingModels) },
                )
            }
        }
    }
}

@Composable
private fun MemoryCenterEntry(onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Brain02,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "打开记忆中心",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "看记住了什么、确认待整理的内容、查看整理历史",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Icon(
                imageVector = HugeIcons.ArrowRight01,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
