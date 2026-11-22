package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.ai.memory.HistoryMemoryManager
import me.rerere.rikkahub.data.db.entity.HistoryMemoryCandidate
import me.rerere.rikkahub.data.model.MemoryFile

/**
 * 单条候选：只在用户想改一条、或者想看看来源和旧记忆时才打开。
 * 批量审核走 [MemoryReviewScreen]，这里不重复做列表。
 */
@Composable
internal fun HistoryCandidateDialog(
    history: HistoryMemoryManager,
    target: String,
    candidate: HistoryMemoryCandidate,
    onDismiss: () -> Unit,
    openSource: (String) -> Unit,
    openMemory: (scope: String, path: String) -> Unit,
    onResolve: (String?, MemoryFile?) -> Unit,
) {
    var mergeInto by remember(candidate.key) { mutableStateOf<MemoryFile?>(null) }
    var fact by rememberSaveable(candidate.key) { mutableStateOf(candidate.fact) }
    var related by remember(candidate.key) { mutableStateOf<List<MemoryFile>>(emptyList()) }
    LaunchedEffect(target, candidate.key) {
        try {
            related = history.related(target, candidate)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 关联推荐失败不影响保存
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(candidate.subject) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${categoryLabel(candidate.category)} · ${localDate(candidate.sourceTime)}")
                OutlinedTextField(
                    value = fact,
                    onValueChange = { fact = it },
                    label = { Text("记忆内容") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("你当时说", style = MaterialTheme.typography.titleSmall)
                Text(candidate.evidence)
                TextButton(onClick = { onDismiss(); openSource(candidate.conversationId) }) { Text("打开来源聊天") }
                if (related.isNotEmpty()) {
                    Text("可能相关的旧记忆 · 请检查是否重复或已经过时", style = MaterialTheme.typography.titleSmall)
                    related.forEach { file ->
                        Text("${dateOf(file.updatedAt)} · ${file.title}\n${file.displayBody.take(400)}")
                        TextButton(onClick = { onDismiss(); openMemory(target, file.path) }) { Text("打开这条记忆") }
                        FilterChip(
                            selected = mergeInto?.path == file.path,
                            onClick = { mergeInto = if (mergeInto?.path == file.path) null else file },
                            label = { Text("合并进这条") },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = fact.trim().length in 4..600, onClick = { onResolve(fact, mergeInto) }) {
                Text(if (mergeInto == null) "保存这条" else "确认合并")
            }
        },
        dismissButton = { TextButton(onClick = { onResolve(null, null) }) { Text("跳过") } },
    )
}
