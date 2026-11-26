package me.rerere.rikkahub.ui.pages.setting

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedFileExportContractTest {
    @Test fun exportsUsePerRequestNameAndMimeRatherThanThePreviousSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val contract = ManagedFileExportContract()
        val photo = contract.createIntent(context, ManagedFileExportRequest("photo.png", "image/png"))
        val document = contract.createIntent(context, ManagedFileExportRequest("report.pdf", "application/pdf"))
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, document.action)
        assertTrue(document.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("image/png", photo.type)
        assertEquals("application/pdf", document.type)
        assertEquals("report.pdf", document.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test fun cancellationNeverProducesAnExportTarget() {
        val contract = ManagedFileExportContract()
        val data = Intent().setData(Uri.parse("content://test/export.pdf"))
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, data))
        assertEquals(data.data, contract.parseResult(Activity.RESULT_OK, data))
    }
}
