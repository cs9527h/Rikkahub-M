package me.rerere.rikkahub.ui.components.file

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import java.io.File
import java.io.IOException
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

private data class ManagedPdfPage(val bitmap: Bitmap, val index: Int, val pageCount: Int)

private sealed interface ManagedPdfState {
    data object Loading : ManagedPdfState
    data class Ready(val page: ManagedPdfPage) : ManagedPdfState
    data class Failure(val message: String) : ManagedPdfState
}

/** One bounded bitmap at a time; every descriptor/page/renderer is opened and closed on IO. */
private suspend fun renderManagedPdfPage(source: File, index: Int): ManagedPdfPage = withContext(Dispatchers.IO) {
    currentCoroutineContext().ensureActive()
    ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            if (renderer.pageCount == 0) throw IOException("PDF 没有可预览的页面")
            val selected = index.coerceIn(0, renderer.pageCount - 1)
            renderer.openPage(selected).use { page ->
                val (width, height) = managedPdfPreviewDimensions(page.width, page.height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    currentCoroutineContext().ensureActive()
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    currentCoroutineContext().ensureActive()
                    ManagedPdfPage(bitmap, selected, renderer.pageCount)
                } catch (error: Throwable) {
                    bitmap.recycle()
                    throw error
                }
            }
        }
    }
}

/** Guards malicious/extreme page dimensions without allocating a bitmap during tests. */
internal fun managedPdfPreviewDimensions(pageWidth: Int, pageHeight: Int): Pair<Int, Int> {
    require(pageWidth > 0 && pageHeight > 0) { "PDF 页面尺寸无效" }
    val width = pageWidth.toDouble()
    val height = pageHeight.toDouble()
    val scale = min(1440.0 / width, min(2048.0 / height, sqrt(3_000_000.0 / (width * height))))
    return floor(width * scale).toInt().coerceAtLeast(1) to
        floor(height * scale).toInt().coerceAtLeast(1)
}

@Composable
internal fun ManagedFilePreviewPdf(source: File, modifier: Modifier = Modifier) {
    var requestedPage by remember(source) { mutableIntStateOf(0) }
    val state by produceState<ManagedPdfState>(ManagedPdfState.Loading, source, requestedPage) {
        value = ManagedPdfState.Loading
        value = try {
            ManagedPdfState.Ready(renderManagedPdfPage(source, requestedPage))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ManagedPdfState.Failure("PDF 预览失败：${error.localizedMessage ?: "文件损坏或已加密"}")
        }
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (val current = state) {
                ManagedPdfState.Loading -> CircularProgressIndicator()
                is ManagedPdfState.Failure -> Text(
                    text = current.message,
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.error,
                )
                is ManagedPdfState.Ready -> Image(
                    bitmap = current.page.bitmap.asImageBitmap(),
                    contentDescription = "PDF 第 ${current.page.index + 1} 页",
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        val ready = (state as? ManagedPdfState.Ready)?.page
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = { ready?.let { requestedPage = it.index - 1 } },
                enabled = ready != null && ready.index > 0,
            ) { Icon(HugeIcons.ArrowLeft01, contentDescription = "上一页") }
            Text(ready?.let { "${it.index + 1} / ${it.pageCount}" } ?: "正在读取页面")
            FilledTonalIconButton(
                onClick = { ready?.let { requestedPage = it.index + 1 } },
                enabled = ready != null && ready.index < ready.pageCount - 1,
            ) { Icon(HugeIcons.ArrowRight01, contentDescription = "下一页") }
        }
    }
}
