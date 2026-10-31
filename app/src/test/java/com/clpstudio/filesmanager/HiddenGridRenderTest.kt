package com.clpstudio.filesmanager

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.clpstudio.filesmanager.data.Category
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileView
import com.clpstudio.filesmanager.data.HiddenFilter
import com.clpstudio.filesmanager.data.HiddenMedia
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.data.groupByFolder
import com.clpstudio.filesmanager.thumbs.thumbModel
import com.clpstudio.filesmanager.ui.components.FileCollection
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.components.SelectionState
import com.clpstudio.filesmanager.ui.screens.FolderGroupsGrid
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A Photos grid with hidden media shown ("Show hidden files" on), from real files on disk. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class HiddenGridRenderTest {
    @get:Rule val compose = createComposeRule()
    private val out = File("build/screenshots")
    private lateinit var root: File
    private lateinit var visible: List<FileItem>

    private fun png(rel: String, c1: Int, c2: Int, t: Long): File {
        val f = File(root, rel).apply { parentFile!!.mkdirs() }
        val bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 240f, 240f, c1, c2, Shader.TileMode.CLAMP) })
        c.drawCircle(120f, 100f, 50f, Paint().apply { color = 0x66FFFFFF })
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        f.setLastModified(t)
        return f
    }

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ctx).coroutineContext(Dispatchers.Unconfined).build())
        out.mkdirs()
        root = File(ctx.cacheDir, "hidden-grid").apply { deleteRecursively(); mkdirs() }
        val now = 1_793_450_000_000L
        visible = listOf(
            png("DCIM/Camera/IMG_20261031_101.png", 0xFF1E6FF0.toInt(), 0xFF7CC4FF.toInt(), now - 1_000),
            png("DCIM/Camera/IMG_20261030_102.png", 0xFF0EA5E9.toInt(), 0xFF155E75.toInt(), now - 90_000_000),
        ).map { FileItem.of(it) }
        png("DCIM/.hidden/IMG_hidden_1.png", 0xFF9333EA.toInt(), 0xFFF0ABFC.toInt(), now - 500)
        png("Pictures/.secret_selfie.png", 0xFFE11D48.toInt(), 0xFFFDA4AF.toInt(), now - 2_000)
        File(root, "WhatsApp/Media/Statuses").mkdirs(); File(root, "WhatsApp/Media/Statuses/.nomedia").writeText("")
        png("WhatsApp/Media/Statuses/status_0931.png", 0xFFF6B800.toInt(), 0xFFFF6B3D.toInt(), now - 3_000)
        png(".Private/trip/goa_beach.png", 0xFF334155.toInt(), 0xFF94A3B8.toInt(), now - 95_000_000)
    }

    private fun merged(on: Boolean): List<FileItem> {
        val hidden = HiddenMedia.walk(listOf(root), emptyList())
        return HiddenMedia.merge(visible, hidden, HiddenFilter(on, storageRoots = listOf(root.absolutePath))) { HiddenMedia.inCategory(it, Category.PHOTOS) }
    }

    @Test fun mergedList_onAndOff() {
        val on = merged(true)
        assertEquals(6, on.size)
        assertTrue(on.any { it.name == ".secret_selfie.png" } && on.any { it.name == "status_0931.png" })
        // Hidden thumbnails load from the file path (no MediaStore row needed).
        on.forEach { assertEquals(File(it.path), thumbModel(it)) }
        assertEquals(visible.map { it.path }, merged(false).map { it.path })
    }

    @Test fun categoryFlow_mergesCachedHiddenIndex() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        Graph.init(ctx)
        val hidden = HiddenMedia.walk(listOf(root), emptyList())
        Graph.hiddenMedia.setForTest(hidden)
        val on = withTimeout(20_000) { Graph.media.categoryFlow(Category.PHOTOS, HiddenFilter(true, storageRoots = listOf(root.absolutePath))).first { it.done } }
        assertTrue(on.items.map { it.path }.containsAll(hidden.map { it.path }))
        val off = withTimeout(20_000) { Graph.media.categoryFlow(Category.PHOTOS, HiddenFilter(false, storageRoots = listOf(root.absolutePath))).first { it.done } }
        assertTrue(off.items.none { f -> hidden.any { it.path == f.path } })
        val audio = withTimeout(20_000) { Graph.media.categoryFlow(Category.AUDIO, HiddenFilter(true)).first { it.done } }
        assertTrue(audio.items.none { f -> hidden.any { it.path == f.path } })
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun shot(name: String, content: @Composable () -> Unit) {
        val preview = AsyncImagePreviewHandler { request ->
            val bmp = (request.data as? File)?.let { BitmapFactory.decodeFile(it.path) } ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            bmp.asImage()
        }
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides preview) { content() } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage(File(out, "$name.png").path)
    }

    @Test fun photosGrid_hiddenShown() {
        val items = merged(true)
        shot("files_hidden_photos_grid") {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                GlassScaffold(topBar = { GlassTopBar("Photos", subtitle = "${items.size} files · hidden files shown", onBack = {}) }) { p ->
                    FileCollection(items, FileView.GRID, SelectionState(), onClick = {}, contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = p.calculateTopPadding() + 4.dp), sections = true)
                }
            }
        }
    }

    @Test fun photosFolders_hiddenShown_dark() {
        val items = merged(true)
        val groups = groupByFolder(items)
        shot("files_hidden_photos_folders_dark") {
            FilesTheme(ThemeMode.DARK, liquidGlass = true) {
                GlassScaffold(topBar = { GlassTopBar("Photos", subtitle = "${items.size} files in ${groups.size} folders", onBack = {}) }) { p ->
                    FolderGroupsGrid(groups, p) {}
                }
            }
        }
    }
}
