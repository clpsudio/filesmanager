package com.clpstudio.filesmanager

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.clpstudio.filesmanager.data.AppSettings
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.ui.PrivacyRules
import com.clpstudio.filesmanager.ui.screens.SettingsContent
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Item 39: Settings > Block screenshots & screen recording (default off; vault always blocked). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h4000dp-mdpi")
class BlockScreenshotsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun defaultIsOff() = assertFalse(AppSettings().blockScreenshots)

    @Test fun blockOnSecuresEveryStateIncludingPip() {
        for (sdk in listOf(26, 30, 33, 35)) for (resumed in listOf(true, false)) for (pipOk in listOf(true, false)) {
            assertTrue(PrivacyRules.secure(block = true, hideInRecents = false, resumed = resumed, sdk = sdk, secureAllowed = pipOk, vaultHeld = false))
        }
    }

    @Test fun blockOffLeavesScreenshotsAllowed() {
        assertFalse(PrivacyRules.secure(block = false, hideInRecents = true, resumed = true, sdk = 35, secureAllowed = true, vaultHeld = false))
        assertFalse(PrivacyRules.secure(block = false, hideInRecents = true, resumed = false, sdk = 35, secureAllowed = true, vaultHeld = false))
        // Old Androids: only blank in the background for "Hide content while switching apps", never in PiP.
        assertTrue(PrivacyRules.secure(block = false, hideInRecents = true, resumed = false, sdk = 30, secureAllowed = true, vaultHeld = false))
        assertFalse(PrivacyRules.secure(block = false, hideInRecents = true, resumed = false, sdk = 30, secureAllowed = false, vaultHeld = false))
        assertFalse(PrivacyRules.secure(block = false, hideInRecents = false, resumed = false, sdk = 30, secureAllowed = true, vaultHeld = false))
    }

    @Test fun vaultScreensAlwaysSecure() {
        assertTrue(PrivacyRules.secure(block = false, hideInRecents = false, resumed = true, sdk = 35, secureAllowed = true, vaultHeld = true))
    }

    @Test fun switchIsInSettings() {
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { SettingsContent(AppSettings(), {}, {}, {}, {}, {}) } }
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText("Block screenshots & screen recording").fetchSemanticsNodes().size)
    }
}
