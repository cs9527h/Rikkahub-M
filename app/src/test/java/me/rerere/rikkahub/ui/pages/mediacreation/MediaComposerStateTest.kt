package me.rerere.rikkahub.ui.pages.mediacreation

import androidx.compose.runtime.saveable.SaverScope
import kotlinx.coroutines.CancellationException
import me.rerere.mediagen.model.ImageRole
import org.junit.Assert.*
import org.junit.Test

class MediaComposerStateTest {
    @Test fun `restores sheet and in-flight system picker role but not unsaved bitmap editor`() {
        val state = MediaComposerState().apply {
            showModels = true
            pickingRole = ImageRole.LAST_FRAME
            importRole = ImageRole.FIRST_FRAME
            sketching = SketchRequest(ImageRole.LAST_FRAME)
        }
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(MediaComposerState.Saver) { scope.save(state) }!!
        val restored = MediaComposerState.Saver.restore(saved)!!
        assertTrue(restored.showModels)
        assertEquals(ImageRole.LAST_FRAME, restored.pickingRole)
        assertEquals(ImageRole.FIRST_FRAME, restored.importRole)
        assertNull(restored.sketching)
    }

    @Test fun `model switch closes stale capability panels without changing pending picker role`() {
        val state = MediaComposerState().apply {
            showModels = true; showParams = true; pickingRole = ImageRole.LAST_FRAME
            importRole = ImageRole.FIRST_FRAME; sketching = SketchRequest(ImageRole.LAST_FRAME)
        }
        state.modelSelected()
        assertFalse(state.showModels)
        assertFalse(state.showParams)
        assertNull(state.pickingRole)
        assertNull(state.sketching)
        assertEquals(ImageRole.FIRST_FRAME, state.importRole)
    }

    @Test fun `media diagnostics propagate cancellation before touching Android logging`() {
        val cancellation = CancellationException("cancel")
        assertSame(cancellation, assertThrows(CancellationException::class.java) { reportMediaFailure("media.test", cancellation) })
    }

    @Test fun `media diagnostics never convert JVM Error to UI failure`() {
        val failure = AssertionError("fatal test")
        assertSame(failure, assertThrows(AssertionError::class.java) { reportMediaFailure("media.test", failure) })
    }
}
