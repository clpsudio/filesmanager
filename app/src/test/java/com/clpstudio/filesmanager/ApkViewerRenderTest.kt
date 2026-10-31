package com.clpstudio.filesmanager

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.data.apk.ApkFiles
import com.clpstudio.filesmanager.data.apk.ApkInspector
import com.clpstudio.filesmanager.data.apk.InstalledApp
import com.clpstudio.filesmanager.viewer.ApkViewerContent
import com.clpstudio.filesmanager.viewer.DeviceProfile
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Item 46: the APK page read from a real APK (PackageManager + own readers) and rendered. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class ApkViewerRenderTest {
    @get:Rule val compose = createComposeRule()
    private val out = File("build/screenshots").apply { mkdirs() }
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private val phone = DeviceProfile(35, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))

    private fun load() = ApkInspector.load(ctx, ApkViewerTest.resource("tiny.apk").let { f ->
        File(ctx.cacheDir, "Tiny App 2.3.1.apk").also { f.copyTo(it, overwrite = true) }
    })

    @Test fun inspectorReadsRealApk() {
        val d = load()
        assertNull(d.error)
        assertEquals("com.example.tinyapp", d.manifest?.packageName)
        assertEquals("2.3.1 (231)", d.versionText)
        assertTrue(d.label, d.label == "Tiny App" || d.label == "Tiny App 2.3.1")
        assertNotNull("icon from PackageManager or the zip", d.icon)
        assertEquals(ApkViewerTest.SHA256, d.signing.certs.single().sha256)
        assertNull(d.installed)
        assertEquals(ApkFiles.InstallState.NOT_INSTALLED, d.installState)
        assertNull(d.signatureMatches)
        // The decoded manifest file for the text viewer.
        val xml = ApkInspector.manifestXml(ctx, d)!!.readText()
        assertTrue(xml, xml.contains("android:versionName=\"2.3.1\""))
        // A binary XML pulled out by the archive browser becomes readable XML.
        val pulled = File(ctx.cacheDir, "zip/AndroidManifest.xml").apply { parentFile!!.mkdirs(); writeBytes(d.scan.manifest!!) }
        val readable = ApkInspector.readable(ctx, d.apk, pulled).readText()
        assertTrue(readable, readable.startsWith("<?xml") && readable.contains("<uses-permission"))
    }

    private fun shoot(name: String, mode: ThemeMode, glass: Boolean = true, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { FilesTheme(mode, liquidGlass = glass) { content() } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onRoot().captureRoboImage(File(out, name).path)
    }

    @Test fun apkPageLight() {
        val d = load()
        shoot("files_apk_viewer_light.png", ThemeMode.LIGHT) { ApkViewerContent(d, phone, onBack = {}) }
    }

    @Test @Config(qualifiers = "w393dp-h2400dp-xxhdpi")
    fun apkPageDarkExpandedUpdate() {
        val d = load().copy(installed = InstalledApp("2.2.0", 220, listOf(ApkViewerTest.SHA256), 1_790_000_000_000L, 1_792_000_000_000L, launchable = true))
        shoot("files_apk_viewer_dark_full.png", ThemeMode.DARK) { ApkViewerContent(d, phone, onBack = {}, expandAll = true) }
    }

    @Test @Config(qualifiers = "w393dp-h1400dp-xxhdpi")
    fun apkPagePlainMismatch() {
        val d = load().copy(installed = InstalledApp("3.0", 300, listOf("00:11"), 1_790_000_000_000L, 1_792_000_000_000L, launchable = true))
        shoot("files_apk_viewer_plain_newer.png", ThemeMode.LIGHT, glass = false) { ApkViewerContent(d, phone, onBack = {}) }
    }
}
