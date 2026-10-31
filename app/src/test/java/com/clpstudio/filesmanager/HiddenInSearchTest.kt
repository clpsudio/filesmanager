package com.clpstudio.filesmanager

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.clpstudio.filesmanager.data.AppSettings
import com.clpstudio.filesmanager.data.HiddenFilter
import com.clpstudio.filesmanager.data.SearchHidden
import com.clpstudio.filesmanager.data.ThemeMode
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

/** Item 35: "Show hidden files in search" (default off, only offered while Show hidden files is on). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w1280dp-h4000dp-mdpi")
class HiddenInSearchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun defaultIsOff() {
        assertFalse(AppSettings().hiddenInSearch)
        assertFalse(SearchHidden.include(AppSettings()))
    }

    @Test fun ruleNeedsBothSwitches() {
        assertFalse(SearchHidden.include(showHidden = false, hiddenInSearch = false))
        assertFalse(SearchHidden.include(showHidden = false, hiddenInSearch = true)) // Show hidden off wins
        assertFalse(SearchHidden.include(showHidden = true, hiddenInSearch = false)) // browsing shows, search doesn't
        assertTrue(SearchHidden.include(showHidden = true, hiddenInSearch = true))
    }

    @Test fun searchFilterHidesOrShowsDotPaths() {
        val root = "/storage/emulated/0"
        fun filter(s: AppSettings) = HiddenFilter(SearchHidden.include(s), storageRoots = listOf(root))
        val dot = "$root/.secret/notes.txt"
        val normal = "$root/Documents/notes.txt"
        assertFalse(filter(AppSettings(showHidden = true, hiddenInSearch = false)).visiblePath(dot))
        assertTrue(filter(AppSettings(showHidden = true, hiddenInSearch = false)).visiblePath(normal))
        assertTrue(filter(AppSettings(showHidden = true, hiddenInSearch = true)).visiblePath(dot))
        assertFalse(filter(AppSettings(showHidden = false, hiddenInSearch = true)).visiblePath(dot))
    }

    @Test fun switchOnlyVisibleWhenShowHiddenIsOn() {
        val s = androidx.compose.runtime.mutableStateOf(AppSettings(showHidden = false))
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { SettingsContent(s.value, {}, {}, {}, {}, {}) } }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText("Show hidden files in search").fetchSemanticsNodes().size)
        s.value = AppSettings(showHidden = true)
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText("Show hidden files in search").fetchSemanticsNodes().size)
    }
}
