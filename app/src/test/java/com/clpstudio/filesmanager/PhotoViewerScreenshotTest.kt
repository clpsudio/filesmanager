package com.clpstudio.filesmanager

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.ui.components.MenuRow
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.viewer.FilePagerScreen
import com.clpstudio.filesmanager.viewer.ViewerMenuEntry
import com.clpstudio.filesmanager.viewer.viewerMenuEntries
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The photo viewer: star + ⋮ in the top bar (menu drawn open, in place), filmstrip and Delete / Share / star. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class PhotoViewerScreenshotTest {
    @get:org.junit.Rule val compose = createComposeRule()
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun photo(file: File, a: Int, b: Int, w: Int = 900, h: Int = 1200) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), a, b, Shader.TileMode.CLAMP) })
        c.drawCircle(w * 0.35f, h * 0.4f, w * 0.2f, Paint().apply { color = 0x55FFFFFF })
        c.drawCircle(w * 0.7f, h * 0.7f, w * 0.28f, Paint().apply { color = 0x33000000 })
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    @OptIn(ExperimentalCoilApi::class, coil3.annotation.DelicateCoilApi::class)
    @Test fun photoViewer() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ctx).coroutineContext(Dispatchers.Unconfined).build())
        val dir = File(ctx.cacheDir, "Camera").apply { mkdirs() }
        val palettes = listOf(
            0xFF1E6FF0 to 0xFF7CC4FF, 0xFFF6B800 to 0xFFFF6B3D, 0xFF16A34A to 0xFFB5F5C8, 0xFF9333EA to 0xFFF0ABFC,
            0xFF0EA5E9 to 0xFF155E75, 0xFFE11D48 to 0xFFFDA4AF, 0xFF64748B to 0xFFE2E8F0, 0xFF0D9488 to 0xFFFDE68A,
        )
        val paths = palettes.mapIndexed { i, (a, b) ->
            File(dir, "IMG_20261031_10%02d12.jpg".format(30 + i)).also { photo(it, a.toInt(), b.toInt()) }.absolutePath
        }
        Favorites.init(File(ctx.filesDir, "favorites-test.txt"))
        Favorites.add(listOf(paths[3]))
        Favorites.flush()

        val preview = AsyncImagePreviewHandler { request ->
            val path = when (val d = request.data) {
                is File -> d.path
                is Uri -> d.path
                is String -> d
                else -> null
            }
            (path?.let { BitmapFactory.decodeFile(it) } ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)).asImage()
        }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides preview) {
                FilesTheme(ThemeMode.DARK, liquidGlass = true) {
                    Box(Modifier.fillMaxSize()) {
                        FilePagerScreen(paths, paths[3], onBack = {}, openFull = { _, _ -> }, showInFolder = {})
                        // The ⋮ menu as it opens (popups don't draw here, so it's drawn in place).
                        val entries = viewerMenuEntries(FileKind.IMAGE, {}, {}, {}, {}) + listOf(
                            ViewerMenuEntry(Icons.Filled.DriveFileRenameOutline, "Rename") {},
                            ViewerMenuEntry(Icons.Filled.Share, "Share") {},
                            ViewerMenuEntry(Icons.AutoMirrored.Filled.OpenInNew, "Open with another app") {},
                            ViewerMenuEntry(Icons.Filled.Folder, "Show in folder") {},
                            ViewerMenuEntry(Icons.Filled.Delete, "Delete") {},
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shadowElevation = 3.dp,
                            tonalElevation = 3.dp,
                            modifier = Modifier.align(Alignment.TopEnd).padding(top = 76.dp, end = 8.dp).width(232.dp),
                        ) {
                            Column(Modifier.padding(vertical = 8.dp)) { entries.forEach { MenuRow(it.icon, it.label) {} } }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage(File(out, "files_r2_photo_viewer.png").path)
    }

    @OptIn(ExperimentalCoilApi::class, coil3.annotation.DelicateCoilApi::class)
    @Test fun photoViewerClean() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ctx).coroutineContext(Dispatchers.Unconfined).build())
        val dir = File(ctx.cacheDir, "Camera2").apply { mkdirs() }
        val paths = (0 until 6).map { i ->
            File(dir, "Screenshot_2026103$i.png").also { photo(it, (0xFF1E6FF0 + i * 0x101010).toInt(), 0xFFF6B800.toInt()) }.absolutePath
        }
        Favorites.init(File(ctx.filesDir, "favorites-test2.txt"))
        Favorites.flush()
        val preview = AsyncImagePreviewHandler { request ->
            val path = when (val d = request.data) { is File -> d.path; is Uri -> d.path; else -> null }
            (path?.let { BitmapFactory.decodeFile(it) } ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)).asImage()
        }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides preview) {
                FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                    FilePagerScreen(paths, paths[0], onBack = {}, openFull = { _, _ -> }, showInFolder = {})
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage(File(out, "files_r2_photo_viewer_light.png").path)
    }
}
