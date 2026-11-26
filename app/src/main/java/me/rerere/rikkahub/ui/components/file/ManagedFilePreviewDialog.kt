package me.rerere.rikkahub.ui.components.file

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.jvziyaoyao.scale.image.pager.ImagePager
import com.jvziyaoyao.scale.zoomable.pager.rememberZoomablePagerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.bettertext.markdown.Markdown
import me.rerere.bettertext.markdown.MarkdownDocument
import me.rerere.bettertext.markdown.MarkdownComponents
import me.rerere.bettertext.markdown.parseMarkdown
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.LinkSquare02
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.MANAGED_FILE_MARKDOWN_PREVIEW_BYTES
import me.rerere.rikkahub.data.files.MANAGED_FILE_TEXT_PREVIEW_BYTES
import me.rerere.rikkahub.data.files.ManagedFilePreviewKind
import me.rerere.rikkahub.data.files.ManagedFileTextPreview
import me.rerere.rikkahub.data.files.createManagedFileOpenIntent
import me.rerere.rikkahub.data.files.managedFilePreviewKind
import me.rerere.rikkahub.data.files.readManagedFileDocumentPreview
import me.rerere.rikkahub.data.files.readManagedFileTextPreview
import me.rerere.rikkahub.data.files.requireReadableManagedFile
import me.rerere.rikkahub.ui.components.richtext.MaterialMarkdownDefaults
import java.io.File

private data class ManagedPreviewContent(
    val kind: ManagedFilePreviewKind,
    val text: ManagedFileTextPreview? = null,
    val chunks: List<String> = emptyList(),
    val markdown: MarkdownDocument? = null,
    val previewError: String? = null,
)

private sealed interface ManagedPreviewState {
    data object Loading : ManagedPreviewState
    data class Ready(val content: ManagedPreviewContent) : ManagedPreviewState
    data class Failure(val message: String) : ManagedPreviewState
}

/** Full-screen native preview. [onExport] only delegates to the caller's SAF CreateDocument flow. */
@Composable
fun ManagedFilePreviewDialog(
    file: ManagedFileEntity,
    fileOnDisk: File,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
) {
    key(file.id, file.updatedAt, fileOnDisk.path) {
        ManagedFilePreviewDialogContent(file, fileOnDisk, onDismiss, onExport)
    }
}

@Composable
private fun ManagedFilePreviewDialogContent(
    file: ManagedFileEntity,
    fileOnDisk: File,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var openError by remember { mutableStateOf<String?>(null) }
    var opening by remember { mutableStateOf(false) }
    val state by produceState<ManagedPreviewState>(ManagedPreviewState.Loading, file, fileOnDisk) {
        value = ManagedPreviewState.Loading
        value = try {
            ManagedPreviewState.Ready(withContext(Dispatchers.IO) {
                requireReadableManagedFile(fileOnDisk)
                val kind = managedFilePreviewKind(file.displayName, file.mimeType)
                try {
                    val text = when (kind) {
                        ManagedFilePreviewKind.TEXT, ManagedFilePreviewKind.CODE -> readManagedFileTextPreview(fileOnDisk)
                        ManagedFilePreviewKind.MARKDOWN -> readManagedFileTextPreview(
                            fileOnDisk, MANAGED_FILE_MARKDOWN_PREVIEW_BYTES
                        )
                        ManagedFilePreviewKind.DOCUMENT -> readManagedFileDocumentPreview(
                            fileOnDisk, file.displayName, file.mimeType
                        )
                        else -> null
                    }
                    currentCoroutineContext().ensureActive()
                    val markdown = if (kind == ManagedFilePreviewKind.MARKDOWN && text != null) {
                        parseMarkdown(text.text, softBreakAsLineBreak = true, streaming = false)
                    } else {
                        null
                    }
                    currentCoroutineContext().ensureActive()
                    ManagedPreviewContent(kind, text, text?.text?.chunked(2048).orEmpty(), markdown)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // A preview decoder failure must not prevent exporting the intact source.
                    ManagedPreviewContent(kind, previewError = error.localizedMessage ?: "内容预览失败，可导出原文件。")
                }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ManagedPreviewState.Failure(error.localizedMessage ?: "文件预览失败，请重试")
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(HugeIcons.Cancel01, contentDescription = "关闭预览") }
                    Column(Modifier.weight(1f)) {
                        Text(
                            file.displayName,
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "文件预览",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalIconButton(onClick = onExport, enabled = state is ManagedPreviewState.Ready) {
                        Icon(HugeIcons.Download01, contentDescription = "导出文件")
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        when (val current = state) {
                            ManagedPreviewState.Loading -> CircularProgressIndicator()
                            is ManagedPreviewState.Failure -> Text(
                                current.message,
                                modifier = Modifier.padding(24.dp),
                                color = MaterialTheme.colorScheme.error,
                            )
                            is ManagedPreviewState.Ready -> ManagedFilePreviewBody(
                                current.content, fileOnDisk, file.displayName
                            )
                        }
                    }
                }
                val ready = (state as? ManagedPreviewState.Ready)?.content
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (ready?.text?.truncated == true) {
                        val limit = if (ready.kind == ManagedFilePreviewKind.MARKDOWN) {
                            MANAGED_FILE_MARKDOWN_PREVIEW_BYTES
                        } else {
                            MANAGED_FILE_TEXT_PREVIEW_BYTES
                        }
                        Text(
                            if (ready.kind == ManagedFilePreviewKind.DOCUMENT) {
                                "提取文字仅预览前 ${limit / 1024} KiB，已截断；导出可保留完整原文件。"
                            } else {
                                "仅预览前 ${limit / 1024} KiB，内容已截断；导出可保留完整文件。"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (openError != null) {
                        Text(openError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    }
                    if (ready != null && (ready.kind !in textPreviewKinds || ready.previewError != null)) {
                        FilledTonalButton(
                            enabled = !opening,
                            onClick = {
                                opening = true
                                openError = null
                                scope.launch {
                                    try {
                                        val intent = createManagedFileOpenIntent(context, fileOnDisk, file.mimeType)
                                        context.startActivity(intent)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: ActivityNotFoundException) {
                                        openError = "没有找到可打开此文件的应用，可先导出文件。"
                                    } catch (error: Exception) {
                                        openError = "打开失败：${error.localizedMessage ?: "文件不可用"}"
                                    } finally {
                                        opening = false
                                    }
                                }
                            },
                        ) {
                            Icon(HugeIcons.LinkSquare02, contentDescription = null)
                            Text(if (opening) "正在打开…" else "使用其他应用打开", Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

// The shared default image component opens its URL on click; previews deliberately use inert alt text.
private val managedPreviewMarkdownComponents = MarkdownComponents(
    codeBlock = MaterialMarkdownDefaults.components.codeBlock,
    table = MaterialMarkdownDefaults.components.table,
    details = MaterialMarkdownDefaults.components.details,
    image = { image, _ ->
        Text("[图片] ${image.alt.orEmpty()}", style = MaterialTheme.typography.labelMedium)
    },
)

private val textPreviewKinds = setOf(
    ManagedFilePreviewKind.TEXT, ManagedFilePreviewKind.CODE, ManagedFilePreviewKind.MARKDOWN
)

@Composable
private fun ManagedFilePreviewBody(content: ManagedPreviewContent, source: File, displayName: String) {
    if (content.previewError != null) {
        Text(content.previewError, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
        return
    }
    when (content.kind) {
        ManagedFilePreviewKind.IMAGE -> ManagedFilePreviewImage(source, displayName)
        ManagedFilePreviewKind.PDF -> ManagedFilePreviewPdf(source, Modifier.fillMaxSize())
        ManagedFilePreviewKind.TEXT, ManagedFilePreviewKind.CODE, ManagedFilePreviewKind.MARKDOWN,
        ManagedFilePreviewKind.DOCUMENT -> {
            if (content.text?.text.isNullOrEmpty()) {
                Text(
                    if (content.kind == ManagedFilePreviewKind.DOCUMENT) "文档未提取到文字，可打开原文件查看。" else "这是一个空文件",
                    modifier = Modifier.padding(24.dp),
                )
            } else if (content.markdown != null) {
                // Parsed on IO. Native text components do not run scripts or load remote images.
                SelectionContainer {
                    Markdown(
                        document = content.markdown,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        theme = MaterialMarkdownDefaults.theme(MaterialTheme.typography.bodyLarge),
                        components = managedPreviewMarkdownComponents,
                        onLinkClick = {}, // Preview links are inert, including javascript:/file:/intent: links.
                    )
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                    ) {
                        itemsIndexed(content.chunks, key = { index, _ -> index }, contentType = { _, _ -> "text" }) { _, chunk ->
                            Text(
                                text = chunk,
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = if (content.kind == ManagedFilePreviewKind.CODE) FontFamily.Monospace else null,
                                ),
                            )
                        }
                    }
                }
            }
        }
        ManagedFilePreviewKind.AUDIO -> Text("音频文件\n可使用其他应用播放或导出。", Modifier.padding(24.dp))
        ManagedFilePreviewKind.VIDEO -> Text("视频文件\n可使用其他应用播放或导出。", Modifier.padding(24.dp))
        ManagedFilePreviewKind.OTHER -> Text("此格式暂无内置预览\n可使用其他应用打开或导出。", Modifier.padding(24.dp))
    }
}

@Composable
private fun ManagedFilePreviewImage(source: File, displayName: String) {
    val context = LocalContext.current
    var failed by remember(source) { mutableStateOf(false) }
    var loading by remember(source) { mutableStateOf(true) }
    val request = remember(context, source) {
        // Bound decoder memory even when the source has enormous pixel dimensions.
        ImageRequest.Builder(context).data(source).size(2048).build()
    }
    val state = rememberZoomablePagerState(initialPage = 0) { 1 }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ImagePager(
            modifier = Modifier.fillMaxSize().semantics { contentDescription = displayName },
            pagerState = state,
            imageLoader = {
                val painter = rememberAsyncImagePainter(
                    model = request,
                    onSuccess = { loading = false; failed = false },
                    onError = { loading = false; failed = true },
                )
                painter to painter.intrinsicSize
            },
        )
        if (loading) CircularProgressIndicator()
        if (failed) Text("图片解码失败，可使用其他应用打开或导出。", Modifier.padding(24.dp))
    }
}
