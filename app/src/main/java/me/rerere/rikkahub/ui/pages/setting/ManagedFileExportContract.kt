package me.rerere.rikkahub.ui.pages.setting

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

internal data class ManagedFileExportRequest(val name: String, val mimeType: String)

/** A per-request MIME type: choosing a PDF after a picture must not reuse the previous type. */
internal class ManagedFileExportContract : ActivityResultContract<ManagedFileExportRequest, Uri?>() {
    override fun createIntent(context: Context, input: ManagedFileExportRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = input.mimeType.takeIf { it.contains('/') } ?: "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, input.name)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
