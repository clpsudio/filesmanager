package com.clpstudio.filesmanager

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.media3.common.Metadata
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.test.core.app.ApplicationProvider
import com.clpstudio.filesmanager.data.DesignPreview
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.playback.MediaRenderers
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.viewer.DesignBitmaps
import com.clpstudio.filesmanager.viewer.DesignViewerScreen
import com.clpstudio.filesmanager.viewer.ZipViewerScreen
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Design previews, the 7z browser and the FFmpeg renderer wiring, rendered: --tests '*FormatsRenderTest*' */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class FormatsRenderTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun fixture(name: String): File {
        val f = File(tmp.root, name)
        javaClass.getResourceAsStream("/fmt/$name")!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun shot(name: String, text: String) {
        compose.waitUntil(15_000) {
            runCatching { compose.onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(out, "$name.png").path)
    }

    @Test fun psdBitmap() {
        val loaded = DesignBitmaps.load(fixture("art_rgb.psd"))
        assertNotNull(loaded.bitmap)
        assertEquals(120, loaded.bitmap!!.width)
        val thumb = DesignBitmaps.load(fixture("sample_r2018_preview.dwg"), maxPx = 256, thumbnail = true)
        assertNotNull("DWG PNG preview decodes on Android", thumb.bitmap)
        val bmp = DesignBitmaps.load(fixture("sample_r2000_preview.dwg"), maxPx = 256, thumbnail = true)
        assertNotNull("DWG BMP preview decodes on Android", bmp.bitmap)
    }

    @Test fun psdPreviewScreen() {
        val f = fixture("art_rgb.psd")
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { DesignViewerScreen(f, f.name, {}) } }
        shot("files_p2_psd_preview", "Full image")
    }

    @Test fun dwgPreviewScreen() {
        val f = fixture("sample_r2018_preview.dwg")
        compose.setContent { FilesTheme(ThemeMode.DARK, liquidGlass = false) { DesignViewerScreen(f, "floor plan.dwg", {}) } }
        shot("files_p2_dwg_preview", "Preview saved by AutoCAD")
    }

    @Test fun noPreviewCard() {
        val f = tmp.newFile("bare.ai").apply { writeText("%!PS-Adobe-3.0\n%%EOF") }
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { DesignViewerScreen(f, f.name, {}) } }
        shot("files_p2_no_preview", "No preview available offline")
    }

    @Test fun sevenZBrowser() {
        val f = fixture("sample.7z")
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { ZipViewerScreen(f.path, {}) {} } }
        shot("files_p2_archive_7z", "Trip")
    }

    @Test fun ffmpegAudioRendererIsInstalledAfterThePhoneDecoders() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val renderers = MediaRenderers.factory(ctx).createRenderers(
            Handler(Looper.getMainLooper()),
            object : VideoRendererEventListener {},
            object : AudioRendererEventListener {},
            object : TextOutput { override fun onCues(cueGroup: CueGroup) {} },
            object : MetadataOutput { override fun onMetadata(metadata: Metadata) {} },
        )
        val ffmpeg = renderers.indexOfFirst { it is FfmpegAudioRenderer }
        val platform = renderers.indexOfFirst { it is MediaCodecAudioRenderer }
        assertTrue("FFmpeg audio renderer present: ${renderers.map { it.javaClass.simpleName }}", ffmpeg >= 0)
        assertTrue("phone decoders come first", platform in 0 until ffmpeg)
        assertTrue(DesignPreview.isDesign("Logo.PSD"))
    }
}
