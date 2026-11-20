package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.BackupReminderConfig
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.utils.toLocalDateTime
import java.time.Instant

private val ReminderIntervals = listOf(1, 3, 7, 14, 30)

@Composable
fun ReminderTab(vm: BackupVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.backupReminderConfig

    fun updateConfig(update: BackupReminderConfig) {
        vm.updateSettings { it.copy(backupReminderConfig = update) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CardGroup {
            switchItem(
                checked = config.autoBackupOnAppLifecycle,
                onCheckedChange = {
                    updateConfig(config.copy(autoBackupOnAppLifecycle = it))
                },
                headlineContent = { Text("打开及退出时备份设置") },
                supportingContent = {
                    Text("仅备份设置、助手配置及云服务器地址信息，不含聊天数据库或附件。云端备份可能包含私人配置，请使用可信的 HTTPS WebDAV 或 S3；设备授权恢复后需重新配对。")
                },
            )
            formItem(
                headlineContent = { Text("聊天记录自动备份") },
                supportingContent = { Text("聊天数据库较大，单独按周期上传；不包含图片或附件。关闭后仍可手动备份。") },
                trailingContent = {
                    CardGroupValue(
                        if (config.databaseBackupIntervalDays == 0) "关闭"
                        else "每 " + config.databaseBackupIntervalDays + " 天"
                    )
                },
            ) {
                ConnectedChoiceButtons(
                    options = listOf(0, 7, 14, 30),
                    selected = config.databaseBackupIntervalDays,
                    onSelect = { updateConfig(config.copy(databaseBackupIntervalDays = it)) },
                ) { days, selected ->
                    ConnectedChoiceLabel(if (days == 0) "关闭" else days.toString(), selected)
                }
            }
            switchItem(
                checked = config.enabled,
                onCheckedChange = { updateConfig(config.copy(enabled = it)) },
                headlineContent = { Text(stringResource(R.string.backup_page_reminder_enable)) },
            )

            if (config.enabled) {
                formItem(
                    headlineContent = { Text(stringResource(R.string.backup_page_reminder_interval)) },
                    trailingContent = {
                        CardGroupValue(
                            stringResource(R.string.backup_page_reminder_interval_days, config.intervalDays)
                        )
                    },
                ) {
                    // 五个按钮并排，按钮里只放数字，带单位的完整取值显示在标题行右侧
                    ConnectedChoiceButtons(
                        options = ReminderIntervals,
                        selected = config.intervalDays,
                        onSelect = { updateConfig(config.copy(intervalDays = it)) },
                    ) { days, selected ->
                        ConnectedChoiceLabel(text = days.toString(), selected = selected)
                    }
                }

                item(
                    headlineContent = {
                        Text(
                            if (config.lastBackupTime == 0L) {
                                stringResource(R.string.backup_page_reminder_no_record)
                            } else {
                                stringResource(
                                    R.string.backup_page_reminder_last_time,
                                    Instant.ofEpochMilli(config.lastBackupTime).toLocalDateTime()
                                )
                            }
                        )
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.Clock01) },
                )
            }
        }
    }
}
