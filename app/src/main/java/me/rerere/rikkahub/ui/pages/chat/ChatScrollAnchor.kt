package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.hooks.imeScrollDelta
import kotlin.uuid.Uuid

/** UI-only scroll state; conversation messages still come exclusively from ConversationSession. */
@Stable
class ChatScrollAnchorState {
    var nodeId: Uuid? by mutableStateOf(null)
        private set
    internal var scrollRequest by mutableIntStateOf(0)
        private set
    internal var pendingAnchorRequest = 0
        private set
    internal var pinned by mutableStateOf(false)
        private set
    internal var selfScrolling = false
        private set
    private var waitingForNewUserMessage by mutableStateOf(false)
    private var lastUserNodeIdBeforeSend: Uuid? = null

    // Also guards initial bottom positioning when the first message is sent before history/header layout finishes.
    internal val hasScrollTarget: Boolean get() = waitingForNewUserMessage || nodeId != null
    internal val autoFollow: Boolean get() = !hasScrollTarget
    internal val awaitingUserMessage: Boolean get() = waitingForNewUserMessage

    fun anchorNextUserMessage(nodes: List<MessageNode>) {
        waitingForNewUserMessage = true
        lastUserNodeIdBeforeSend = nodes.lastOrNull { it.isAnchorable() }?.id
    }

    fun anchorTurnOf(nodes: List<MessageNode>, messageId: Uuid) {
        val index = nodes.indexOfFirst { it.currentMessage.id == messageId }
        if (index < 0) return
        val userIndex = (index downTo 0).firstOrNull { nodes[it].isAnchorable() } ?: return
        waitingForNewUserMessage = false
        anchorTo(nodes[userIndex].id)
    }

    internal fun onMessagesChanged(nodes: List<MessageNode>) {
        if (nodeId != null && nodes.none { it.id == nodeId && it.isAnchorable() }) release()
        if (!waitingForNewUserMessage) return
        val lastUser = nodes.lastOrNull { it.isAnchorable() } ?: return
        if (lastUser.id != lastUserNodeIdBeforeSend) {
            waitingForNewUserMessage = false
            anchorTo(lastUser.id)
        }
    }

    private fun anchorTo(id: Uuid) {
        nodeId = id
        pinned = false
        scrollRequest++
        pendingAnchorRequest = scrollRequest
    }

    /** Dragging cancels a pending animation's right to pin, but retains padding to avoid a reading jump. */
    internal fun onUserScroll() {
        // A queued send may not have entered history yet; a drag still cancels its future jump.
        waitingForNewUserMessage = false
        pinned = false
        scrollRequest++
    }

    /** Explicit navigation (especially return-to-bottom) removes padding and restores ordinary follow mode. */
    fun release() {
        waitingForNewUserMessage = false
        nodeId = null
        pinned = false
        scrollRequest++
    }

    internal fun beginScroll(request: Int): Boolean {
        if (request != scrollRequest || nodeId == null) return false
        selfScrolling = true
        return true
    }

    internal fun finishScroll(request: Int, completed: Boolean) {
        selfScrolling = false
        if (request == scrollRequest && completed && nodeId != null) pinned = true
    }
}

private fun MessageNode.isAnchorable(): Boolean = currentMessage.let {
    it.role == MessageRole.USER && !it.isNoticeOnly && !it.isContextCheckpoint
}

/**
 * Keep the 1.1.4 IME delta/zero-frame behavior when reading normally. While pinned, resize the
 * anchor's measured space instead of scrolling the user message under the title bar. Always track
 * IME height, including pinned frames, so releasing the anchor never replays an old keyboard delta.
 */
@Composable
internal fun ChatScrollAnchorState.ImeAutoScroller(listState: LazyListState) {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    var previousHeight by remember(listState) { mutableIntStateOf(0) }
    LaunchedEffect(listState, ime, density) {
        snapshotFlow { ime.getBottom(density) }.collect { height ->
            val delta = imeScrollDelta(previousHeight, height)
            previousHeight = height
            if (delta != 0 && !pinned && !selfScrolling && !awaitingUserMessage) {
                listState.scrollBy(delta.toFloat())
            }
        }
    }
}

/** Last item supplies the missing viewport height in its own measure pass, never a one-frame-late spacer. */
@Composable
internal fun ChatScrollAnchorState.rememberLastNodeMinHeight(
    listState: LazyListState,
    nodes: List<MessageNode>,
    bottomKey: Any,
): IntState {
    val minHeight = remember(this, listState) { mutableIntStateOf(0) }
    val currentNodes by rememberUpdatedState(nodes)
    val anchorIndex by remember(this) {
        derivedStateOf { nodeId?.let { id -> currentNodes.indexOfFirst { it.id == id } } ?: -1 }
    }
    val lastNodeIndex by remember { derivedStateOf { currentNodes.lastIndex } }

    LaunchedEffect(nodes) { onMessagesChanged(nodes) }

    // Only a real pointer drag interrupts a pin; keyboard compensation is programmatic too.
    LaunchedEffect(listState, this) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) onUserScroll()
        }
    }

    // Non-anchor programmatic scrolling (volume keys) releases the pin as well.
    LaunchedEffect(listState, this) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !selfScrolling && pinned) onUserScroll()
        }
    }

    val request = scrollRequest
    LaunchedEffect(request, listState) {
        // onUserScroll/release invalidate the running request without initiating a new animation.
        if (request == 0 || nodeId == null || !hasScrollTarget) return@LaunchedEffect
        if (request != pendingAnchorRequest) return@LaunchedEffect
        withFrameNanos {}
        withFrameNanos {}
        val index = anchorIndex
        if (index < 0 || !beginScroll(request)) return@LaunchedEffect
        var completed = false
        try {
            listState.animateScrollToItem(index)
            completed = true
        } finally {
            finishScroll(request, completed)
        }
    }

    LaunchedEffect(listState, this) {
        snapshotFlow { Triple(listState.layoutInfo, anchorIndex, lastNodeIndex) }.collect { (info, index, lastIndex) ->
            if (index < 0 || lastIndex < index) {
                minHeight.intValue = 0
                return@collect
            }
            val height = info.lastNodeMinHeight(index, lastIndex, bottomKey) ?: return@collect
            minHeight.intValue = height
            // Removing the loading line, collapsing reasoning and window/header resizes must not push the anchor down.
            if (pinned && !listState.isScrollInProgress) {
                val anchor = info.visibleItemsInfo.firstOrNull { it.index == index }
                val bottomVisible = info.visibleItemsInfo.any { it.key == bottomKey }
                if (anchor != null && bottomVisible && anchor.offset != 0) listState.requestScrollToItem(index)
            }
        }
    }
    return minHeight
}

internal fun Modifier.chatAnchorMinHeight(minHeight: IntState): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val height = maxOf(placeable.height, minHeight.intValue).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(placeable.width, height) { placeable.place(0, 0) }
}

private fun LazyListLayoutInfo.lastNodeMinHeight(anchorIndex: Int, lastNodeIndex: Int, bottomKey: Any): Int? {
    val items = visibleItemsInfo
    if (items.isEmpty()) return null
    val visibleHeight = (viewportSize.height - beforeContentPadding - afterContentPadding).coerceAtLeast(0)
    if (anchorIndex > items.last().index) return visibleHeight
    // If an endpoint is offscreen the content is already longer than a viewport; keep the previous minimum.
    val anchor = items.firstOrNull { it.index == anchorIndex } ?: return null
    val last = items.firstOrNull { it.index == lastNodeIndex } ?: return null
    val bottom = items.lastOrNull { it.key == bottomKey }
    val otherHeight = if (bottom != null) {
        bottom.offset + bottom.size - anchor.offset - last.size
    } else {
        last.offset - anchor.offset
    }
    return chatAnchorLastNodeMinHeight(visibleHeight, otherHeight)
}

internal fun chatAnchorLastNodeMinHeight(visibleHeight: Int, otherHeight: Int): Int =
    (visibleHeight.coerceAtLeast(0) - otherHeight).coerceAtLeast(0)
