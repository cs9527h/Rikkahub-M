package me.rerere.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.pages.backup.BackupVM

/** One transient passphrase shared by local, WebDAV and S3 operations on this page. */
@Composable
fun BackupEncryptionOptions(vm: BackupVM) {
    val encrypted by vm.encryptBackups.collectAsStateWithLifecycle()
    val passphrase by vm.backupPassphrase.collectAsStateWithLifecycle()
    val confirmation by vm.backupPassphraseConfirmation.collectAsStateWithLifecycle()
    val includeCredentials by vm.includeBackupCredentials.collectAsStateWithLifecycle()
    CardGroup(title = { Text(stringResource(R.string.backup_page_security)) }) {
        switchItem(
            checked = encrypted,
            onCheckedChange = {
                vm.encryptBackups.value = it
                if (!it) vm.includeBackupCredentials.value = false
            },
            headlineContent = { Text(stringResource(R.string.backup_page_encrypt)) },
            supportingContent = {
                Text(stringResource(if (encrypted) R.string.backup_page_encryption_warning else R.string.backup_page_plaintext_warning))
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
    }
}
