package me.rerere.rikkahub.utils

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import me.rerere.common.util.formatExceptionForLog
import me.rerere.common.util.runCatchingCancellable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

private const val TAG = "ContextUtil"

/**
 * Read clipboard data as text
 */
fun Context.readClipboardText(): String {
    val clipboardManager =
        getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = clipboardManager.primaryClip ?: return ""
    val item = clip.getItemAt(0) ?: return ""
    return item.text.toString()
}

/**
 * Write text into clipboard
 */
fun Context.writeClipboardText(text: String) {
    val clipboardManager =
        getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    runCatchingCancellable {
        clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
        Log.i(TAG, "clipboard.write succeeded")
    }.onFailure {
        Log.e(TAG, formatExceptionForLog("clipboard.write", it))
        Toast.makeText(this, "Failed to write text into clipboard", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Whether the app has been granted the "Usage access" special permission
 * (android.permission.PACKAGE_USAGE_STATS), required to query screen usage time.
 */
fun Context.hasUsageStatsPermission(): Boolean {
    val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

/**
 * Open the system "Usage access" settings page so the user can grant the
 * PACKAGE_USAGE_STATS permission manually.
 */
fun Context.openUsageAccessSettings() {
    runCatchingCancellable {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }.onFailure {
        Log.e(TAG, formatExceptionForLog("settings.usage-access", it))
    }
}

fun Context.openAccessibilitySettings() {
    runCatchingCancellable {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Log.e(TAG, formatExceptionForLog("settings.accessibility", it))
    }
}

/**
 * Open a url
 */
fun Context.openUrl(url: String) {
    Log.i(TAG, "browser.open")
    runCatchingCancellable {
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        intent.launchUrl(this, url.toUri())
    }.onFailure {
        Log.e(TAG, formatExceptionForLog("browser.open", it))
        Toast.makeText(this, "Failed to open URL: $url", Toast.LENGTH_SHORT).show()
    }
}

fun Context.getActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) {
            return context
        }
        context = context.baseContext
    }
    return null
}

enum class ImageExportResult { Saved, PermissionRequired, Failed }

fun Context.exportImage(
    activity: Activity,
    bitmap: Bitmap,
    fileName: String = "RikkaHub_${System.currentTimeMillis()}.png"
) {
    // 检查存储权限（Android 9及以下需要）
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                1
            )
            return
        }
    }
    writeImageToGallery(bitmap, fileName)
}

/** Result is Saved only after encoding AND closing the output stream succeed. */
fun Context.writeImageToGallery(bitmap: Bitmap, fileName: String): ImageExportResult =
    writeToGallery(fileName) { output ->
        writeImageExport(output) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

private fun Context.writeToGallery(fileName: String, write: (OutputStream?) -> Unit): ImageExportResult {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return ImageExportResult.PermissionRequired
    }
    var pendingUri: Uri? = null
    var pendingFile: File? = null
    return try {
        withImageExportRollback(rollback = {
            pendingUri?.let { contentResolver.delete(it, null, null) }
            pendingFile?.let { check(!it.exists() || it.delete()) { "Image rollback failed" } }
        }) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = checkNotNull(contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues))
            pendingUri = uri
            write(contentResolver.openOutputStream(uri))
            check(contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) > 0)
            pendingUri = null
        } else {
            val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            check(imagesDir.isDirectory || imagesDir.mkdirs())
            val image = File(imagesDir, fileName)
            pendingFile = image
            write(FileOutputStream(image))
            sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(image)))
            pendingFile = null
        }
        ImageExportResult.Saved
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, formatExceptionForLog("image.export", e))
        ImageExportResult.Failed
    }
}

internal fun writeImageExport(output: OutputStream?, encode: (OutputStream) -> Boolean) {
    checkNotNull(output) { "Image output stream is missing" }.use {
        check(encode(it)) { "Image encoding failed" }
    }
}

fun Context.exportImageFile(
    activity: Activity,
    file: File,
    fileName: String = "RikkaHub_${System.currentTimeMillis()}.png"
) {
    // 检查存储权限（Android 9及以下需要）
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                1
            )
            return
        }
    }

    writeToGallery(fileName) { output -> copyImageExport(output) { file.inputStream() } }
}
