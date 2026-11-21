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

/** Transient manual recovery input; local automatic-backup escrow requires an explicit action. */
@Composable
fun BackupEncryptionOptions(vm: BackupVM) {
    val encrypted by vm.encryptBackups.collectAsStateWithLifecycle()
    val passphrase by vm.backupPassphrase.collectAsStateWithLifecycle()
    val confirmation by vm.backupPassphraseConfirmation.collectAsStateWithLifecycle()
    val includeCredentials by vm.includeBackupCredentials.collectAsStateWithLifecycle()
    val keyConfigured by vm.automaticPassphraseConfigured.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var keyBusy by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }

    fun updateKey(forget: Boolean) {
        scope.launch {
            keyBusy = true
            try {
                if (forget) vm.forgetAutomaticBackupPassphrase() else vm.savePassphraseForAutomaticBackup()
                toaster.show(
                    context.getString(
                        if (forget) R.string.backup_page_key_forgotten else R.string.backup_page_key_saved
                    ),
                    type = ToastType.Success,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                toaster.show(context.getString(R.string.backup_page_key_failed), type = ToastType.Error)
            } finally {
                keyBusy = false
            }
        }
    }

    CardGroup(title = { Text(stringResource(R.string.backup_page_security)) }) {
        switchItem(
            checked = encrypted,
            onCheckedChange = {
                vm.encryptBackups.value = it
                if (!it) vm.includeBackupCredentials.value = false
            },
            headlineContent = { Text(stringResource(R.string.backup_page_encrypt)) },
            supportingContent = {
                Text(
                    stringResource(
                        if (encrypted) {
                            R.string.backup_page_encryption_warning
                        } else {
                            R.string.backup_page_plaintext_warning
                        }
                    )
                )
            },
        )
        formItem {
            OutlinedTextField(
                value = passphrase,
                onValueChange = { vm.backupPassphrase.value = it },
                label = { Text(stringResource(R.string.backup_page_passphrase)) },
                supportingText = { Text(stringResource(R.string.backup_page_passphrase_hint)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (encrypted) {
            formItem {
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { vm.backupPassphraseConfirmation.value = it },
                    label = { Text(stringResource(R.string.backup_page_confirm_passphrase)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = confirmation.isNotEmpty() && confirmation != passphrase,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            switchItem(
                checked = includeCredentials,
                onCheckedChange = { vm.includeBackupCredentials.value = it },
                headlineContent = { Text(stringResource(R.string.backup_page_include_credentials)) },
                supportingContent = { Text(stringResource(R.string.backup_page_credentials_warning)) },
            )
        }
        formItem(
            headlineContent = {
                Text(
                    stringResource(
                        if (keyConfigured) {
                            R.string.backup_page_auto_key_ready
                        } else {
                            R.string.backup_page_auto_key_missing
                        }
                    )
                )
            },
            supportingContent = { Text(stringResource(R.string.backup_page_local_key_desc)) },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    enabled = !keyBusy && encrypted && passphrase.length in 8..1024 && passphrase == confirmation,
                    onClick = { updateKey(forget = false) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.backup_page_store_auto_passphrase))
                }
                if (keyConfigured) {
                    TextButton(enabled = !keyBusy, onClick = { confirmForget = true }) {
                        Text(stringResource(R.string.backup_page_forget_key))
                    }
                }
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.backup_page_forget_key)) },
            text = { Text(stringResource(R.string.backup_page_forget_key_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    updateKey(forget = true)
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
