package me.rerere.rikkahub.ui.components.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Own transient grants through the entire copy; only publish on the caller's dispatcher. */
internal suspend fun <T> importPastedContent(
    copy: () -> T,
    onImported: (T) -> Unit,
    release: () -> Unit = {},
) {
    try {
        val imported = withContext(Dispatchers.IO) { copy() }
        onImported(imported)
    } finally {
        release()
    }
}
