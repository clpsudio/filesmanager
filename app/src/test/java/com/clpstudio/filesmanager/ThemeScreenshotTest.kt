package com.clpstudio.filesmanager

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
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
import com.clpstudio.filesmanager.data.AppSettings
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.QuickDate
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.data.TypeFilter
import com.clpstudio.filesmanager.ui.screens.HomeContent
import com.clpstudio.filesmanager.ui.screens.HomeState
import com.clpstudio.filesmanager.ui.screens.HomeTile
import com.clpstudio.filesmanager.ui.screens.SearchContent
import com.clpstudio.filesmanager.ui.screens.SettingsContent
import com.clpstudio.filesmanager.ui.screens.formatCountLabel
import com.clpstudio.filesmanager.ui.screens.homeGridColumns
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Dark/light theme pass: Home (phone + tablet), Settings and Search. Record with
 * ./gradlew :app:testDebugUnitTest --tests '*ThemeScreenshotTest*' -Proborazzi.test.record=true
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class ThemeScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val out = File("build/screenshots")
    private lateinit var photos: List<FileItem>

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ctx).coroutineContext(Dispatchers.Unconfined).build())
        out.mkdirs()
        val dir = File(ctx.cacheDir, "theme_shots/Camera").apply { mkdirs() }
        val palettes = listOf(
            intArrayOf(0xFF1E6FF0.toInt(), 0xFF7CC4FF.toInt()), intArrayOf(0xFF16A34A.toInt(), 0xFFB5F5C8.toInt()),
            intArrayOf(0xFF9333EA.toInt(), 0xFFF0ABFC.toInt()), intArrayOf(0xFF0EA5E9.toInt(), 0xFF155E75.toInt()),
            intArrayOf(0xFFE11D48.toInt(), 0xFFFDA4AF.toInt()), intArrayOf(0xFF475569.toInt(), 0xFFCBD5E1.toInt()),
        )
        photos = (0 until 10).map { i ->
            val p = palettes[i % palettes.size]
            val f = File(dir, "IMG_2026103${i % 10}_0$i.png")
            val bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 240f, 240f, p[0], p[1], Shader.TileMode.CLAMP) })
            c.drawCircle(80f + 10 * i, 90f, 46f, Paint().apply { color = 0x66FFFFFF })
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileItem(f.absolutePath, f.name, false, 2_400_000L + i * 310_000, 1_793_400_000_000L - i * 3_600_000L)
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun shot(name: String, content: @Composable () -> Unit) {
        val preview = AsyncImagePreviewHandler { request ->
            val bmp = (request.data as? File)?.let { BitmapFactory.decodeFile(it.path) } ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
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
            HomeTile.RECENT to "18 items", HomeTile.FAVORITES to "12 items", HomeTile.PHOTOS to "2431 items", HomeTile.MEDIA to "2617 items",
            HomeTile.VIDEOS to "186 items", HomeTile.AUDIO to "412 items", HomeTile.DOCUMENTS to "1240 items", HomeTile.DOWNLOADS to "64 items",
            HomeTile.APPS to "APKs & installed", HomeTile.INTERNAL to "System storage", HomeTile.BIN to "3 items", HomeTile.VAULT to "Coming soon",
        ),
        recent = photos.take(8),
    )

    @Composable private fun home(mode: ThemeMode) = FilesTheme(mode, liquidGlass = true) { HomeContent(homeState(), {}, {}, {}, {}, { _, _ -> }) }

    @Test fun dark_home_phone() = shot("files_r2_dark_home") { home(ThemeMode.DARK) }

    @Test fun light_home_phone() = shot("files_r2_light_home") { home(ThemeMode.LIGHT) }

    @Config(qualifiers = "w1280dp-h800dp-land-hdpi")
    @Test fun dark_home_tablet_land() = shot("files_r2_dark_home_tablet") { home(ThemeMode.DARK) }

    @Config(qualifiers = "w800dp-h1280dp-port-hdpi")
    @Test fun dark_home_tablet_port() = shot("files_r2_dark_home_tablet_portrait") { home(ThemeMode.DARK) }

    @Config(qualifiers = "w1280dp-h800dp-land-hdpi")
    @Test fun light_home_tablet_land() = shot("files_r2_light_home_tablet") { home(ThemeMode.LIGHT) }

    @Test fun dark_settings() = shot("files_r2_dark_settings") {
        FilesTheme(ThemeMode.DARK, liquidGlass = true) { SettingsContent(AppSettings(), {}, {}, {}, {}, {}) }
    }

    @Test fun light_settings() = shot("files_r2_light_settings") {
        FilesTheme(ThemeMode.LIGHT, liquidGlass = true) { SettingsContent(AppSettings(), {}, {}, {}, {}, {}) }
    }

    @Composable private fun searchEmpty(mode: ThemeMode) = FilesTheme(mode, liquidGlass = true) {
        SearchContent(
            root = null, text = "", onText = {}, typedRangeLabel = null, quick = QuickDate.ANY, onQuick = {}, customLabel = null,
            types = emptySet(), onToggleType = {}, queryEmpty = true, results = emptyList(), searching = false, onBack = {}, onOpen = {}, autoFocus = false,
        )
    }

    @Composable private fun searchResults(mode: ThemeMode) = FilesTheme(mode, liquidGlass = true) {
        SearchContent(
            root = null, text = "IMG", onText = {}, typedRangeLabel = null, quick = QuickDate.THIS_WEEK, onQuick = {}, customLabel = null,
            types = setOf(TypeFilter.PHOTOS), onToggleType = {}, queryEmpty = false, results = photos, searching = false, onBack = {}, onOpen = {}, autoFocus = false,
        )
    }

    @Test fun dark_search() = shot("files_r2_dark_search") { searchEmpty(ThemeMode.DARK) }

    @Test fun dark_search_results() = shot("files_r2_dark_search_results") { searchResults(ThemeMode.DARK) }

    @Test fun light_search() = shot("files_r2_light_search") { searchEmpty(ThemeMode.LIGHT) }

    @Test fun light_search_results() = shot("files_r2_light_search_results") { searchResults(ThemeMode.LIGHT) }

    @Test fun r3_dark_settings() = shot("files_r3_dark_settings") {
        FilesTheme(ThemeMode.DARK, liquidGlass = true) { SettingsContent(AppSettings(), {}, {}, {}, {}, {}) }
    }

    @Test fun r3_light_settings() = shot("files_r3_light_settings") {
        FilesTheme(ThemeMode.LIGHT, liquidGlass = true) { SettingsContent(AppSettings(), {}, {}, {}, {}, {}) }
    }

    @Test fun r3_dark_home() = shot("files_r3_dark_home") { home(ThemeMode.DARK) }

    @Test fun r3_light_home() = shot("files_r3_light_home") { home(ThemeMode.LIGHT) }

    @Test fun columnsAdaptToWidth() {
        assertEquals(3, homeGridColumns(393.dp))
        assertEquals(3, homeGridColumns(360.dp))
        assertEquals(4, homeGridColumns(800.dp))
        assertEquals(6, homeGridColumns(1280.dp))
    }

    @Test fun countLabelGetsThousandsSeparator() {
        assertEquals("1,240 items", formatCountLabel("1240 items"))
        assertEquals("1 item", formatCountLabel("1 item"))
        assertEquals("Coming soon", formatCountLabel("Coming soon"))
        assertEquals("2,431 items", formatCountLabel("2,431 items"))
        assertEquals(null, formatCountLabel(null))
    }
}
