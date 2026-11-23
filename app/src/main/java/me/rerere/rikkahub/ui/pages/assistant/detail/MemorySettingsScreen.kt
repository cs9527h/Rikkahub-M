package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import org.koin.compose.koinInject
import androidx.compose.ui.res.stringResource

/**
 * 记忆中心复用的设置组件：基础开关常驻，高级选项按需展开。
 */
@Composable
internal fun MemorySettingsScreen(
    assistant: Assistant,
    showAdvanced: Boolean = false,
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "记忆默认只属于当前助手。改成共享后，所有助手会看到同一份记忆，请确认内容可以共用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CardGroup {
            switchItem(
                checked = assistant.enableMemory,
                onCheckedChange = { onUpdateAssistant(assistant.copy(enableMemory = it)) },
                supportingContent = { Text("开启后，聊天会自动带上相关的记忆。") },
                headlineContent = { Text(stringResource(R.string.assistant_page_memory)) },
            )
            switchItem(
                checked = assistant.useGlobalMemory,
                onCheckedChange = { onUpdateAssistant(assistant.copy(useGlobalMemory = it)) },
                enabled = assistant.enableMemory,
                supportingContent = { Text(if (assistant.useGlobalMemory) "当前：所有助手共享同一份记忆。" else "当前：只有这个助手使用自己的记忆。") },
                headlineContent = { Text("所有助手共享记忆") },
            )
            switchItem(
                checked = assistant.autoConsolidateMemory,
                onCheckedChange = { onUpdateAssistant(assistant.copy(autoConsolidateMemory = it)) },
                enabled = assistant.enableMemory,
                supportingContent = { Text(stringResource(R.string.assistant_page_memory_auto_dream_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_memory_auto_dream)) },
            )
        }
        if (showAdvanced) {
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
            switchItem(
                checked = assistant.recordMemoryUsage,
                onCheckedChange = { onUpdateAssistant(assistant.copy(recordMemoryUsage = it)) },
                enabled = assistant.enableMemory,
                headlineContent = { Text("保存记忆使用记录") },
                supportingContent = { Text("在本机保留最近 50 轮的检索和注入内容。") },
            )
            item(
                headlineContent = { Text("记忆整理模型") },
                supportingContent = { Text("整理历史聊天时使用的模型，未配置时使用快速模型或聊天模型。") },
                onClick = onOpenModels,
            )
            switchItem(
                checked = assistant.enableRecentChatsReference,
                onCheckedChange = { onUpdateAssistant(assistant.copy(enableRecentChatsReference = it)) },
                supportingContent = { Text(stringResource(R.string.assistant_page_recent_chats_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_recent_chats)) },
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
        }
    }
}

private enum class BudgetKind { RUN, DAY }
