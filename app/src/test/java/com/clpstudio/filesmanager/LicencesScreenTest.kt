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
import com.clpstudio.filesmanager.ui.screens.LicenceText
import com.clpstudio.filesmanager.ui.screens.LicenceTextContent
import com.clpstudio.filesmanager.ui.screens.LicencesListContent
import com.clpstudio.filesmanager.ui.screens.OpenSourceLicences
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.github.takahirom.roborazzi.captureRoboImage
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

/** Settings > Open-source licences: four separate sections, every bundled text in the APK's assets, the required wording. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class LicencesScreenTest {
    @get:Rule val compose = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private fun text(asset: String) = OpenSourceLicences.read(ctx, asset)
    private fun flat(s: String) = s.replace(Regex("\\s+"), " ").trim()
    private val shots get() = File("build/screenshots").apply { mkdirs() }

    @Test fun everyListedTextIsBundled() {
        for (e in OpenSourceLicences.entries) {
            assertFalse("${e.asset} missing", text(e.asset).startsWith("This text is missing"))
        }
    }

    @Test fun threeSectionsKeepTheLicencesApart() {
        val s = OpenSourceLicences.sections
        assertEquals(
            listOf("CLP Studio's own code", "FFmpeg (LGPL-2.1-or-later)", "Other third-party components"),
            s.map { it.heading },
        )
        // (a) Apache-2.0, clearly pending, own code only.
        assertTrue(s[0].status!!.contains("Apache-2.0") && s[0].status!!.contains("PENDING"))
        assertTrue(s[0].intro.contains("only CLP Studio's own code"))
        assertTrue(s[0].entries.any { it.asset == "Apache-2.0.txt" && it.title.contains("PENDING") && it.note!!.contains("PENDING") })
        // (b) FFmpeg: notice, source availability + build configuration, relinking, LGPL text, musl MIT notice.
        assertEquals(
            listOf("FFMPEG-NOTICE.txt", "FFMPEG-SOURCE.txt", "FFMPEG-RELINKING.txt", "LGPL-2.1.txt", "MIT-musl-avsscanf.txt"),
            s[1].entries.map { it.asset },
        )
        assertTrue(s[1].intro.contains("statically") && s[1].intro.contains("replaced as a whole"))
        // The Apache text under "Other" is for the third-party libraries; the pending one is in (a).
        assertTrue(s[2].intro.contains("AndroidX") && s[2].intro.contains("Media3") && s[2].intro.contains("Kotlin") && s[2].intro.contains("Coil"))
        assertTrue(s[2].entries.map { it.asset }.containsAll(listOf("Apache-2.0.txt", "NOTICE", "THIRD_PARTY_LICENSES.md")))
    }

    /** Junrar (UnRAR licence, not open source) was removed in Nov 2026: no section, no text, no mention as a component. */
    @Test fun noJunrarOrUnrarAnywhereOnTheScreen() {
        val ui = OpenSourceLicences.sections.flatMap { s ->
            listOf(s.heading, s.intro, s.status.orEmpty()) + s.entries.flatMap { listOf(it.title, it.summary, it.note.orEmpty(), it.asset) }
        }
        for (t in ui) assertFalse("screen text: '$t'", t.contains("junrar", true) || t.contains("unrar", true))
        assertTrue(text("UnRAR-junrar-7.5.5.txt").startsWith("This text is missing"))
        for (asset in listOf("ABOUT.txt", "NOTICE", "THIRD_PARTY_LICENSES.md")) {
            val t = text(asset)
            assertFalse("$asset names Junrar", t.contains("junrar", true))
            assertFalse("$asset names UnRAR", t.contains("unrar", true))
        }
    }

    @Test fun ffmpegNoticeSourceAndRelinking() {
        val notice = text("FFMPEG-NOTICE.txt")
        listOf("d388c347d41e4eb516dec05910551c5461e65615", "Lesser General Public License, version 2.1", "flac, alac,", "truehd",
            "linked statically into libffmpegJNI.so", "WITHOUT ANY WARRANTY")
            .forEach { assertTrue("FFmpeg notice mentions '$it'", flat(notice).contains(it, ignoreCase = true)) }
        val source = flat(text("FFMPEG-SOURCE.txt"))
        listOf("corresponding source", "https://github.com/clpsudio/filesmanager", "ffmpeg-corresponding-source",
            "f89916923b8b935d1f0aaf8b77d21cb0c507c270d66c00d0f256a62cc7ff22ec", "Written offer", "26.1.10909125",
            "--disable-everything", "--enable-decoder=truehd", "--enable-decoder=flac", "Not used: --enable-gpl, --enable-version3, --enable-nonfree")
            .forEach { assertTrue("source text mentions '$it'", source.contains(it, ignoreCase = true)) }
        val relink = flat(text("FFMPEG-RELINKING.txt"))
        listOf("linked statically into one shared library, libffmpegJNI.so", "replacing libffmpegJNI.so as a whole", "RELINKING.md",
            "relink kit", "relink-kit/<abi>/ffmpeg_jni.cc.o", "link-command.txt", "build-jni-standalone.sh")
            .forEach { assertTrue("relinking text mentions '$it'", relink.contains(it, ignoreCase = true)) }
        assertTrue(text("LGPL-2.1.txt").contains("GNU LESSER GENERAL PUBLIC LICENSE") && text("LGPL-2.1.txt").contains("Version 2.1, February 1999"))
        assertTrue(text("MIT-musl-avsscanf.txt").contains("Copyright (c) 2005-2014 Rich Felker, et al."))
    }

    @Test fun noClaimThatTheWholeAppIsApacheOrFullyOpenSource() {
        val about = text("ABOUT.txt")
        assertTrue(about.contains("PENDING"))
        assertTrue(about.contains("NOT distributed under a") || about.contains("not distributed under a"))
        val ui = OpenSourceLicences.sections.flatMap { s ->
            listOf(s.heading, s.intro, s.status.orEmpty()) + s.entries.flatMap { listOf(it.title, it.summary, it.note.orEmpty()) }
        }
        for (t in ui.map { flat(it).lowercase() }) {
            assertFalse("screen text: '$t'", t.contains("fully open source") || t.contains("100% legal") || t.contains("entirely apache") || t.contains("files is licensed under"))
        }
        for (e in OpenSourceLicences.entries) {
            val t = flat(text(e.asset)).lowercase()
            assertFalse("${e.asset}: 'is fully open source'", t.contains("is fully open source"))
            assertFalse("${e.asset}: '100% legal'", t.contains("100% legal"))
            assertFalse("${e.asset}: 'licensed under the apache license' for the whole app", t.contains("files is licensed under"))
        }
    }

    /** Reflowing for the phone changes only line-break whitespace: every other character stays, in order. */
    @Test fun reflowKeepsEveryTextVerbatimApartFromWhitespace() {
        val licences = setOf("LGPL-2.1.txt", "Apache-2.0.txt", "MIT-musl-avsscanf.txt")
        for (asset in OpenSourceLicences.entries.map { it.asset }.toSet()) {
            val original = text(asset)
            val md = asset.endsWith(".md")
            val shown = LicenceText.blocks(original, md).joinToString("\n") { it.text }
            if (asset in licences) {
                assertEquals("$asset changed", flat(original), flat(shown))
            } else {
                // Our own notices: a title's ==== / ---- underline and a Markdown '#' become heading style.
                fun strip(s: String) = flat(s.lines().filterNot { it.trim().matches(Regex("([=\\-~])\\1{2,}")) }
                    .joinToString("\n") { if (md) it.replace(Regex("^#{1,6}\\s+"), "") else it })
                assertEquals("$asset changed", strip(original), strip(shown))
            }
        }
    }

    @Test fun reflowJoinsHardWrappedSentences() {
        fun lines(asset: String) = LicenceText.blocks(text(asset), asset.endsWith(".md")).flatMap { it.text.lines() }
        assertTrue(lines("LGPL-2.1.txt").any { it.contains("applies to any software library or other program which contains a notice placed") })
        assertTrue(lines("Apache-2.0.txt").any { it.contains("\"License\" shall mean the terms and conditions for use, reproduction, and distribution") })
        assertTrue(lines("FFMPEG-NOTICE.txt").any { it.contains("Files plays some audio formats with FFmpeg's decoders. They are in the native library") })
        // Short lines (titles, list items, the LGPL's centred title) keep their own line.
        val lgpl = LicenceText.blocks(text("LGPL-2.1.txt"))
        assertTrue(lgpl.first().text.lines().first() == "GNU LESSER GENERAL PUBLIC LICENSE")
        // An aligned block (columns, ASCII art) keeps its layout (scrolls sideways).
        val art = LicenceText.blocks("Name      Version   Licence\nzip4j     2.11.5    Apache-2.0\n").first()
        assertEquals(LicenceText.Kind.PRE, art.kind)
        // A list keeps one item per line.
        val about = LicenceText.blocks(text("ABOUT.txt"))
        assertTrue(about.any { b -> b.text.lines().count { it.matches(Regex("^\\d\\. .*")) } == 3 })
        assertEquals(LicenceText.Kind.HEADING, about.first().kind)
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
        var opened: OpenSourceLicences.Entry? = null
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { LicencesListContent(onBack = {}, onOpen = { opened = it }) } }
        compose.onNodeWithText("CLP Studio's own code").assertExists()
        compose.onNodeWithText("Apache-2.0 · decision PENDING").assertExists()
        compose.onNodeWithText("FFmpeg (LGPL-2.1-or-later)").assertExists()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(shots, "files_licences_list.png").path)
        val list = compose.onNode(androidx.compose.ui.test.hasScrollAction())
        list.performScrollToNode(hasText("GNU Lesser General Public License 2.1"))
        compose.waitForIdle()
        compose.onNodeWithText("GNU Lesser General Public License 2.1").performClick()
        compose.waitForIdle()
        assertEquals("LGPL-2.1.txt", opened?.asset)
        list.performScrollToNode(hasText("Other third-party components"))
    }

    /** The whole list on one tall image, for review. */
    @Config(qualifiers = "w393dp-h2400dp-xxhdpi")
    @Test fun licencesListScreenWhole() {
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { LicencesListContent(onBack = {}, onOpen = {}) } }
        compose.onNodeWithText("Other third-party components").assertExists()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(shots, "files_licences_list_whole.png").path)
    }

    @Test fun ffmpegNoticeScreen() {
        val t = text("FFMPEG-NOTICE.txt")
        compose.setContent { FilesTheme(ThemeMode.DARK, liquidGlass = false) { LicenceTextContent("FFmpeg notice", t, onBack = {}) } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("FFmpeg in Files", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().captureRoboImage(File(shots, "files_licences_ffmpeg.png").path)
    }

    @Test fun ffmpegSourceScreen() {
        val t = text("FFMPEG-SOURCE.txt")
        compose.setContent { FilesTheme(ThemeMode.DARK, liquidGlass = false) { LicenceTextContent("Source code and build configuration", t, onBack = {}) } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("complete corresponding source", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().captureRoboImage(File(shots, "files_licences_ffmpeg_source.png").path)
    }

    /** README.md: same wording rule as the screen, licence marked pending, every preview it shows exists. */
    @Test fun readmeKeepsTheLicenceWordingAndItsPreviews() {
        val readme = File("../README.md").readText()
        val t = flat(readme).lowercase()
        assertFalse("README: 'fully open source'", t.contains("fully open source"))
        assertFalse("README: 'entirely apache'", t.contains("entirely apache"))
        assertFalse("README: '100% legal'", t.contains("100% legal"))
        assertFalse("README names Junrar", t.contains("junrar"))
        assertFalse("README: 'files is licensed under'", t.contains("files is licensed under"))
        assertTrue("README marks the licence as pending", t.contains("licence status: pending"))
        assertTrue("README: no INTERNET permission stated", readme.contains("There is no `INTERNET` permission"))
        val pics = Regex("docs/screenshots/[A-Za-z0-9._-]+\\.png").findAll(readme).map { it.value }.toList()
        assertTrue("README shows previews", pics.size >= 6)
        for (s in pics) assertTrue("$s missing", File("../$s").isFile)
    }
}
