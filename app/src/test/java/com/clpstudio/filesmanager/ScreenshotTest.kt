package com.clpstudio.filesmanager

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import android.graphics.BitmapFactory
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.media3.common.MediaMetadata
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.clpstudio.filesmanager.data.AppSettings
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.data.groupByFolder
import com.clpstudio.filesmanager.playback.PlayerUi
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.screens.FolderGroupsGrid
import com.clpstudio.filesmanager.ui.screens.HomeContent
import com.clpstudio.filesmanager.ui.screens.HomeState
import com.clpstudio.filesmanager.ui.screens.HomeTile
import com.clpstudio.filesmanager.ui.screens.SettingsContent
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.viewer.MusicPlayerContent
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

/**
 * Renders the main screens to PNG (Roborazzi). Record with:
 * ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*' -Proborazzi.test.record=true
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val out = File("build/screenshots")
    private lateinit var photos: List<FileItem>

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ctx).coroutineContext(Dispatchers.Unconfined).build())
        out.mkdirs()
        val base = File(ctx.cacheDir, "shots").apply { mkdirs() }
        val palettes = listOf(
            intArrayOf(0xFF1E6FF0.toInt(), 0xFF7CC4FF.toInt()), intArrayOf(0xFFF6B800.toInt(), 0xFFFF6B3D.toInt()),
            intArrayOf(0xFF16A34A.toInt(), 0xFFB5F5C8.toInt()), intArrayOf(0xFF9333EA.toInt(), 0xFFF0ABFC.toInt()),
            intArrayOf(0xFF0EA5E9.toInt(), 0xFF155E75.toInt()), intArrayOf(0xFFE11D48.toInt(), 0xFFFDA4AF.toInt()),
        )
        val folders = listOf("Camera", "WhatsApp Images", "Screenshots", "Instagram")
        var n = 0
        photos = folders.flatMapIndexed { fi, folder ->
            val dir = File(base, folder).apply { mkdirs() }
            (0 until 5 - fi).map { i ->
                val p = palettes[(n++) % palettes.size]
                val f = File(dir, "IMG_2026103${i}_${fi}0$i.png")
                val bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 240f, 240f, p[0], p[1], Shader.TileMode.CLAMP) })
                c.drawCircle(80f + 20 * i, 90f, 46f, Paint().apply { color = 0x66FFFFFF })
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                FileItem(f.absolutePath, f.name, false, 2_400_000L + i * 310_000, 1_793_400_000_000L - (fi * 5 + i) * 3_600_000L)
            }
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun shot(name: String, content: @Composable () -> Unit) {
        val preview = AsyncImagePreviewHandler { request ->
            val data = request.data
            val bmp = (data as? File)?.let { BitmapFactory.decodeFile(it.path) } ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            bmp.asImage()
        }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides preview) { content() }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage(File(out, "$name.png").path)
    }

    private fun homeState() = HomeState(
        used = 71_400_000_000, total = 128_000_000_000,
        counts = mapOf(
            HomeTile.RECENT to "18 items", HomeTile.PHOTOS to "2,431 items", HomeTile.VIDEOS to "186 items",
            HomeTile.AUDIO to "412 items", HomeTile.DOCUMENTS to "97 items", HomeTile.DOWNLOADS to "64 items",
            HomeTile.APPS to "APKs & installed", HomeTile.INTERNAL to "System storage", HomeTile.BIN to "3 items", HomeTile.VAULT to "Coming soon",
        ),
        recent = photos.take(8),
    )

    @Test fun home_glass_light() = shot("files_app_home_glass_light") {
        FilesTheme(ThemeMode.LIGHT, liquidGlass = true) { HomeContent(homeState(), {}, {}, {}, {}, { _, _ -> }) }
    }

    @Test fun home_glass_dark() = shot("files_app_home_glass_dark") {
        FilesTheme(ThemeMode.DARK, liquidGlass = true) { HomeContent(homeState(), {}, {}, {}, {}, { _, _ -> }) }
    }

    @Test fun home_normal_light() = shot("files_app_home_normal_light") {
        FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { HomeContent(homeState(), {}, {}, {}, {}, { _, _ -> }) }
    }

    @Test fun category_photos() = shot("files_app_category_photos") {
        FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
            val groups = groupByFolder(photos)
            GlassScaffold(topBar = { GlassTopBar("Photos", subtitle = "${photos.size} files in ${groups.size} folders", onBack = {}) }) { p ->
                FolderGroupsGrid(groups, p) {}
            }
        }
    }

    private fun art(): Bitmap {
        val bmp = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 600f, 600f, 0xFF1E6FF0.toInt(), 0xFFF6B800.toInt(), Shader.TileMode.CLAMP) })
        c.drawCircle(300f, 300f, 150f, Paint().apply { color = 0x55FFFFFF })
        c.drawCircle(300f, 300f, 40f, Paint().apply { color = 0xCC0B1020.toInt() })
        return bmp
    }

    private fun playerUi() = PlayerUi(
        hasMedia = true, isPlaying = true, title = "Kesariya", artist = "Arijit Singh", position = 83_000, duration = 268_000,
        queue = listOf("Kesariya", "Tum Hi Ho", "Apna Bana Le").map { MediaMetadata.Builder().setTitle(it).build() },
    )

    @Test fun music_player_glass() = shot("files_app_player_music") {
        FilesTheme(ThemeMode.DARK, liquidGlass = true) {
            MusicPlayerContent(playerUi(), {}, {}, {}, {}, {}, {}, {}, {}, artworkOverride = art().asImageBitmap())
        }
    }

    @Test fun music_player_normal() = shot("files_app_player_music_normal") {
        FilesTheme(ThemeMode.LIGHT, liquidGlass = false) {
            MusicPlayerContent(playerUi(), {}, {}, {}, {}, {}, {}, {}, {}, artworkOverride = art().asImageBitmap())
        }
    }

    @Test fun settings() = shot("files_app_settings") {
        FilesTheme(ThemeMode.LIGHT, liquidGlass = true) { SettingsContent(AppSettings(), {}, {}, {}, {}, {}) }
    }
}
