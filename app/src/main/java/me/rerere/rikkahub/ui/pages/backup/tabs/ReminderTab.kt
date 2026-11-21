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
import me.rerere.rikkahub.data.sync.AutoBackupPolicy
import me.rerere.rikkahub.data.sync.BackupKind
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
private val AutoSettingsIntervals = listOf(6, 12, 24)
private val AutoChatsIntervals = listOf(24, 72, 168)
private val AutoRetainOptions = listOf(0, 5, 10, 20)

@Composable
fun ReminderTab(vm: BackupVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.backupReminderConfig
    val keyConfigured by vm.automaticPassphraseConfigured.collectAsStateWithLifecycle()
    // CardGroup's content is a builder, not a composable lambda: resolve labels before it.
    val webDavLabel = stringResource(R.string.backup_page_auto_transport_webdav)
    val s3Label = stringResource(R.string.backup_page_auto_transport_s3)
    val settingsLabel = stringResource(R.string.backup_page_auto_snapshot_settings)
    val chatsLabel = stringResource(R.string.backup_page_auto_snapshot_chats)

    fun updateConfig(update: (BackupReminderConfig) -> BackupReminderConfig) {
        vm.updateSettings { it.copy(backupReminderConfig = update(it.backupReminderConfig)) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        me.rerere.rikkahub.ui.pages.backup.components.BackupEncryptionOptions(vm)
        CardGroup {
            switchItem(
                checked = config.autoBackupOnAppLifecycle,
                enabled = keyConfigured || config.autoBackupOnAppLifecycle,
                onCheckedChange = { enabled ->
                    updateConfig { it.copy(autoBackupOnAppLifecycle = enabled) }
                },
                headlineContent = { Text(stringResource(R.string.backup_page_auto_backup)) },
                supportingContent = { Text(stringResource(R.string.backup_page_auto_desc)) },
            )
            switchItem(
                checked = config.autoBackupIncludeCredentials,
                onCheckedChange = { include ->
                    updateConfig { it.copy(autoBackupIncludeCredentials = include, autoBackupStates = emptyMap()) }
                },
                headlineContent = { Text(stringResource(R.string.backup_page_auto_include_credentials)) },
                supportingContent = { Text(stringResource(R.string.backup_page_credentials_warning)) },
            )
            if (config.autoBackupOnAppLifecycle) {
                formItem(
                    headlineContent = { Text(stringResource(R.string.backup_page_auto_settings_interval)) },
                    trailingContent = {
                        CardGroupValue(
                            stringResource(R.string.backup_page_auto_interval_hours, config.autoSettingsIntervalHours)
                        )
                    },
                ) {
                    ConnectedChoiceButtons(
                        options = AutoSettingsIntervals,
                        selected = config.autoSettingsIntervalHours,
                        onSelect = { hours -> updateConfig { it.copy(autoSettingsIntervalHours = hours) } },
                    ) { hours, selected -> ConnectedChoiceLabel(text = hours.toString(), selected = selected) }
                }
                formItem(
                    headlineContent = { Text(stringResource(R.string.backup_page_auto_chats_interval)) },
                    trailingContent = {
                        CardGroupValue(
                            stringResource(R.string.backup_page_auto_interval_hours, config.autoChatsIntervalHours)
                        )
                    },
                ) {
                    ConnectedChoiceButtons(
                        options = AutoChatsIntervals,
                        selected = config.autoChatsIntervalHours,
                        onSelect = { hours -> updateConfig { it.copy(autoChatsIntervalHours = hours) } },
                    ) { hours, selected -> ConnectedChoiceLabel(text = hours.toString(), selected = selected) }
                }
                formItem(
                    headlineContent = { Text(stringResource(R.string.backup_page_auto_retention)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_auto_retention_desc)) },
                    trailingContent = {
                        CardGroupValue(
                            if (config.autoRetainVersions == 0) {
                                stringResource(R.string.backup_page_auto_retention_all)
                            } else {
                                stringResource(
                                    R.string.backup_page_auto_retention_keep,
                                    config.autoRetainVersions,
                                )
                            }
                        )
                    },
                ) {
                    ConnectedChoiceButtons(
                        options = AutoRetainOptions,
                        selected = config.autoRetainVersions,
                        onSelect = { keep -> updateConfig { it.copy(autoRetainVersions = keep) } },
                    ) { keep, selected ->
                        ConnectedChoiceLabel(
                            text = if (keep == 0) "∞" else keep.toString(),
                            selected = selected,
                        )
                    }
                }
                for (transport in listOf("webdav", "s3")) {
                    for (kind in listOf(BackupKind.SETTINGS, BackupKind.CHATS)) {
                        val key = AutoBackupPolicy.key(transport, kind)
                        val successAt = config.autoBackupStates[key]?.lastSuccessAt ?: 0L
                        val destination = if (transport == "webdav") webDavLabel else s3Label
                        val category = if (kind == BackupKind.SETTINGS) settingsLabel else chatsLabel
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.backup_page_auto_snapshot_status, destination, category))
                            },
                            supportingContent = {
                                Text(
                                    if (successAt == 0L) {
                                        stringResource(R.string.backup_page_auto_snapshot_never)
                                    } else {
                                        Instant.ofEpochMilli(successAt).toLocalDateTime().toString()
                                    }
                                )
                            },
                        )
                    }
                }
            }
            item(
                headlineContent = {
                    Text(
                        if (config.lastAutoBackupTime == 0L) {
                            stringResource(R.string.backup_page_auto_never)
                        } else {
                            stringResource(
                                R.string.backup_page_auto_last_success,
                                Instant.ofEpochMilli(config.lastAutoBackupTime).toLocalDateTime(),
                            )
                        }
                    )
                },
                supportingContent = if (config.lastAutoBackupError.isNotBlank()) {
                    { Text(stringResource(R.string.backup_page_auto_failed, config.lastAutoBackupError)) }
                } else {
                    null
                },
                leadingContent = { CardGroupIcon(HugeIcons.Clock01) },

            )
            switchItem(
                checked = config.enabled,
                onCheckedChange = { enabled -> updateConfig { it.copy(enabled = enabled) } },
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
                        onSelect = { days -> updateConfig { it.copy(intervalDays = days) } },
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
