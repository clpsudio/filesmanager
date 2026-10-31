package com.clpstudio.filesmanager

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.viewer.VideoActions
import com.clpstudio.filesmanager.viewer.VideoOverlay
import com.clpstudio.filesmanager.viewer.VideoOverlayState
import com.clpstudio.filesmanager.viewer.videoSheetActions
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Video round 4: controls with shading only behind them, the scrub time bubble, the name at the bottom. Tablet landscape. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
@OptIn(ExperimentalCoilApi::class, coil3.annotation.DelicateCoilApi::class)
class VideoRound4ScreenshotTest {
    @get:org.junit.Rule val compose = createComposeRule()
    private val out = File("build/screenshots").apply { mkdirs() }
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    /** A bright outdoor-like frame, so any dimming would show. */
    private fun frame(w: Int = 1920, h: Int = 1080): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, h * 0.62f, 0xFF4FA3F7.toInt(), 0xFFBFE3FF.toInt(), Shader.TileMode.CLAMP) })
        c.drawCircle(w * 0.78f, h * 0.2f, h * 0.09f, Paint().apply { color = 0xFFFFE27A.toInt() })
        val hills = Path().apply {
            moveTo(0f, h * 0.62f); quadTo(w * 0.25f, h * 0.42f, w * 0.5f, h * 0.6f); quadTo(w * 0.75f, h * 0.45f, w.toFloat(), h * 0.58f)
            lineTo(w.toFloat(), h.toFloat()); lineTo(0f, h.toFloat()); close()
        }
        c.drawPath(hills, Paint().apply { shader = LinearGradient(0f, h * 0.45f, 0f, h.toFloat(), 0xFF5BBF5A.toInt(), 0xFF2E7D32.toInt(), Shader.TileMode.CLAMP) })
        c.drawRect(w * 0.12f, h * 0.7f, w * 0.88f, h * 0.76f, Paint().apply { color = 0xFFD7C29A.toInt() })
        return bmp
    }

    private fun thumbs(): List<FileItem> {
        val dir = File(ctx.cacheDir, "Movies").apply { mkdirs() }
        val colors = listOf(0xFF1E6FF0, 0xFFF6B800, 0xFF16A34A, 0xFF9333EA, 0xFF0EA5E9, 0xFFE11D48, 0xFF64748B, 0xFFEA580C, 0xFF14B8A6)
        return colors.mapIndexed { i, col ->
            val f = File(dir, "VID_20261031_12%02d40.mp4".format(10 + i))
            val b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(col.toInt()) }
            File(dir, f.name + ".jpg").outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            f.writeBytes(ByteArray(8))
            FileItem.of(f)
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    private val preview = AsyncImagePreviewHandler { request ->
        val path = when (val d = request.data) {
            is File -> d.path
            is Uri -> d.path
            is String -> d
            else -> d.toString()
        }
        val bmp = path?.let { BitmapFactory.decodeFile("$it.jpg") ?: BitmapFactory.decodeFile(it) }
        (bmp ?: Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF334155.toInt()) }).asImage()
    }

    private val actions = object : VideoActions {
        override fun back() {}
        override fun info() {}
        override fun rotate() {}
        override fun autoRotate() {}
        override fun toggleFavorite() {}
        override fun edit() {}
        override fun hide() {}
        override fun share() {}
        override fun delete() {}
        override fun openWith() {}
        override fun setSpeed(speed: Float) {}
        override fun setLoop(on: Boolean) {}
        override fun toggleMute() {}
        override fun jumpTo(index: Int) {}
    }

    private fun render(name: String, state: VideoOverlayState, before: () -> Unit = {}) {
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ctx).coroutineContext(Dispatchers.Unconfined).build())
        val f = frame()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides preview) {
                FilesTheme(ThemeMode.DARK, liquidGlass = false) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        Image(f.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        VideoOverlay(state, actions, videoSheetActions({}, {}, null))
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        before()
        compose.mainClock.advanceTimeBy(700)
        compose.onRoot().captureRoboImage(File(out, name).path)
    }

    private fun baseState(items: List<FileItem>) = VideoOverlayState().apply {
        this.items = items
        index = 2
        title = items[2].name
        duration = 250_000L
        position = 83_000L
        playing = true
        hasPrev = true
        hasNext = true
    }

    @Test fun controls() {
        val s = baseState(thumbs()).apply {
            controls = true
            speed = 1.5f
            loop = true
            favorite = true
        }
        render("files_r4_video_controls.png", s)
    }

    @Test fun seek() {
        val s = baseState(thumbs()).apply {
            controls = false
            scrubbing = true
            scrubStartMs = 71_000L
            scrubMs = 83_000L
        }
        render("files_r4_video_seek.png", s)
    }

    @Test fun nameAtBottom() {
        // Just switched to this video: the name fades in at the bottom by the timeline.
        val s = baseState(thumbs()).apply { controls = false; position = 2_000L; nameStamp = 1 }
        render("files_r4_video_name.png", s)
    }
}
