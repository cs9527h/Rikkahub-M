package me.rerere.rikkahub.ui.hooks

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity

internal fun imeScrollDelta(previousHeight: Int, keyboardHeight: Int): Int =
    keyboardHeight.coerceAtLeast(0) - previousHeight.coerceAtLeast(0)

@Composable
fun ImeLazyListAutoScroller(
    lazyListState: LazyListState,
) {
    val ime = WindowInsets.ime
    val localDensity = LocalDensity.current
    var previousHeight by remember(lazyListState) { mutableIntStateOf(0) }
    LaunchedEffect(lazyListState, ime, localDensity) {
        snapshotFlow {
            ime.getBottom(localDensity)
        }.collect { keyboardHeight ->
            // Include the final zero frame: closing reverses the opening compensation,
            // and an identical-height second opening must start from zero again.
            val delta = imeScrollDelta(previousHeight, keyboardHeight)
            previousHeight = keyboardHeight
            if (delta != 0) lazyListState.scrollBy(delta.toFloat())
        }
    }
}
