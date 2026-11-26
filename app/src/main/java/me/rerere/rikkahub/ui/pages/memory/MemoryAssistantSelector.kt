package me.rerere.rikkahub.ui.pages.memory

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.UserCircle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.AssistantPickerSheet
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.UIAvatar

@Composable
internal fun MemoryAssistantSelector(
    settings: Settings,
    assistant: Assistant,
    enabled: Boolean,
    onSelect: (Assistant) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }
    val ready = enabled && settings.assistants.any { it.id == assistant.id }
    CardGroup(title = { Text("设置助手") }) {
        item(
            onClick = if (ready) ({ showPicker = true }) else null,
            headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text("查看和设置此助手的记忆，不切换当前聊天助手。") },
            leadingContent = { UIAvatar(name = name, value = assistant.avatar, modifier = Modifier.size(36.dp)) },
            trailingContent = { Icon(HugeIcons.ArrowDown01, contentDescription = "选择要设置的助手", modifier = Modifier.size(20.dp)) },
        )
    }
    if (showPicker) {
        val choose: (Assistant) -> Unit = { selected ->
            showPicker = false
            // A removed entry must not silently open the current chat assistant instead.
            settings.assistants.find { it.id == selected.id }?.let(onSelect)
        }
        AssistantPickerSheet(
            settings = settings,
            currentAssistant = assistant,
            onAssistantSelected = choose,
            onEditAssistant = choose,
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
internal fun MemoryAssistantUnavailable(
    settings: Settings,
    requestedId: String?,
    onSelect: (Assistant) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    ExtensionEmptyState(
        message = "所选助手暂不可用",
        description = "助手可能已被删除或尚未载入。请选择其他助手，页面不会自动改成别人的记忆设置。",
        buttonText = if (settings.assistants.isNotEmpty()) "选择其他助手" else null,
        onAction = { showPicker = true },
        icon = HugeIcons.UserCircle,
    )
    if (showPicker) {
        val unavailable = remember(requestedId) {
            Assistant(id = kotlin.uuid.Uuid.parseOrNull(requestedId.orEmpty()) ?: kotlin.uuid.Uuid.NIL)
        }
        val choose: (Assistant) -> Unit = { selected ->
            showPicker = false
            settings.assistants.find { it.id == selected.id }?.let(onSelect)
        }
        AssistantPickerSheet(
            settings = settings,
            currentAssistant = unavailable,
            onAssistantSelected = choose,
            onEditAssistant = choose,
            onDismiss = { showPicker = false },
        )
    }
}
