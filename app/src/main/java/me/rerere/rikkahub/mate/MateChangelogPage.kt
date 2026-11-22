package me.rerere.rikkahub.mate

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import me.rerere.rikkahub.utils.NoteSection
import me.rerere.rikkahub.utils.ReleaseNote
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.utils.UpdateNotes
import org.koin.compose.koinInject

/**
 * 更新日志：只显示用户可感知的变化，按「新增 / 优化 / 修复 / 其他」分类，空分类隐藏。
 * 内置说明离线可读且不会被联网刷新覆盖；历史版本默认折叠，避免长页面一次铺开。
 * 发布策略、CI、JVM 测试、Lint、README 同步、SHA-256 等开发细节不进入此页面。
 */
@Composable
fun MateChangelogPage() {
    val checker = koinInject<UpdateChecker>()
    var notes by remember { mutableStateOf<List<ReleaseNote>?>(null) }
    var online by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        // 保留上一次结果，刷新时不清空列表，避免界面跳动
        val result = runCatching { checker.releaseNotes() }.getOrDefault(emptyList())
        if (result.isNotEmpty()) {
            online = result.any { it.source != "bundled" }
            notes = result
        } else if (notes == null) {
            notes = emptyList()
        }
        loading = false
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Rikkahub-M 更新日志") },
            navigationIcon = { BackButton() },
            actions = {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(12.dp))
                }
                TextButton(onClick = { if (!loading) reloadKey++ }, enabled = !loading) { Text("刷新") }
            },
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("当前版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
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
                list.isEmpty() -> item {
                    Text("没有可显示的版本记录。", style = MaterialTheme.typography.bodyMedium)
                }
                else -> items(list, key = { it.version }) { note ->
                    VersionEntry(
                        note = note,
                        initiallyExpanded = note.version == BuildConfig.VERSION_NAME ||
                            note.version == list.first().version,
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionEntry(note: ReleaseNote, initiallyExpanded: Boolean) {
    var expanded by remember(note.version) { mutableStateOf(initiallyExpanded) }
    val sections = remember(note.version, note.body) { UpdateNotes.parse(note.body) }
    val summary = remember(note.version, note.summary, note.body) {
        note.summary.ifBlank { UpdateNotes.summary(note.body) }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(note.version, style = MaterialTheme.typography.titleLarge)
                if (note.version == BuildConfig.VERSION_NAME) {
                    StatusChip("当前版本", MaterialTheme.colorScheme.primaryContainer)
                } else if (note.prerelease) {
                    StatusChip("预览", MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    StatusChip("正式", MaterialTheme.colorScheme.tertiaryContainer)
                }
                Spacer(Modifier.weight(1f))
                if (sections.isNotEmpty()) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (summary.isNotBlank() && !expanded) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                if (sections.isEmpty()) {
                    Text("该版本没有填写更新说明。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    sections.forEach { section -> SectionBlock(section) }
                }
            }
        }
    }
}

@Composable
private fun SectionBlock(section: NoteSection) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Spacer(Modifier.height(2.dp))
        Text(
            section.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        section.items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("·", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item, style = MaterialTheme.typography.bodyMedium)
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
