package com.clpstudio.filesmanager

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.ui.screens.LicenceTextContent
import com.clpstudio.filesmanager.ui.screens.LicencesListContent
import com.clpstudio.filesmanager.ui.screens.OpenSourceLicences
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Settings > Open-source licences: every bundled text is in the APK's assets, with the required wording. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class LicencesScreenTest {
    @get:Rule val compose = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private fun text(asset: String) = OpenSourceLicences.read(ctx, asset)

    @Test fun everyListedTextIsBundled() {
        for (e in OpenSourceLicences.entries) {
            assertFalse("${e.asset} missing", text(e.asset).startsWith("This text is missing"))
        }
    }

    @Test fun ffmpegNoticeHasVersionLicenceSourceAndRelinking() {
        val t = text("FFMPEG-NOTICE.txt")
        listOf("d388c347d41e4eb516dec05910551c5461e65615", "Lesser General Public License, version 2.1",
            "corresponding source", "Written offer", "relinking", "flac, alac,", "truehd")
            .forEach { assertTrue("FFmpeg notice mentions '$it'", t.contains(it, ignoreCase = true)) }
        assertTrue(text("LGPL-2.1.txt").contains("GNU LESSER GENERAL PUBLIC LICENSE") && text("LGPL-2.1.txt").contains("Version 2.1, February 1999"))
        assertTrue(text("MIT-musl-avsscanf.txt").contains("Copyright (c) 2005-2014 Rich Felker, et al."))
    }

    @Test fun unrarRestrictionIsKeptVerbatim() {
        val t = text("UnRAR-junrar-7.5.5.txt").replace(Regex("\\s+"), " ")
        assertTrue(t.contains("cannot be used to re-create the RAR compression algorithm, which is proprietary."))
        assertTrue(t.contains("the code may not be used to develop a RAR (WinRAR) compatible archiver."))
    }

    @Test fun noClaimThatTheWholeAppIsApacheOrFullyOpenSource() {
        val about = text("ABOUT.txt")
        assertTrue(about.contains("PENDING"))
        assertTrue(about.contains("NOT distributed under a") || about.contains("not distributed under a"))
        for (e in OpenSourceLicences.entries) {
            val t = text(e.asset).lowercase().replace(Regex("\\s+"), " ")
            assertFalse("${e.asset}: 'is fully open source'", t.contains("is fully open source"))
            assertFalse("${e.asset}: 'licensed under the apache license' for the whole app", t.contains("files is licensed under"))
        }
    }

    @Test fun settingsHasAnOpenSourceLicencesRow() {
        var opened = false
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = false) {
                com.clpstudio.filesmanager.ui.screens.SettingsContent(
                    com.clpstudio.filesmanager.data.AppSettings(), {}, {}, {}, {}, {}, onLicences = { opened = true },
                )
            }
        }
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasText("Open-source licences"))
        compose.onNodeWithText("Open-source licences").performClick()
        assertTrue(opened)
    }

    @Test fun licencesListScreen() {
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { LicencesListContent(onBack = {}, onOpen = {}) } }
        compose.onNodeWithText("FFmpeg (audio decoders)").assertExists()
        compose.onNodeWithText("Junrar (RAR archives)").assertExists()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(File("build/screenshots").apply { mkdirs() }, "files_licences_list.png").path)
    }

    @Test fun ffmpegNoticeScreen() {
        val t = text("FFMPEG-NOTICE.txt")
        compose.setContent { FilesTheme(ThemeMode.DARK, liquidGlass = false) { LicenceTextContent("FFmpeg (audio decoders)", t, onBack = {}) } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("FFmpeg in Files", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().captureRoboImage(File(File("build/screenshots").apply { mkdirs() }, "files_licences_ffmpeg.png").path)
    }
}
