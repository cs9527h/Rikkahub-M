package me.rerere.rikkahub.data.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

/**
 * Copies to a SAF CreateDocument result, never moves/deletes the source. Returns bytes written.
 * A null provider stream or copy/flush/close failure throws; coroutine cancellation propagates.
 * On failure the destination may contain a partial copy; the caller should report that outcome.
 */
suspend fun exportManagedFile(context: Context, source: File, target: Uri): Long {
    require(target.scheme == "content") { "请选择系统文件选择器中的保存位置" }
    require(target.authority != "${context.packageName}.fileprovider") { "保存位置应与原文件分离" }
    return exportManagedFileToStream(source) {
        context.contentResolver.openOutputStream(target, "wt")
    }
}

/** Kept independent of Android providers so null streams, failures and cancellation can be tested. */
internal suspend fun exportManagedFileToStream(
    source: File,
    openOutput: () -> OutputStream?,
): Long = withContext(Dispatchers.IO) {
    currentCoroutineContext().ensureActive()
    requireReadableManagedFile(source)
    source.inputStream().use { input ->
        // Open the source before touching the destination; a missing source never creates an empty export.
        currentCoroutineContext().ensureActive()
        val output = openOutput() ?: throw IOException("目标位置未提供可写入的数据流")
        output.use {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var copied = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                currentCoroutineContext().ensureActive()
                output.write(buffer, 0, read)
                copied += read
            }
            currentCoroutineContext().ensureActive()
            output.flush()
            currentCoroutineContext().ensureActive()
            copied
        }
    }
}

/**
 * FileProvider covers filesDir, cacheDir and external-files in the existing file_paths.xml.
 * Canonicalization/provider lookup is performed on IO; launch the returned intent on the UI thread.
 */
suspend fun createManagedFileOpenIntent(
    context: Context,
    source: File,
    mimeType: String,
): Intent = withContext(Dispatchers.IO) {
    requireReadableManagedFile(source)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
    val suppliedMime = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
    val guessedMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(source.extension.lowercase(Locale.ROOT))
    val kind = managedFilePreviewKind(source.name, suppliedMime)
    val resolvedMime = when (kind) {
        // Keep scripts/HTML as inert source when an external text editor is used.
        ManagedFilePreviewKind.TEXT, ManagedFilePreviewKind.CODE, ManagedFilePreviewKind.MARKDOWN -> "text/plain"
        else -> suppliedMime.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: guessedMime ?: "application/octet-stream"
    }
    Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, resolvedMime)
        clipData = ClipData.newRawUri(source.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
