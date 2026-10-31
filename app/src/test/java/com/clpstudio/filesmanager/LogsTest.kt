package com.clpstudio.filesmanager

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.clpstudio.filesmanager.data.Diagnostics
import com.clpstudio.filesmanager.data.RollingLog
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.ui.screens.AppLogsText
import com.clpstudio.filesmanager.ui.screens.LogsContent
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
import java.io.File

/** Item 38: Settings > Logs (rolling local log, share / copy / delete, report on GitHub). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w800dp-h1280dp-mdpi")
class LogsTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun rotatesAndStaysUnderTheCap() {
        val dir = File(app.cacheDir, "logtest").apply { deleteRecursively() }
        val log = RollingLog(dir, maxBytes = 4_000)
        repeat(500) { log.append("line %04d %s".format(it, "x".repeat(20))) }
        assertTrue("capped: ${log.sizeBytes()}", log.sizeBytes() <= 4_000)
        assertTrue(File(dir, "app.1.log").exists())
        val all = log.readAll()
        assertTrue(all.contains("line 0499")) // newest kept
        assertFalse(all.contains("line 0000")) // oldest dropped
        // Oldest first: the rotated file comes before the current one.
        val lines = all.trim().lines()
        assertTrue(lines.first() < lines.last())
    }

    @Test fun deleteRemovesEverything() {
        val dir = File(app.cacheDir, "logtest2").apply { deleteRecursively() }
        val log = RollingLog(dir, maxBytes = 1_000)
        repeat(100) { log.append("entry $it") }
        log.deleteAll()
        assertEquals(0L, log.sizeBytes())
        assertEquals("", log.readAll())
        log.append("after delete")
        assertEquals("after delete\n", log.readAll())
    }

    @Test fun diagnosticsWritesToTheLogAndDeleteClearsIt() {
        Diagnostics.init(app)
        Diagnostics.log("test", "something failed", IllegalStateException("boom"))
        val text = AppLogsText.build(app)
        assertTrue(text.contains("App start"))
        assertTrue(text.contains("[test] something failed"))
        assertTrue(text.contains("IllegalStateException"))
        AppLogsText.deleteAll(app)
        assertFalse(AppLogsText.build(app).contains("something failed"))
    }

    @Test fun issuesLinkIsTheRepo() {
        assertEquals("https://github.com/clpsudio/filesmanager/issues/new", AppLogsText.ISSUES_URL)
    }

    @Test fun screenHasShareCopyDeleteAndReport() {
        var deleted = false
        var reported = false
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = false) {
                LogsContent(text = "2026-10-31 [app] App start\n2026-10-31 [CRASH] boom", onBack = {}, onShare = {}, onCopy = {}, onDelete = { deleted = true }, onReport = { reported = true })
            }
        }
        compose.onNodeWithText("  Share logs").assertIsDisplayed()
        compose.onNodeWithText("  Copy").assertIsDisplayed()
        compose.onNodeWithText("  Report on GitHub").performClick()
        assertTrue(reported)
        compose.onNodeWithText("  Delete logs").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertTrue(deleted)
    }
}
