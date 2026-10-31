package com.clpstudio.filesmanager

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.viewer.SlideshowControls
import com.clpstudio.filesmanager.viewer.SlideshowOptions
import com.clpstudio.filesmanager.viewer.SlideshowOptionsContent
import com.clpstudio.filesmanager.viewer.SlideshowPlan
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the slideshow controls over a photo and the options sheet content (drawn in place). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class SlideshowScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val out = File("build/screenshots")

    @Before fun setUp() { out.mkdirs() }

    private fun photo(w: Int = 1080, h: Int = 1440): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF1E6FF0.toInt(), 0xFFF6B800.toInt(), Shader.TileMode.CLAMP) })
        c.drawCircle(w * 0.35f, h * 0.35f, w * 0.18f, Paint().apply { color = 0x66FFFFFF })
        c.drawCircle(w * 0.7f, h * 0.65f, w * 0.25f, Paint().apply { color = 0x33000000 })
        return bmp
    }

    private fun set(content: @Composable () -> Unit) {
        compose.setContent(content)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
    }

    @Test fun slideshowControls() {
        val bmp = photo()
        val kb = SlideshowPlan.kenBurns(0)
        set {
            FilesTheme(ThemeMode.DARK, liquidGlass = true) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Image(
                        bmp.asImageBitmap(), null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            val s = kb.startScale + (kb.endScale - kb.startScale) * 0.5f
                            scaleX = s; scaleY = s
                        },
                    )
                    SlideshowControls(
                        position = 3, total = 12, name = "IMG_20261031_104512_holiday_beach.jpg", playing = true,
                        onClose = {}, onPrevious = {}, onPlayPause = {}, onNext = {}, onSettings = {},
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage(File(out, "files_r2_slideshow_controls.png").path)
    }

    @Test fun slideshowOptionsSheet() {
        val bmp = photo()
        set {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Color(0x66000000)))
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(top = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
                        Box(Modifier.padding(top = 18.dp)) {
                            SlideshowOptionsContent(initial = SlideshowOptions(seconds = 5, repeat = true), onStart = {})
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage(File(out, "files_r2_slideshow_options.png").path)
    }
}
