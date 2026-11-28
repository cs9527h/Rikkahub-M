package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.CatalogFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual production content and click handlers, with only the reference-aware storage port substituted. */
class SettingFilesInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var state: FileManagerState
    private val file = CatalogFile(ManagedFileEntity(1, "upload", "upload/1.txt", "report.txt", "text/plain", 10, 1, 1), emptySet())
    private val results = mutableListOf<FileDeletionSummary>()

    private fun show(delete: suspend (CatalogFile) -> Boolean, restoration: StateRestorationTester? = null) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            state = rememberSaveable(saver = FileManagerState.Saver) { FileManagerState() }
            MaterialExpressiveTheme {
                SettingFilesContent(state, Result.success(listOf(file)), emptyMap(), emptyMap(),
                    fileOnDisk = { File(context.cacheDir, "not-opened.txt") }, deleteFile = delete,
                    exportFile = {}, onPreview = {}, onRefresh = {},
                    onDeletionResult = { summary, _, _ -> results.add(summary) }, navigationIcon = {})
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
    }

    private fun deleteThroughUi() {
        compose.onNodeWithContentDescription(context.getString(R.string.file_manager_actions_named, "report.txt")).performClick()
        compose.onNodeWithText(context.getString(R.string.setting_files_page_delete_action)).performClick()
        compose.onNodeWithText(context.getString(R.string.setting_files_page_delete_file_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.setting_files_page_delete_action)).performClick()
        compose.waitForIdle()
    }

    @Test fun successfulDeleteDisappearsBeforeUnchangedCatalogRescansAndStaysGoneAcrossTabs() {
        show({ true })
        deleteThroughUi()
        compose.onNodeWithText("report.txt").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.file_manager_area_avatars)).performClick()
        compose.onNodeWithText(context.getString(R.string.file_manager_area_chat)).performClick()
        compose.onNodeWithText("report.txt").assertDoesNotExist()
        compose.runOnIdle { assertEquals(setOf("upload/1.txt"), state.removedPaths); assertEquals(FileDeletionSummary(1, 0), results.single()) }
    }

    @Test fun failedStorageResultDoesNotOptimisticallyRemoveFile() {
        show({ false })
        deleteThroughUi()
        compose.onNodeWithText("report.txt").assertIsDisplayed()
        compose.runOnIdle { assertTrue(state.removedPaths.isEmpty()); assertEquals(FileDeletionSummary(0, 1), results.single()) }
    }

    @Test fun thrownStorageFailureKeepsItemAndReleasesDisabledActions() {
        show({ throw IOException("test provider failure") })
        deleteThroughUi()
        compose.onNodeWithText("report.txt").assertIsDisplayed()
        compose.runOnIdle { assertFalse(state.busyDeleting); assertTrue(state.removedPaths.isEmpty()) }
        compose.onNodeWithContentDescription(context.getString(R.string.file_manager_actions_named, "report.txt")).performClick()
        compose.onNodeWithText(context.getString(R.string.setting_files_page_delete_action)).assertIsEnabled()
    }

    @Test fun batchCleanupUsesRealSheetAndConfirmationAndKeepsFailedItem() {
        show({ false })
        compose.onNodeWithContentDescription(context.getString(R.string.setting_files_page_clean_content_description)).performClick()
        compose.onNodeWithText(context.getString(R.string.setting_files_page_clean_action)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.file_manager_clean_confirm, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.setting_files_page_clean_action)).performClick()
        compose.onNodeWithText("report.txt").assertIsDisplayed()
        compose.runOnIdle { assertEquals(FileDeletionSummary(0, 1), results.single()); assertTrue(state.removedPaths.isEmpty()) }
    }

    @Test fun successfulDeleteTombstoneSurvivesSavedStateWhileCatalogIsStillStale() {
        val restoration = StateRestorationTester(compose)
        show({ true }, restoration)
        deleteThroughUi()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("report.txt").assertDoesNotExist()
        compose.runOnIdle { assertEquals(setOf("upload/1.txt"), state.removedPaths) }
    }

    @Test fun typedFilterRestoresAndTabSwitchResetsOnlyIncompatibleFilters() {
        val restoration = StateRestorationTester(compose)
        show({ false }, restoration)
        compose.onNode(hasSetTextAction()).performTextInput("no-match")
        compose.onNodeWithText("report.txt").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextContains("no-match")
        compose.runOnIdle { assertEquals("no-match", state.query) }
        compose.onNodeWithText(context.getString(R.string.file_manager_area_avatars)).performClick()
        compose.onNodeWithText(context.getString(R.string.file_manager_area_chat)).performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("")
        compose.onNodeWithText("report.txt").assertIsDisplayed()
        compose.runOnIdle { assertEquals("", state.query) }
    }
}
