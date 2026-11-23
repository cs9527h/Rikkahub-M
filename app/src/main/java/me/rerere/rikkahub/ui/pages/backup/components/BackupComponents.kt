package me.rerere.rikkahub.ui.pages.backup.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.ConnectedMultiChoiceButtons

// 「备份内容」多选项，本地、WebDAV、S3 共用
internal fun <T> CardGroupScope.backupContentItem(
    options: List<T>,
    selected: List<T>,
    onChange: (List<T>) -> Unit,
    label: @Composable (T) -> String,
) = formItem(
    headlineContent = { Text(stringResource(R.string.backup_page_backup_items)) },
) {
    ConnectedMultiChoiceButtons(
        options = options,
        selected = selected,
        onToggle = { option, checked ->
            onChange(if (checked) selected + option else selected - option)
        },
    ) { option, isSelected ->
        ConnectedChoiceLabel(
            text = label(option),
            selected = isSelected,
            icon = HugeIcons.Tick01,
        )
    }
}
