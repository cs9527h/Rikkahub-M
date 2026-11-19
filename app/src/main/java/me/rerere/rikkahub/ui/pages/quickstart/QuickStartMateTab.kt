package me.rerere.rikkahub.ui.pages.quickstart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController

/**
 * A truthful introduction and direct, always-available pairing path.
 * The separate Mate gateway is NOT yet integrated into ChatService.
 */
@Composable
internal fun QuickStartMateTab() {
    val nav = LocalNavController.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.mate_intro_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.mate_intro_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CardGroup(title = { Text(stringResource(R.string.mate_intro_connection_heading)) }) {
            formItem {
                Text(stringResource(R.string.mate_intro_connection_detail))
                Button(
                    onClick = { nav.navigate(Screen.MateChat) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("进入聊天")
                }
                Button(
                    onClick = { nav.navigate(Screen.MateConnection) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.mate_intro_connect_button))
                }
            }
        }
        CardGroup(title = { Text(stringResource(R.string.mate_intro_progress_heading)) }) {
            formItem {
                Text(stringResource(R.string.mate_intro_progress_detail))
            }
        }
        CardGroup(title = { Text(stringResource(R.string.mate_intro_old_app_heading)) }) {
            formItem {
                Text(stringResource(R.string.mate_intro_old_app_detail))
            }
        }
        Text(
            stringResource(R.string.mate_intro_origin),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
