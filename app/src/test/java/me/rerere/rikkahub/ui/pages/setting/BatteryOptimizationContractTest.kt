package me.rerere.rikkahub.ui.pages.setting

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BatteryOptimizationContractTest {
    @Test fun githubMayAskDirectlyThenFallBackToListAndDetails() {
        assertEquals(listOf(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        ), batteryOptimizationSettingsActions(ignoring = false, isPlayStore = false))
    }

    @Test fun playNeverRequestsTheExemptionDirectly() {
        for (ignoring in listOf(false, true)) {
            val actions = batteryOptimizationSettingsActions(ignoring, isPlayStore = true)
            assertEquals(
                listOf(
                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                ),
                actions
            )
            assertFalse(actions.contains(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS))
        }
    }

    @Test fun alreadyExemptGithubBuildOpensTheListInstead() {
        assertEquals(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            batteryOptimizationSettingsActions(ignoring = true, isPlayStore = false).first()
        )
    }

    @Test fun exemptionPermissionIsLimitedToTheGithubManifest() {
        val app = listOf(File("."), File("app")).first { File(it, "src/github/AndroidManifest.xml").isFile }
        val permission = "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
        assertTrue(File(app, "src/github/AndroidManifest.xml").readText().contains(permission))
        assertFalse(File(app, "src/main/AndroidManifest.xml").readText().contains(permission))
        val play = File(app, "src/play/AndroidManifest.xml")
        assertFalse(play.isFile && play.readText().contains(permission))
    }
}
