package me.rerere.rikkahub.ui.pages.mediacreation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.SlidersHorizontal
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.Video01
import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationCapabilities
import me.rerere.mediagen.provider.MediaGenerationParameter
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.capabilities
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.MediaCreationAsset
import me.rerere.rikkahub.data.model.MediaCreationAssetType
import me.rerere.rikkahub.data.model.MediaCreationDraft
import me.rerere.rikkahub.data.model.MediaCreationOutput
import me.rerere.rikkahub.data.model.MediaCreationParams
import me.rerere.rikkahub.data.model.canSubmit
import me.rerere.rikkahub.data.model.mixesFramesWithReferences
import me.rerere.rikkahub.data.model.withRequired
import me.rerere.rikkahub.ui.components.ai.PickerHeader
import me.rerere.ui.components.FormItem
import me.rerere.ui.components.Tooltip
import me.rerere.ui.sketch.SketchDialog
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.pages.setting.components.label
import me.rerere.rikkahub.ui.pages.setting.components.typeName
import java.io.File
import kotlin.uuid.Uuid

private const val MAX_PICKED_IMAGES = 8

/**
 * 页面底部的输入区：素材、提示词，以及模型和参数的选择。
 */
@Composable
internal fun MediaCreationComposer(
    vm: MediaCreationVM,
    draft: MediaCreationDraft,
    selection: MediaCreationModelSelection?,
    providers: List<MediaGenerationProviderSetting>,
    recentOutputs: List<MediaCreationRecentOutput>,
    uploadConfigured: Boolean,
    onOpenMediaSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val submitting by vm.submitting.collectAsStateWithLifecycle()
    val actions = remember(vm) {
        MediaComposerActions(vm.promptState, vm::resolve, vm::importAssets, vm::stopEditing,
            vm::removeAsset, vm::setAssetRole, vm::clearDraft, vm::generate, vm::selectModel,
            vm::updateParams, vm::useVideo, vm::useImage, vm::addSketch)
    }
    androidx.compose.runtime.key(vm.sessionId) {
        MediaCreationComposerContent(actions, submitting, draft, selection, providers, recentOutputs,
            uploadConfigured, onOpenMediaSettings, modifier)
    }
}

@Composable
internal fun MediaCreationComposerContent(
    actions: MediaComposerActions,
    submitting: Boolean,
    draft: MediaCreationDraft,
    selection: MediaCreationModelSelection?,
    providers: List<MediaGenerationProviderSetting>,
    recentOutputs: List<MediaCreationRecentOutput>,
    uploadConfigured: Boolean,
    onOpenMediaSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val panels = rememberSaveable(saver = MediaComposerState.Saver) { MediaComposerState() }
    with(panels) {
    val imagesPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED_IMAGES)
    ) { uris ->
        actions.importAssets(uris, MediaCreationAssetType.IMAGE, importRole)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        actions.importAssets(listOfNotNull(uri), MediaCreationAssetType.IMAGE, importRole)
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        actions.importAssets(listOfNotNull(uri), MediaCreationAssetType.VIDEO, ImageRole.REFERENCE)
    }

    val capabilities = selection?.capabilities
    val hasContent = actions.promptState.text.isNotEmpty() || draft.assets.isNotEmpty()
    val canSubmit = !submitting && capabilities != null &&
        draft.copy(prompt = actions.promptState.text.toString()).canSubmit(capabilities)

    // 分槽位（首帧、尾帧）的模型一直显示素材行，槽位本身就是提示；只有参考素材的模型没放素材时把这一行
    // 收起来，从输入框左边的加号添加
    val hasFrameSlots = capabilities != null && capabilities.frameRoles.isNotEmpty()
    val showAssets = capabilities != null && capabilities.acceptsAssets &&
        (hasFrameSlots || draft.assets.isNotEmpty())
    val showAddButton = capabilities != null && capabilities.acceptsAssets && !hasFrameSlots

    // 和聊天页的输入框一样，是一个悬浮在页面底部的圆角容器
    Surface(
        modifier = modifier
            .windowInsetsPadding(
                WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)
            )
            .padding(horizontal = 8.dp)
            .padding(bottom = 8.dp)
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.largeIncreased,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (draft.editingNodeId != null) {
                EditingBar(
                    onStop = actions.stopEditing,
                    modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 6.dp),
                )
            }
            AnimatedVisibility(visible = showAssets) {
                if (capabilities != null) {
                    AssetRow(
                        assets = draft.assets,
                        capabilities = capabilities,
                        resolve = actions.resolve,
                        onPick = { pickingRole = it },
                        onRemove = actions.removeAsset,
                        onSetRole = actions.setAssetRole,
                        onDraw = { sketching = SketchRequest(role = it.role, asset = it) },
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
                    )
                }
            }
            if (capabilities != null && capabilities.acceptsAssets) {
                if (draft.assets.mixesFramesWithReferences(capabilities)) {
                    Text(
                        text = stringResource(R.string.media_creation_page_frames_with_references),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp),
                    )
                }
                if (capabilities.requiresRemoteInputs && draft.assets.isNotEmpty() && !uploadConfigured) {
                    Text(
                        text = stringResource(R.string.media_creation_page_upload_not_configured_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(start = 8.dp, end = 8.dp, top = 4.dp)
                            .clickable(onClick = onOpenMediaSettings),
                    )
                }
            }

            TextField(
                state = actions.promptState,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.media_creation_page_prompt_placeholder)) },
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 5),
                shape = MaterialTheme.shapes.largeIncreased,
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                leadingIcon = if (showAddButton) {
                    {
                        IconButton(onClick = { pickingRole = ImageRole.REFERENCE }) {
                            Icon(
                                imageVector = HugeIcons.Add01,
                                contentDescription = stringResource(
                                    R.string.media_creation_page_add_reference_asset
                                ),
                            )
                        }
                    }
                } else null,
            )

            Row(
                modifier = Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModelButton(model = selection?.model, onClick = { showModels = true })
                    if (selection != null && capabilities != null && capabilities.parameters.isNotEmpty()) {
                        val summary = draft.params
                            .withRequired(capabilities, selection.provider.presets(selection.model.kind).defaults)
                            .summary(selection.model.kind)
                        ComposerChip(
                            icon = HugeIcons.SlidersHorizontal,
                            text = summary.take(3).joinToString(" · ")
                                .ifEmpty { stringResource(R.string.media_creation_page_params) },
                            onClick = { showParams = true },
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
                if (hasContent) {
                    IconButton(onClick = actions.clearDraft) {
                        Icon(
                            imageVector = HugeIcons.Eraser,
                            contentDescription = stringResource(R.string.media_creation_page_clear_input),
                        )
                    }
                }
                GenerateButton(enabled = canSubmit, onClick = actions.generate)
            }
        }
    }

    if (showModels) {
        ModelSheet(
            providers = providers,
            selectedModelId = draft.modelId,
            onSelect = { provider, model ->
                actions.selectModel(provider, model)
                modelSelected()
            },
            onManage = {
                showModels = false
                onOpenMediaSettings()
            },
            onDismiss = { showModels = false },
        )
    }

    if (showParams && selection != null) {
        ParamsSheet(
            selection = selection,
            params = draft.params,
            onParamsChange = actions.updateParams,
            onDismiss = { showParams = false },
        )
    }

    val role = pickingRole
    if (role != null && capabilities != null) {
        // 视频只能作为参考素材
        val acceptsVideo = capabilities.videoInput && role == ImageRole.REFERENCE
        AssetPickerSheet(
            role = role,
            acceptsVideo = acceptsVideo,
            recentOutputs = recentOutputs.filter { acceptsVideo || !it.output.isVideo },
            resolve = actions.resolve,
            onPickImages = {
                pickingRole = null
                importRole = role
                val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                // 首帧和尾帧各只有一张
                if (role == ImageRole.REFERENCE) imagesPicker.launch(request) else imagePicker.launch(request)
            },
            onPickVideo = {
                pickingRole = null
                videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            },
            onSketch = {
                pickingRole = null
                sketching = SketchRequest(role = role)
            },
            onSelect = { output ->
                pickingRole = null
                if (output.isVideo) actions.useVideo(output) else actions.useImage(output, role)
            },
            onDismiss = { pickingRole = null },
        )
    }

    sketching?.let { request ->
        SketchDialog(
            image = request.asset?.let { actions.resolve(it.path).toUri() },
            // 白纸默认用参数里选的比例，画出来的首帧、参考图和要生成的画面对得上
            aspectRatio = if (selection != null && capabilities != null) {
                draft.params
                    .withRequired(capabilities, selection.provider.presets(selection.model.kind).defaults)
                    .aspectRatio
                    ?.let(::parseAspectRatio)
            } else {
                null
            },
            onDismiss = { sketching = null },
            onConfirm = { result ->
                sketching = null
                actions.addSketch(result, request.role, request.asset)
            },
        )
    }
}
}
