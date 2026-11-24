package me.rerere.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import me.rerere.rikkahub.R

/**
 * 恢复加密备份时可选的输入框：不填就走本机保存的密钥；
 * 填了就用这个口令解密（适合「有口令、但没有本机密钥」的跨设备恢复）。
 */
class RestorePassphrasePromptState {
    var visible by mutableStateOf(false)
        private set

    private var pending: CompletableDeferred<CharArray?>? = null

    /** 挂起直到用户输入或取消；取消返回 null。 */
    suspend fun ask(): CharArray? {
        val deferred = CompletableDeferred<CharArray?>()
        pending = deferred
        visible = true
        return try {
            deferred.await()
        } finally {
            visible = false
            pending = null
        }
    }

    fun submit(value: CharArray) {
        pending?.complete(value)
        visible = false
    }

    fun dismiss() {
        pending?.complete(null)
        visible = false
    }
}

@Composable
fun rememberRestorePassphrasePrompt(): RestorePassphrasePromptState =
    remember { RestorePassphrasePromptState() }

@Composable
fun RestorePassphraseDialog(prompt: RestorePassphrasePromptState) {
    if (!prompt.visible) return
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { prompt.dismiss() },
        title = { Text(stringResource(R.string.backup_page_restore_key_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_page_restore_key_desc))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.backup_page_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = value.length >= 6,
                onClick = {
                    prompt.submit(value.toCharArray())
                    value = ""
                }
            ) { Text(stringResource(R.string.backup_page_restore)) }
        },
        dismissButton = {
            TextButton(onClick = {
                value = ""
                prompt.dismiss()
            }) { Text(stringResource(R.string.cancel)) }
        },
    )
}
