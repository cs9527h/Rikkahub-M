package me.rerere.rikkahub.ui.pages.mediacreation

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.model.MediaCreationAsset
import me.rerere.rikkahub.data.model.MediaCreationAssetType
import me.rerere.rikkahub.data.model.MediaCreationOutput
import me.rerere.rikkahub.data.model.MediaCreationParams
import me.rerere.ui.sketch.SketchResult
import java.io.File

internal class SketchRequest(val role: ImageRole, val asset: MediaCreationAsset? = null)

/** Picker restoration is independent of the model's changing capabilities. */
internal class MediaComposerState {
    var showModels by mutableStateOf(false)
    var showParams by mutableStateOf(false)
    var pickingRole by mutableStateOf<ImageRole?>(null)
    var importRole by mutableStateOf(ImageRole.REFERENCE)
    var sketching by mutableStateOf<SketchRequest?>(null)

    fun modelSelected() {
        showModels = false
        showParams = false
        pickingRole = null
        sketching = null
        // importRole remains the role of the already-launched system picker.
    }

    companion object {
        val Saver = listSaver<MediaComposerState, String>(
            save = {
                listOf(
                    it.showModels.toString(),
                    it.showParams.toString(),
                    it.pickingRole?.name.orEmpty(),
                    it.importRole.name
                )
            },
            restore = { saved ->
                MediaComposerState().apply {
                    showModels = saved[0].toBoolean()
                    showParams = saved[1].toBoolean()
                    pickingRole = ImageRole.entries.find { it.name == saved[2] }
                    importRole = ImageRole.valueOf(saved[3])
                }
            },
        )
    }
}

/** Platform/VM ports; the production composer and interaction tests render the same controls. */
internal class MediaComposerActions(
    val promptState: TextFieldState,
    val resolve: (String) -> File,
    val importAssets: (List<Uri>, MediaCreationAssetType, ImageRole) -> Unit = { _, _, _ -> },
    val stopEditing: () -> Unit = {},
    val removeAsset: (MediaCreationAsset) -> Unit = {},
    val setAssetRole: (MediaCreationAsset, ImageRole) -> Unit = { _, _ -> },
    val clearDraft: () -> Unit = {},
    val generate: () -> Unit = {},
    val selectModel: (MediaGenerationProviderSetting, MediaGenerationModel) -> Unit = { _, _ -> },
    val updateParams: (MediaCreationParams) -> Unit = {},
    val useVideo: (MediaCreationOutput) -> Unit = {},
    val useImage: (MediaCreationOutput, ImageRole) -> Unit = { _, _ -> },
    val addSketch: (SketchResult, ImageRole, MediaCreationAsset?) -> Unit = { _, _, _ -> },
)
