package me.rerere.rikkahub.ui.pages.setting

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsDiagnosticsTest {
    @Test fun `cancellation is not logged or converted to ordinary UI failure`() {
        val failure = CancellationException("cancel")
        assertSame(
            failure,
            assertThrows(CancellationException::class.java) { reportSettingsFailure("settings.test", failure) }
        )
    }

    @Test fun `fatal JVM failure is not hidden by the error boundary`() {
        val failure = AssertionError("fatal test")
        assertSame(
            failure,
            assertThrows(AssertionError::class.java) { reportSettingsFailure("settings.test", failure) }
        )
    }
}
