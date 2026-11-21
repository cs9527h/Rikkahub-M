package me.rerere.rikkahub.mate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.utils.ReleaseNote
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

/**
 * 更新日志：内置当前版本说明始终可读；联网后合并 GitHub 与备用源的发布记录。
 * 已发布的预览版本照常展示并标注，但自动升级只挑正式版本。
 */
@Composable
fun MateChangelogPage() {
    val checker = koinInject<UpdateChecker>()
    var notes by remember { mutableStateOf<List<ReleaseNote>?>(null) }
    var online by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        notes = null
        val result = runCatching { checker.releaseNotes() }.getOrDefault(emptyList())
        online = result.any { it.source != "bundled" }
        notes = result
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Rikkahub-M 更新日志") },
            navigationIcon = { BackButton() },
            actions = {
                TextButton(onClick = { reloadKey++ }) { Text("刷新") }
            },
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("当前版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
                    if (!online && notes != null) {
                        Text(
                            "暂时无法连接发布源，下面是内置说明。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val list = notes
            when {
                list == null -> item { CircularProgressIndicator() }
                list.isEmpty() -> item { Text("没有可显示的版本记录。", style = MaterialTheme.typography.bodyMedium) }
                else -> items(list, key = { it.version }) { note ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(note.version, style = MaterialTheme.typography.titleMedium)
                            if (note.version == BuildConfig.VERSION_NAME) {
                                StatusChip("当前版本", MaterialTheme.colorScheme.primaryContainer)
                            } else if (note.prerelease) {
                                StatusChip("预览", MaterialTheme.colorScheme.secondaryContainer)
                            } else {
                                StatusChip("正式", MaterialTheme.colorScheme.tertiaryContainer)
                            }
                        }
                        if (note.body.isBlank()) {
                            Text("该版本没有填写更新说明。", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            MarkdownBlock(content = note.body, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
