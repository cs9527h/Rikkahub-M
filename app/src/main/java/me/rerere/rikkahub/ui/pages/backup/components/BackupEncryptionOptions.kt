package me.rerere.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.ui.toast.ToastType

/**
 * 日常只显示“已开启/未开启”，不出现口令输入框。
 * 只有用户主动设置或更换恢复口令时才弹出一次对话框。
 */
@Composable
fun BackupEncryptionOptions(vm: BackupVM) {
    val encrypted by vm.encryptBackups.collectAsStateWithLifecycle()
    val keyConfigured by vm.automaticPassphraseConfigured.collectAsStateWithLifecycle()
    val includeCredentials by vm.includeBackupCredentials.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var setupDialog by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }

    CardGroup(title = { Text(stringResource(R.string.backup_page_security)) }) {
        switchItem(
            checked = encrypted && keyConfigured,
            onCheckedChange = { enabled ->
                when {
                    !enabled -> confirmDisable = true
                    keyConfigured -> vm.encryptBackups.value = true
                    else -> setupDialog = true
                }
            },
            headlineContent = { Text(stringResource(R.string.backup_page_encrypt)) },
            supportingContent = {
                Text(
                    stringResource(
                        when {
                            encrypted && keyConfigured -> R.string.backup_page_encryption_active
                            else -> R.string.backup_page_encryption_off
                        }
                    )
                )
            },
        )
        if (encrypted && keyConfigured) {
            switchItem(
                checked = includeCredentials,
                onCheckedChange = { vm.includeBackupCredentials.value = it },
                headlineContent = { Text(stringResource(R.string.backup_page_include_credentials)) },
                supportingContent = { Text(stringResource(R.string.backup_page_credentials_warning)) },
            )
            formItem(
                headlineContent = { Text(stringResource(R.string.backup_page_key_ready_title)) },
                supportingContent = { Text(stringResource(R.string.backup_page_local_key_desc)) },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { setupDialog = true }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.backup_page_change_key))
                    }
                    TextButton(onClick = { confirmDisable = true }) {
                        Text(stringResource(R.string.backup_page_disable_key))
                    }
                }
            }
        }
        if (!encrypted || !keyConfigured) {
            formItem(
                headlineContent = { Text(stringResource(R.string.backup_page_plaintext_warning)) },
                supportingContent = { Text(stringResource(R.string.backup_page_passphrase_hint)) },
            ) {
                FilledTonalButton(onClick = { setupDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.backup_page_setup_key))
                }
            }
        }
    }

    if (setupDialog) {
        PassphraseDialog(
            onDismiss = { setupDialog = false },
            onConfirm = { passphrase ->
                setupDialog = false
                scope.launch {
                    try {
                        vm.configureBackupKey(passphrase)
                        toaster.show(resources.getString(R.string.backup_page_key_saved), type = ToastType.Success)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        toaster.show(resources.getString(R.string.backup_page_key_failed), type = ToastType.Error)
                    }
                }
            },
        )
    }

    if (confirmDisable) {
        AlertDialog(
            onDismissRequest = { confirmDisable = false },
            title = { Text(stringResource(R.string.backup_page_disable_key)) },
            text = { Text(stringResource(R.string.backup_page_forget_key_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisable = false
                    scope.launch { vm.disableBackupEncryption() }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisable = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** 仅设置/更换恢复口令时出现；日常备份与恢复不再要求输入。 */
@Composable
private fun PassphraseDialog(
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val tooShort = value.length in 1..7
    val mismatch = confirmation.isNotEmpty() && confirmation != value
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_page_setup_key)) },
        text = {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.backup_page_local_key_desc))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.backup_page_passphrase)) },
                    isError = tooShort,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text(stringResource(R.string.backup_page_confirm_passphrase)) },
                    isError = mismatch,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = value.length in 8..1024 && value == confirmation,
                onClick = { onConfirm(value.toCharArray()) },
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
