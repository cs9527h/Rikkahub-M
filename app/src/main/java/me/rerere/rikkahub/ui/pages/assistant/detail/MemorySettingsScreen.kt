package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import org.koin.compose.koinInject

/**
 * 记忆中心的状态区（首屏第一块）：三个开关决定聊天到底用不用记忆。
 *
 * [enableMemory] 关掉时后两项仍然显示当前值、只是不可点，用户能看清“关掉记忆之后共享库和自动
 * 做梦原来是什么状态”，不会以为是开关被吞了。
 */
@Composable
internal fun MemoryStatusSwitches(
    assistant: Assistant,
    onUpdateAssistant: (Assistant) -> Unit,
) {
    CardGroup(title = { Text("使用状态") }) {
        switchItem(
            checked = assistant.enableMemory,
            onCheckedChange = { onUpdateAssistant(assistant.copy(enableMemory = it)) },
            headlineContent = { Text("启用记忆") },
            supportingContent = { Text("聊天时自动带上相关的长期记忆。") },
        )
        switchItem(
            checked = assistant.useGlobalMemory,
            onCheckedChange = { onUpdateAssistant(assistant.copy(useGlobalMemory = it)) },
            enabled = assistant.enableMemory,
            headlineContent = { Text("使用共享记忆库") },
            supportingContent = {
                Text(
                    if (assistant.useGlobalMemory) {
                        "所有助手共用一份记忆。下面切换浏览范围不会改动这一项。"
                    } else {
                        "只读取当前助手自己的记忆。下面切换浏览范围不会改动这一项。"
                    },
                )
            },
        )
        switchItem(
            checked = assistant.autoConsolidateMemory,
            onCheckedChange = { onUpdateAssistant(assistant.copy(autoConsolidateMemory = it)) },
            enabled = assistant.enableMemory,
            headlineContent = { Text("自动做梦") },
            supportingContent = {
                Text(
                    if (assistant.autoConsolidateMemory) {
                        "空闲时自动把最近的聊天整理成记忆。"
                    } else {
                        "已关闭：只在点“智能整理”时整理。"
                    },
                )
            },
        )
    }
}

/**
 * 记忆中心的高级设置：低频项折叠在页面底部，展开/收起带过渡。
 *
 * [reduceMotion] 跟随系统“移除动画”：为 true 时不做高度与旋转动画，直接切换内容。
 */
@Composable
internal fun MemoryAdvancedSettings(
    assistant: Assistant,
    expanded: Boolean,
    reduceMotion: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    onOpenModels: () -> Unit,
) {
    val settingsStore: SettingsStore = koinInject()
    val scope = rememberCoroutineScope()
    var budgetDialog by remember { mutableStateOf<BudgetKind?>(null) }
    var budgetInput by remember { mutableStateOf("") }
    var timeDialog by remember(assistant.id) { mutableStateOf(false) }
    var timeInput by remember(assistant.id) { mutableStateOf("") }

    if (timeDialog) {
        val interval = timeInput.toIntOrNull()?.takeIf { it > 0 }
        AlertDialog(
            onDismissRequest = { timeDialog = false },
            title = { Text(stringResource(R.string.assistant_page_time_reminder_interval)) },
            text = {
                TextField(
                    value = timeInput,
                    onValueChange = { timeInput = it },
                    label = { Text(stringResource(R.string.assistant_page_time_reminder_interval_label)) },
                    supportingText = { Text(stringResource(R.string.assistant_page_time_reminder_interval_hint)) },
                    isError = interval == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(enabled = interval != null, onClick = {
                    interval?.let { onUpdateAssistant(assistant.copy(timeReminderIntervalMinutes = it)) }
                    timeDialog = false
                }) { Text(stringResource(R.string.assistant_page_save)) }
            },
            dismissButton = { TextButton(onClick = { timeDialog = false }) { Text(stringResource(R.string.assistant_page_cancel)) } },
        )
    }

    budgetDialog?.let { kind ->
        AlertDialog(
            onDismissRequest = { budgetDialog = null },
            title = { Text(if (kind == BudgetKind.RUN) "单次整理上限" else "每日整理预算") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("按估算 Token 计算，填 0 表示不限制。达到上限会自动暂停，不会擅自继续花钱。")
                    TextField(
                        value = budgetInput,
                        onValueChange = { budgetInput = it.filter { c -> c.isDigit() }.take(9) },
                        label = { Text("Token") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = budgetInput.toIntOrNull() ?: 0
                    scope.launch {
                        settingsStore.update {
                            if (kind == BudgetKind.RUN) it.copy(memoryRunTokenBudget = value)
                            else it.copy(memoryDailyTokenBudget = value)
                        }
                    }
                    budgetDialog = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { budgetDialog = null }) { Text("取消") } },
        )
    }

    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(220),
        label = "memory-advanced-chevron",
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardGroup {
            item(
                headlineContent = { Text("高级设置") },
                supportingContent = { Text("整理预算、整理模型、时间提醒") },
                trailingContent = {
                    Icon(
                        imageVector = HugeIcons.ArrowDown01,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp).rotate(rotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { onExpandedChange(!expanded) },
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (reduceMotion) {
                EnterTransition.None
            } else {
                expandVertically(animationSpec = tween(240)) + fadeIn(animationSpec = tween(160))
            },
            exit = if (reduceMotion) {
                ExitTransition.None
            } else {
                shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(120))
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardGroup {
                    item(
                        headlineContent = { Text("单次整理上限") },
                        supportingContent = { Text("超出后暂停，等你确认再继续。0 表示不限制。") },
                        onClick = {
                            budgetInput = ""
                            budgetDialog = BudgetKind.RUN
                        },
                    )
                    item(
                        headlineContent = { Text("每日整理预算") },
                        supportingContent = { Text("超过后当天不再自动整理。0 表示不限制。") },
                        onClick = {
                            budgetInput = ""
                            budgetDialog = BudgetKind.DAY
                        },
                    )
                    val budgets = listOf(500, 1_000, 2_000, 4_000)
                    val budget = assistant.memoryRecallTokenBudget.coerceIn(500, 4_000)
                    item(
                        headlineContent = { Text("召回预算 · 约 $budget Token") },
                        supportingContent = { Text("点击更换。用于每次注入的正文片段或单次工具读取。") },
                        onClick = {
                            val next = budgets[(budgets.indexOf(budget) + 1) % budgets.size]
                            onUpdateAssistant(assistant.copy(memoryRecallTokenBudget = next))
                        },
                    )
                    item(
                        headlineContent = { Text("记忆整理模型") },
                        supportingContent = { Text("整理历史聊天时使用的模型，未配置时使用快速模型或聊天模型。") },
                        onClick = onOpenModels,
                    )
                }
                CardGroup {
                    switchItem(
                        checked = assistant.enableTimeReminder,
                        onCheckedChange = { onUpdateAssistant(assistant.copy(enableTimeReminder = it)) },
                        supportingContent = { Text(stringResource(R.string.assistant_page_time_reminder_desc)) },
                        headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder)) },
                    )
                    if (assistant.enableTimeReminder) {
                        item(
                            headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder_interval)) },
                            supportingContent = { Text(stringResource(R.string.assistant_page_time_reminder_interval_desc)) },
                            trailingContent = {
                                Text(
                                    text = stringResource(
                                        R.string.assistant_page_time_reminder_interval_value,
                                        assistant.timeReminderIntervalMinutes,
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                            onClick = {
                                timeInput = assistant.timeReminderIntervalMinutes.toString()
                                timeDialog = true
                            },
                        )
                    }
                }
                CardGroup {
                    switchItem(
                        checked = assistant.enableRecentChatsReference,
                        onCheckedChange = { onUpdateAssistant(assistant.copy(enableRecentChatsReference = it)) },
                        supportingContent = { Text(stringResource(R.string.assistant_page_recent_chats_desc)) },
                        headlineContent = { Text(stringResource(R.string.assistant_page_recent_chats)) },
                    )
                    switchItem(
                        checked = assistant.recordMemoryUsage,
                        onCheckedChange = { onUpdateAssistant(assistant.copy(recordMemoryUsage = it)) },
                        enabled = assistant.enableMemory,
                        headlineContent = { Text("保存记忆使用记录") },
                        supportingContent = { Text("在本机保留最近 50 轮的检索和注入内容。") },
                    )
                }
            }
        }
    }
}

private enum class BudgetKind { RUN, DAY }
