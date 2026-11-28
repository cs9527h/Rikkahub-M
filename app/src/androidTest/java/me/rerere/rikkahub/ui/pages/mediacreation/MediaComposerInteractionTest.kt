package me.rerere.rikkahub.ui.pages.mediacreation

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import java.io.File
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.capabilities
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.MediaCreationDraft
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.uuid.Uuid

class MediaComposerInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val first = MediaGenerationModel(modelId = "first-model", kind = MediaKind.IMAGE)
    private val second = MediaGenerationModel(modelId = "second-model", kind = MediaKind.IMAGE)
    private val provider = MediaGenerationProviderSetting.OpenAI(models = listOf(first, second))
    private val controller = MediaDraftController().apply { load(MediaCreationDraft(modelId = first.id, prompt = "original", editingNodeId = Uuid.random())) }
    private var pending: MediaDraftController.Ticket? = null
    private var submissions = 0

    @Composable
    private fun Content(state: MediaDraftController = controller) {
        val draft by state.draft.collectAsState()
        val submitting by state.submitting.collectAsState()
        val current = checkNotNull(draft)
        val prompt = rememberSaveable(saver = TextFieldState.Saver) { TextFieldState(current.prompt) }
        LaunchedEffect(prompt, state) {
            snapshotFlow { prompt.text.toString() }.collect { text -> state.update { it.copy(prompt = text) } }
        }
        val selection = MediaCreationModelSelection(provider, provider.models.first { it.id == current.modelId }, provider.capabilities(MediaKind.IMAGE)!!)
        val actions = MediaComposerActions(
            promptState = prompt, resolve = { File(context.cacheDir, it) },
            stopEditing = { state.update { it.copy(editingNodeId = null) } },
            clearDraft = { prompt.clearText(); state.clear() },
            generate = {
                state.update { it.copy(prompt = prompt.text.toString()) }
                pending = state.beginSubmission()
                if (pending != null) submissions++
            },
            selectModel = { _, model ->
                state.update { it.copy(prompt = prompt.text.toString()) }
                state.selectModel(model, selection.capabilities, sameShape = true)
            },
        )
        MediaCreationComposerContent(actions, submitting, current, selection, listOf(provider), emptyList(), true, {})
    }

    private fun generate() = compose.onNodeWithContentDescription(context.getString(R.string.media_creation_page_generate))
    private fun openModels() = compose.onNodeWithContentDescription(context.getString(R.string.media_creation_page_select_model)).performClick()

    @Test fun generateClickLocksRepeatedSubmissionAndModelChangePreservesDraftOnOldSuccess() {
        compose.setContent { MaterialExpressiveTheme { Content() } }
        generate().performClick()
        generate().assertIsNotEnabled()
        openModels()
        compose.onNodeWithText("second-model").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("original next")
        compose.runOnIdle {
            controller.submitted(checkNotNull(pending))
        }
        generate().assertIsEnabled()
        compose.onNode(hasSetTextAction()).assertTextContains("original next")
        compose.runOnIdle { assertEquals(1, submissions); assertEquals(second.id, controller.draft.value!!.modelId); assertEquals("original next", controller.draft.value!!.prompt) }
    }

    @Test fun clearAndStopEditingAreRealClicksAndFailureNeverClearsNewInput() {
        compose.setContent { MaterialExpressiveTheme { Content() } }
        compose.onNodeWithContentDescription(context.getString(R.string.media_creation_page_stop_editing)).performClick()
        compose.runOnIdle { assertNull(controller.draft.value!!.editingNodeId) }
        generate().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.media_creation_page_clear_input)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("replacement")
        compose.runOnIdle { controller.finish(checkNotNull(pending)) }
        compose.onNode(hasSetTextAction()).assertTextContains("replacement")
        generate().assertIsEnabled()
    }

    @Test fun modelSelectionPanelAndTypedPromptRestoreAfterSavedInstanceState() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialExpressiveTheme { Content() } }
        compose.onNode(hasSetTextAction()).performTextInput(" retained")
        openModels()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("second-model").assertIsDisplayed().performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("original retained")
        compose.runOnIdle { assertEquals(second.id, controller.draft.value!!.modelId) }
    }

    @Test fun switchingSessionDoesNotLetLateFirstSessionSuccessClearSecondComposer() {
        val other = MediaDraftController().apply { load(MediaCreationDraft(modelId = first.id, prompt = "other session")) }
        compose.setContent {
            var switched by remember { mutableStateOf(false) }
            MaterialExpressiveTheme {
                Column {
                    Button(onClick = { switched = true }) { Text("Switch session") }
                    key(switched) { Content(if (switched) other else controller) }
                }
            }
        }
        generate().performClick()
        compose.onNodeWithText("Switch session").performClick()
        compose.runOnIdle { controller.submitted(checkNotNull(pending)) }
        compose.onNode(hasSetTextAction()).assertTextContains("other session")
        generate().assertIsEnabled()
        compose.runOnIdle { assertEquals("other session", other.draft.value!!.prompt) }
    }
}
