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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons

/**
 * Item 20 previews: the gallery-style video view every video now opens in (the photo / video pager), drawn from the
 * same pieces FilePagerScreen draws for a video page: top bar · centre buttons · seek row · control row · filmstrip ·
 * Share / Favorite / Info / Delete. Tablet landscape light + dark, portrait light.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
@OptIn(ExperimentalCoilApi::class, coil3.annotation.DelicateCoilApi::class)
class VideoGalleryPreviewTest {
    @get:org.junit.Rule val compose = createComposeRule()
    private val out = File("build/screenshots").apply { mkdirs() }
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    /** A believable landscape video frame: sky, sun, hills, a road. */
    private fun scene(w: Int, h: Int, sky: Long = 0xFF4FA3F7, sky2: Long = 0xFFBFE3FF, hill: Long = 0xFF5BBF5A, hill2: Long = 0xFF2E7D32, sun: Boolean = true): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, h * 0.62f, sky.toInt(), sky2.toInt(), Shader.TileMode.CLAMP) })
        if (sun) c.drawCircle(w * 0.76f, h * 0.24f, h * 0.11f, Paint().apply { color = 0xFFFFE27A.toInt() })
        val hills = Path().apply {
            moveTo(0f, h * 0.62f); quadTo(w * 0.25f, h * 0.40f, w * 0.5f, h * 0.6f); quadTo(w * 0.75f, h * 0.44f, w.toFloat(), h * 0.57f)
            lineTo(w.toFloat(), h.toFloat()); lineTo(0f, h.toFloat()); close()
        }
        c.drawPath(hills, Paint().apply { shader = LinearGradient(0f, h * 0.45f, 0f, h.toFloat(), hill.toInt(), hill2.toInt(), Shader.TileMode.CLAMP) })
        val road = Path().apply { moveTo(w * 0.44f, h.toFloat()); lineTo(w * 0.49f, h * 0.62f); lineTo(w * 0.51f, h * 0.62f); lineTo(w * 0.6f, h.toFloat()); close() }
        c.drawPath(road, Paint().apply { color = 0xFF8D8A84.toInt() })
        return bmp
    }

    private fun items(): List<FileItem> {
        val dir = File(ctx.cacheDir, "Camera").apply { mkdirs() }
        val looks = listOf(
            listOf(0xFFFF9A62, 0xFFFFD2A1, 0xFF8E6A3A, 0xFF4E3A22), listOf(0xFF4FA3F7, 0xFFBFE3FF, 0xFF5BBF5A, 0xFF2E7D32),
            listOf(0xFF1B2A49, 0xFF41537A, 0xFF2D3B55, 0xFF111827), listOf(0xFF7DD3FC, 0xFFE0F2FE, 0xFF0EA5E9, 0xFF075985),
            listOf(0xFFF472B6, 0xFFFCE7F3, 0xFF9D174D, 0xFF500724), listOf(0xFF86EFAC, 0xFFF0FDF4, 0xFF15803D, 0xFF14532D),
            listOf(0xFFFCD34D, 0xFFFEF3C7, 0xFFB45309, 0xFF78350F), listOf(0xFFA5B4FC, 0xFFE0E7FF, 0xFF4338CA, 0xFF1E1B4B),
            listOf(0xFF94A3B8, 0xFFE2E8F0, 0xFF475569, 0xFF1E293B), listOf(0xFF5EEAD4, 0xFFCCFBF1, 0xFF0F766E, 0xFF134E4A),
        )
        return looks.mapIndexed { i, l ->
            val video = i % 4 != 2
            val f = File(dir, (if (video) "VID_20261031_14%02d12.mp4" else "IMG_20261031_14%02d30.jpg").format(10 + i))
            val b = scene(200, 200, l[0], l[1], l[2], l[3], sun = i % 2 == 0)
            File(dir, f.name + ".jpg").outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            f.writeBytes(ByteArray(8))
            FileItem.of(f)
        }
    }

    private val preview = AsyncImagePreviewHandler { request ->
        val path = when (val d = request.data) {
            is File -> d.path
            is Uri -> d.path
            is String -> d
            else -> Regex("""/\S+?\.(mp4|jpg)""").find(d.toString())?.value ?: d.toString()
        }
        val bmp = path?.let { BitmapFactory.decodeFile("$it.jpg") ?: BitmapFactory.decodeFile(it) }
        (bmp ?: Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF334155.toInt()) }).asImage()
    }

    private fun render(name: String, mode: ThemeMode) {
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ctx).coroutineContext(Dispatchers.Unconfined).build())
        val f = scene(1920, 1080)
        val list = items()
        val cur = 1
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides preview) {
                FilesTheme(mode, liquidGlass = true) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        Image(f.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        Box(Modifier.align(Alignment.TopCenter)) {
                            com.clpstudio.filesmanager.viewer.ViewerTopBar(list[cur].name, "2 of 298", onBack = {}) {
                                IconButton(onClick = {}) { Icon(Icons.Filled.Star, "Favorite", tint = com.clpstudio.filesmanager.viewer.FavoriteOn) }
                                IconButton(onClick = {}) { Icon(Icons.Filled.PictureInPictureAlt, "Picture-in-picture") }
                                IconButton(onClick = {}) { Icon(Icons.Filled.Info, "File info") }
                                IconButton(onClick = {}) { Icon(Icons.Filled.Fullscreen, "Full screen") }
                                IconButton(onClick = {}) { Icon(Icons.Filled.MoreVert, "More") }
                            }
                        }
                        com.clpstudio.filesmanager.viewer.VideoCenterButtons(
                            playing = true, hasPrev = true, hasNext = true, onPrevious = {}, onPlayPause = {}, onNext = {},
                            modifier = Modifier.align(Alignment.Center),
                        )
                        com.clpstudio.filesmanager.viewer.VideoBottomBlock(
                            items = list, current = cur, onSelect = {}, favorite = true,
                            onShare = {}, onFavorite = {}, onInfo = {}, onDelete = {},
                            modifier = Modifier.align(Alignment.BottomCenter),
                            video = {
                                com.clpstudio.filesmanager.viewer.VideoSeekRow(3_000L, 13_000L, onSeek = { _, _ -> })
                                com.clpstudio.filesmanager.viewer.VideoControlRow(
                                    muted = false, speed = 1f, loop = true, fill = false, loopHint = null,
                                    onMute = {}, onSpeed = {}, onLoop = {}, onFit = {},
                                )
                            },
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(800)
        compose.onRoot().captureRoboImage(File(out, name).path)
    }

    @Test fun lightLandscape() = render("files_r5_videoplayer_light_land.png", ThemeMode.LIGHT)
    @Test fun darkLandscape() = render("files_r5_videoplayer_dark_land.png", ThemeMode.DARK)
    @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    @Test fun lightPortrait() = render("files_r5_videoplayer_light_port.png", ThemeMode.LIGHT)
}
