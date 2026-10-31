package com.clpstudio.filesmanager

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.clpstudio.filesmanager.data.Favorites
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.ui.components.CloseSelectionButton
import com.clpstudio.filesmanager.ui.components.FileActions
import com.clpstudio.filesmanager.ui.components.FileRow
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.components.SelectionState
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.screens.ApkSelectionActions
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.viewer.FilePagerScreen
import com.clpstudio.filesmanager.viewer.LevelPill
import com.clpstudio.filesmanager.viewer.PillKind
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

/** APK multi-select, the brightness / volume pills and the shared viewer bar (photo + video). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class GesturesScreenshotTest {
    @get:org.junit.Rule val compose = createComposeRule()
    private val out = File("build/screenshots").apply { mkdirs() }
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    private fun bitmap(a: Int, b: Int, w: Int = 900, h: Int = 1200): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), a, b, Shader.TileMode.CLAMP) })
        c.drawCircle(w * 0.35f, h * 0.4f, w * 0.2f, Paint().apply { color = 0x55FFFFFF })
        c.drawCircle(w * 0.7f, h * 0.7f, w * 0.28f, Paint().apply { color = 0x33000000 })
        return bmp
    }

    private fun photos(dirName: String, ext: String = "jpg"): List<String> {
        val dir = File(ctx.cacheDir, dirName).apply { mkdirs() }
        val palettes = listOf(
            0xFF1E6FF0 to 0xFF7CC4FF, 0xFFF6B800 to 0xFFFF6B3D, 0xFF16A34A to 0xFFB5F5C8, 0xFF9333EA to 0xFFF0ABFC,
            0xFF0EA5E9 to 0xFF155E75, 0xFFE11D48 to 0xFFFDA4AF, 0xFF64748B to 0xFFE2E8F0,
        )
        return palettes.mapIndexed { i, (a, b) ->
            File(dir, "IMG_20261031_11%02d12.$ext".format(10 + i)).also { f ->
                f.outputStream().use { bitmap(a.toInt(), b.toInt()).compress(Bitmap.CompressFormat.JPEG, 85, it) }
            }.absolutePath
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    private val preview = AsyncImagePreviewHandler { request ->
        val path = when (val d = request.data) {
            is File -> d.path
            is Uri -> d.path
            is String -> d
            else -> null
        }
        (path?.let { BitmapFactory.decodeFile(it) } ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)).asImage()
    }

    @OptIn(ExperimentalCoilApi::class, coil3.annotation.DelicateCoilApi::class)
    private fun render(name: String, content: @Composable () -> Unit) {
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ctx).coroutineContext(Dispatchers.Unconfined).build())
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides preview) { content() }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage(File(out, name).path)
    }

    private fun pagerWithPill(name: String, pill: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)?) {
        val paths = photos("Camera_$name")
        Favorites.init(File(ctx.filesDir, "favorites-gestures.txt"))
        Favorites.add(listOf(paths[2]))
        Favorites.flush()
        render(name) {
            FilesTheme(ThemeMode.DARK, liquidGlass = true) {
                Box(Modifier.fillMaxSize()) {
                    FilePagerScreen(paths, paths[2], onBack = {}, openFull = { _, _ -> }, showInFolder = {})
                    pill?.invoke(this)
                }
            }
        }
    }

    @Test fun brightnessPill() = pagerWithPill("files_r2_brightness_pill.png") {
        // Drawn where LevelPillHost shows it while dragging on the left third.
        LevelPill(PillKind.BRIGHTNESS, 0.68f, Modifier.align(Alignment.CenterStart).padding(horizontal = 28.dp))
    }

    @Test fun viewerBar() = pagerWithPill("files_r2_viewer_bar.png", null)

    private fun video(name: String, pill: Boolean) {
        val paths = photos("Movies_$name", ext = "mp4")
        val frame = bitmap(0xFF0F172A.toInt(), 0xFF2563EB.toInt(), 1600, 900)
        val state = VideoOverlayState().apply {
            title = File(paths[1]).name
            index = 1
            items = paths.map { FileItem.of(File(it)) }
            favorite = true
        }
        val actions = object : VideoActions {
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
        render(name) {
            FilesTheme(ThemeMode.DARK, liquidGlass = false) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Image(frame.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    // Stand-in for the player's own seek bar row under the overlay.
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 40.dp, start = 24.dp, end = 24.dp)) {
                        androidx.compose.material3.LinearProgressIndicator(progress = { 0.36f }, modifier = Modifier.fillMaxWidth())
                    }
                    VideoOverlay(state, actions, videoSheetActions({}, {}, null))
                    if (pill) LevelPill(PillKind.VOLUME, 0.6f, Modifier.align(Alignment.CenterEnd).padding(horizontal = 28.dp))
                }
            }
        }
    }

    @Test fun volumePill() = video("files_r2_volume_pill.png", pill = true)

    @Test fun videoBar() = video("files_r2_video_bar.png", pill = false)

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Test fun apkSelect() {
        val dir = File(ctx.cacheDir, "Download").apply { mkdirs() }
        val names = listOf("WhatsApp_2.26.21.apk", "Files_1.0.3.apk", "Instagram_412.0.apk", "PhonePe_24.10.apk", "Spotify_9.0.88.apk", "Zoom_6.2.apk", "Chrome_131.apk")
        val list = names.mapIndexed { i, n ->
            File(dir, n).apply { writeBytes(ByteArray(16)) }
            FileItem(File(dir, n).absolutePath, n, false, 9_400_000L + i * 13_700_000L, 1_793_400_000_000L - i * 86_400_000L)
        }
        val extra = listOf("WhatsApp · v2.26.21", "Files · v1.0.3", "Instagram · v412.0", "PhonePe · v24.10", "Spotify · v9.0.88", "Zoom · v6.2", "Chrome · v131")
        render("files_r2_apk_select.png") {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                val selection = remember { SelectionState().apply { selectAll(listOf(list[0].path, list[1].path, list[3].path)) } }
                val actions = remember { FileActions(ctx, {}) }
                GlassScaffold(
                    topBar = {
                        GlassTopBar(
                            title = "${selection.paths.size} selected",
                            navigation = { CloseSelectionButton { } },
                            actions = { ApkSelectionActions(ctx, list, selection, actions) },
                            below = {
                                PrimaryTabRow(selectedTabIndex = 0, containerColor = Color.Transparent) {
                                    listOf("APK files", "Downloaded", "System").forEachIndexed { i, t -> Tab(selected = i == 0, onClick = {}, text = { Text(t) }) }
                                }
                            },
                        )
                    },
                ) { padding ->
                    LazyColumn(contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 24.dp)) {
                        items(list.size) { i ->
                            val item = list[i]
                            FileRow(item, selection.isSelected(item.path), selectionMode = true, onClick = {}, onLongClick = {}, extraLine = extra[i])
                        }
                    }
                }
            }
        }
    }
}

