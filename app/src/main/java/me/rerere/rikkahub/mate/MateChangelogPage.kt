package me.rerere.rikkahub.mate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * 更新日志直接读本仓库的 GitHub Releases，和“检查更新”用同一份数据；
 * 只有离线时才回退到内置的当前版本说明，不再维护第二份会过期的版本列表。
 */
@Composable
fun MateChangelogPage() {
    val checker = koinInject<UpdateChecker>()
    var notes by remember { mutableStateOf<List<ReleaseNote>?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { checker.releaseNotes() }
            .onSuccess { notes = it.filterNot { note -> note.prerelease } }
            .onFailure { failed = true }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Rikkahub-M 更新日志") }, navigationIcon = { BackButton() })
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("当前版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
            }
            val list = notes
            when {
                list == null && !failed -> item { CircularProgressIndicator() }
                list.isNullOrEmpty() -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("暂时无法获取发布记录", style = MaterialTheme.typography.titleMedium)
                        Text("请检查网络后重新打开。当前版本 ${BuildConfig.VERSION_NAME}。", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> items(list) { note ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(note.tag.removePrefix("v"), style = MaterialTheme.typography.titleMedium)
                        MarkdownBlock(content = note.body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
