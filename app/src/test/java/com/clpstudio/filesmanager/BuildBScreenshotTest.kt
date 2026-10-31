package com.clpstudio.filesmanager

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.clpstudio.filesmanager.data.Category
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileView
import com.clpstudio.filesmanager.data.SortBy
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.data.TypeFilters
import com.clpstudio.filesmanager.ui.components.FileCollection
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.components.SelectionState
import com.clpstudio.filesmanager.ui.components.TypeChipsRow
import com.clpstudio.filesmanager.ui.components.ViewSortRow
import com.clpstudio.filesmanager.ui.components.formatSize
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Build 2 screens: type chips + total size + view menu, Grid / Real ratio / List, drag-selection. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class BuildBScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val out = File("build/screenshots")
    private lateinit var media: List<FileItem>

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ctx).coroutineContext(Dispatchers.Unconfined).build())
        out.mkdirs()
        val base = File(ctx.cacheDir, "media").apply { mkdirs() }
        val palettes = listOf(
            intArrayOf(0xFF1E6FF0.toInt(), 0xFF7CC4FF.toInt()), intArrayOf(0xFFF6B800.toInt(), 0xFFFF6B3D.toInt()),
            intArrayOf(0xFF16A34A.toInt(), 0xFFB5F5C8.toInt()), intArrayOf(0xFF9333EA.toInt(), 0xFFF0ABFC.toInt()),
            intArrayOf(0xFF0EA5E9.toInt(), 0xFF155E75.toInt()), intArrayOf(0xFFE11D48.toInt(), 0xFFFDA4AF.toInt()),
        )
        val sizes = listOf(300 to 400, 400 to 300, 300 to 300, 270 to 480, 480 to 270, 300 to 420)
        val now = System.currentTimeMillis()
        val names = listOf(
            "DCIM/Camera/IMG_20261031_104512.jpg", "Pictures/Screenshots/Screenshot_20261031_101144_Files.png",
            "Pictures/WhatsApp Images/IMG-20261031-WA0042.jpg", "DCIM/Camera/IMG_20261030_184233.jpg",
            "Pictures/Screenshots/Screenshot_20261030_220512_Chrome.png", "DCIM/Camera/IMG_20261029_090021.jpg",
            "Download/Panthula Aadhaar Card.png", "DCIM/Camera/IMG_20261027_171501.jpg",
            "Pictures/Instagram/IMG_20261024_120000.jpg", "DCIM/Camera/IMG_20261020_081245.jpg",
            "Pictures/Screenshots/Screenshot_20261012_093011_Maps.png", "DCIM/Camera/IMG_20260930_155959.jpg",
        )
        val ages = listOf(1L, 2, 3, 26, 27, 50, 60, 100, 160, 250, 470, 760)
        media = names.mapIndexed { i, rel ->
            val f = File(base, rel).apply { parentFile!!.mkdirs() }
            val (w, h) = sizes[i % sizes.size]
            val p = palettes[i % palettes.size]
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), p[0], p[1], Shader.TileMode.CLAMP) })
            c.drawCircle(w * 0.4f, h * 0.4f, minOf(w, h) * 0.2f, Paint().apply { color = 0x66FFFFFF })
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileItem(f.absolutePath, f.name, false, 1_200_000L + i * 734_000L, now - ages[i] * 3_600_000L)
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun shot(name: String, content: @Composable () -> Unit) {
        val preview = AsyncImagePreviewHandler { request ->
            val data = request.data
            val path = (data as? File)?.path ?: (data as? android.net.Uri)?.path ?: (data as? String)
            val bmp = path?.let { BitmapFactory.decodeFile(it) } ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            bmp.asImage()
        }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides preview) { content() }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage(File(out, "$name.png").path)
    }

    @Composable
    private fun MediaScreen(view: FileView, selection: SelectionState = SelectionState(), chosen: String = TypeFilters.ALL) {
        val items = TypeFilters.apply(Category.MEDIA, media, chosen)
        GlassScaffold(
            topBar = {
                if (selection.active) {
                    GlassTopBar(
                        title = "${selection.paths.size} selected",
                        navigation = { IconButton(onClick = {}) { Icon(Icons.Filled.Close, null) } },
                        actions = {
                            com.clpstudio.filesmanager.ui.components.PlaySelectedButtonPreview(selection.paths.size)
                            IconButton(onClick = {}) { Icon(Icons.Filled.Share, null) }
                            IconButton(onClick = {}) { Icon(Icons.Filled.Delete, null) }
                            IconButton(onClick = {}) { Icon(Icons.Filled.MoreVert, null) }
                        },
                    )
                } else {
                    GlassTopBar(
                        title = "Media", onBack = {},
                        actions = { IconButton(onClick = {}) { Icon(Icons.Filled.Search, null) } },
                        below = {
                            TypeChipsRow(TypeFilters.counted(Category.MEDIA, media, chosen), chosen) {}
                            ViewSortRow("${items.size} files · " + formatSize(items.sumOf { it.size }), view, {}, SortBy.DATE, false, { _, _ -> })
                        },
                    )
                }
            },
        ) { p ->
            FileCollection(
                items, view, selection, {},
                contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = p.calculateTopPadding() + 4.dp, bottom = 24.dp),
                sections = true,
            )
        }
    }

    @Test fun grid() = shot("files_r2_grid_filters") { FilesTheme(ThemeMode.LIGHT, liquidGlass = true) { MediaScreen(FileView.GRID) } }

    @Test fun realRatio() = shot("files_r2_real_ratio") { FilesTheme(ThemeMode.LIGHT, liquidGlass = true) { MediaScreen(FileView.LARGE) } }

    @Test fun listView() = shot("files_r2_list") { FilesTheme(ThemeMode.DARK, liquidGlass = true) { MediaScreen(FileView.LIST, chosen = "SCREENSHOTS") } }

    @Test fun dragSelected() = shot("files_r2_drag_select") {
        val sel = SelectionState().apply { selectAll(media.take(5).map { it.path }) }
        FilesTheme(ThemeMode.LIGHT, liquidGlass = true) { MediaScreen(FileView.GRID, sel) }
    }

    // Final build (r3): the category list in both themes and every view.
    @Test fun r3DarkGrid() = shot("files_r3_dark_category_grid") { FilesTheme(ThemeMode.DARK, liquidGlass = true) { MediaScreen(FileView.GRID) } }
    @Test fun r3LightGrid() = shot("files_r3_light_category_grid") { FilesTheme(ThemeMode.LIGHT, liquidGlass = true) { MediaScreen(FileView.GRID) } }
    @Test fun r3DarkList() = shot("files_r3_dark_category_list") { FilesTheme(ThemeMode.DARK, liquidGlass = true) { MediaScreen(FileView.LIST) } }
    @Test fun r3LightList() = shot("files_r3_light_category_list") { FilesTheme(ThemeMode.LIGHT, liquidGlass = true) { MediaScreen(FileView.LIST) } }
    @Test fun r3DarkRealRatio() = shot("files_r3_dark_real_ratio") { FilesTheme(ThemeMode.DARK, liquidGlass = true) { MediaScreen(FileView.LARGE) } }
    @Test fun r3DarkSelect() = shot("files_r3_dark_drag_select") {
        val sel = SelectionState().apply { selectAll(media.take(5).map { it.path }) }
        FilesTheme(ThemeMode.DARK, liquidGlass = true) { MediaScreen(FileView.GRID, sel) }
    }
}
