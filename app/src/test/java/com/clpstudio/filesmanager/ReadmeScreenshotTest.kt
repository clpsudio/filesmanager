package com.clpstudio.filesmanager

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.media3.common.MediaMetadata
import androidx.navigation.compose.rememberNavController
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
import com.clpstudio.filesmanager.playback.PlayerUi
import com.clpstudio.filesmanager.ui.Opener
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.screens.BrowserScreen
import com.clpstudio.filesmanager.ui.screens.HomeContent
import com.clpstudio.filesmanager.ui.screens.HomeState
import com.clpstudio.filesmanager.ui.screens.HomeTile
import com.clpstudio.filesmanager.ui.screens.LicencesListContent
import com.clpstudio.filesmanager.ui.screens.SearchContent
import com.clpstudio.filesmanager.ui.screens.SettingsContent
import com.clpstudio.filesmanager.ui.security.AppLockScreen
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.ui.vault.VaultSetup
import com.clpstudio.filesmanager.viewer.MusicPlayerContent
import com.clpstudio.filesmanager.viewer.OfficePage
import com.clpstudio.filesmanager.viewer.ZipViewerScreen
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The previews used in README.md (docs/screenshots). Demo file names only, no real data.
 * Record with:
 *   ./gradlew :app:testDebugUnitTest --tests '*ReadmeScreenshotTest*' -Proborazzi.test.record=true
 * then copy the PNG files from app/build/readme-screenshots to docs/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class ReadmeScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val out = File("build/readme-screenshots").apply { mkdirs() }
    private lateinit var ctx: Application
    private lateinit var demo: File
    private lateinit var root: String
    private lateinit var photos: List<FileItem>

    private val t0 = 1_793_400_000_000L // 31 Oct 2026

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Graph.init(ctx)
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ctx).coroutineContext(Dispatchers.Unconfined).build())
        // A made-up "Internal storage" volume, so breadcrumbs and paths read like a phone's.
        val storage = File(ctx.cacheDir, "readme_demo").apply { deleteRecursively(); mkdirs() }
        com.clpstudio.filesmanager.data.SdCards.override =
            listOf(com.clpstudio.filesmanager.data.MountedVolume(storage.absolutePath, null, removable = false, primary = true))
        root = storage.absolutePath
        demo = File(storage, "Documents").apply { mkdirs() }
        val camera = File(storage, "DCIM/Camera").apply { mkdirs() }
        val palettes = listOf(
            0xFF1E6FF0 to 0xFF7CC4FF, 0xFFF6B800 to 0xFFFF6B3D, 0xFF16A34A to 0xFFB5F5C8,
            0xFF9333EA to 0xFFF0ABFC, 0xFF0EA5E9 to 0xFF155E75, 0xFFE11D48 to 0xFFFDA4AF,
        )
        photos = (0 until 8).map { i ->
            val (a, b) = palettes[i % palettes.size]
            val f = File(camera, "IMG_2026103${i % 10}_demo$i.png")
            val bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 240f, 240f, a.toInt(), b.toInt(), Shader.TileMode.CLAMP) })
            c.drawCircle(80f + 12 * i, 90f, 46f, Paint().apply { color = 0x66FFFFFF })
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            f.setLastModified(t0 - i * 3_600_000L)
            FileItem(f.absolutePath, f.name, false, f.length(), f.lastModified())
        }
        for ((i, name) in listOf("Invoices", "Recipes", "Trip plans").withIndex()) {
            val d = File(demo, name).apply { mkdirs() }
            repeat(3 + i) { n -> File(d, "demo-$n.txt").writeText("Demo file $n") }
            d.setLastModified(t0 - (i + 1) * 86_400_000L)
        }
        val files = listOf(
            "Budget 2026.txt" to 18_400, "Meeting notes.md" to 6_200, "Packing list.txt" to 1_900,
            "Project plan.txt" to 42_000, "Reading list.md" to 3_300,
        )
        files.forEachIndexed { i, (name, size) ->
            val f = File(demo, name)
            f.writeText("Demo content for $name\n" + "x".repeat(size))
            f.setLastModified(t0 - i * 7_200_000L)
        }
        val zip = File(demo, "Holiday photos.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            for (e in listOf("Day 1/", "Day 2/", "Day 3/")) z.putNextEntry(ZipEntry(e).apply { time = t0 }).also { z.closeEntry() }
            for ((n, text) in listOf(
                "Day 1/beach.txt" to "demo", "Day 2/fort.txt" to "demo", "itinerary.txt" to "Demo itinerary\n".repeat(400),
                "packing-list.txt" to "Demo packing list\n".repeat(120), "hotel-booking.txt" to "Demo booking\n".repeat(60),
            )) {
                z.putNextEntry(ZipEntry(n).apply { time = t0 })
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        zip.setLastModified(t0 - 5 * 3_600_000L)
    }

    @After fun tearDown() {
        com.clpstudio.filesmanager.data.SdCards.override = null
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun shot(name: String, waitFor: String? = null, content: @Composable () -> Unit) {
        val preview = AsyncImagePreviewHandler { request ->
            val bmp = (request.data as? File)?.let { BitmapFactory.decodeFile(it.path) } ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            bmp.asImage()
        }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides preview) { content() }
        }
        if (waitFor != null) {
            compose.waitUntil(15_000) { compose.onAllNodesWithText(waitFor, substring = true).fetchSemanticsNodes().isNotEmpty() }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage(File(out, "$name.png").path)
    }

    private fun homeState() = HomeState(
        used = 71_400_000_000, total = 128_000_000_000,
        counts = mapOf(
            HomeTile.RECENT to "18 items", HomeTile.FAVORITES to "12 items", HomeTile.PHOTOS to "2,431 items", HomeTile.MEDIA to "2,617 items",
            HomeTile.VIDEOS to "186 items", HomeTile.AUDIO to "412 items", HomeTile.DOCUMENTS to "1,240 items", HomeTile.DOWNLOADS to "64 items",
            HomeTile.APPS to "APKs & installed", HomeTile.INTERNAL to "System storage", HomeTile.BIN to "3 items",
            HomeTile.HIDDEN to "All in one place", HomeTile.VAULT to "12 files",
        ),
        recent = photos,
    )

    @Test fun home_light() = shot("01-home-light") {
        FilesTheme(ThemeMode.LIGHT, liquidGlass = true) { HomeContent(homeState(), {}, {}, {}, {}, { _, _ -> }) }
    }

    @Test fun home_dark() = shot("02-home-dark") {
        FilesTheme(ThemeMode.DARK, liquidGlass = true) { HomeContent(homeState(), {}, {}, {}, {}, { _, _ -> }) }
    }

    @Test fun folder() = shot("03-folder", waitFor = "Holiday photos.zip") {
        FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
            val opener = Opener(LocalContext.current, rememberNavController())
            BrowserScreen(demo.absolutePath, opener, onBack = {}, onSearchAll = {})
        }
    }

    @Test fun search() = shot("04-search") {
        // Search results only need names, sizes and dates; the paths are a phone's, not this test's.
        val phone = "/storage/emulated/0"
        val results = listOf(
            FileItem("$phone/Documents/Project plan.txt", "Project plan.txt", false, 42_000, t0 - 7_200_000L),
            FileItem("$phone/Documents/Trip plans", "Trip plans", true, 0, t0 - 86_400_000L),
            FileItem("$phone/Download/Weekly meal plan.md", "Weekly meal plan.md", false, 3_300, t0 - 2 * 86_400_000L),
            FileItem("$phone/Documents/Work/Plan B notes.txt", "Plan B notes.txt", false, 1_900, t0 - 5 * 86_400_000L),
        )
        FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
            SearchContent(
                root = null, text = "plan", onText = {}, typedRangeLabel = null, quick = QuickDate.ANY, onQuick = {}, customLabel = null,
                types = emptySet(), onToggleType = {}, queryEmpty = false, results = results,
                searching = false, onBack = {}, onOpen = {}, autoFocus = false,
            )
        }
    }

    @Test fun archive() = shot("05-archive", waitFor = "itinerary.txt") {
        FilesTheme(ThemeMode.LIGHT, liquidGlass = true) { ZipViewerScreen(File(demo, "Holiday photos.zip").path, onBack = {}) {} }
    }

    @Test fun document() {
        val src = File(javaClass.classLoader!!.getResource("docx/word_sample.docx")!!.toURI())
        val f = File(demo, "Quarterly report.docx").also { src.copyTo(it, overwrite = true) }
        val item = FileItem(f.absolutePath, f.name, false, f.length(), t0)
        shot("06-document", waitFor = "underlined") {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                GlassScaffold(topBar = { GlassTopBar(f.name, onBack = {}) }) { p ->
                    OfficePage(item, Modifier.fillMaxSize(), p.calculateTopPadding()) {}
                }
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

    @Test fun music() = shot("07-music-player") {
        FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
            MusicPlayerContent(
                PlayerUi(
                    hasMedia = true, isPlaying = true, title = "Morning Walk", artist = "Demo Artist", position = 83_000, duration = 214_000,
                    queue = listOf("Morning Walk", "City Lights", "Slow Rain").map { MediaMetadata.Builder().setTitle(it).build() },
                ),
                {}, {}, {}, {}, {}, {}, {}, {}, artworkOverride = art().asImageBitmap(),
            )
        }
    }

    @Test fun vault() = shot("08-vault-setup", waitFor = "No recovery") {
        FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { VaultSetup(onBack = {}) }
    }

    @Test fun appLock() = shot("09-app-lock", waitFor = "Files is locked") {
        FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { AppLockScreen(androidx.activity.compose.LocalActivity.current!!) }
    }

    @Test fun settings() = shot("10-settings") {
        FilesTheme(ThemeMode.LIGHT, liquidGlass = true) { SettingsContent(AppSettings(), {}, {}, {}, {}, {}) }
    }

    @Test fun licences() = shot("11-licences", waitFor = "CLP Studio's own code") {
        FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { LicencesListContent(onBack = {}, onOpen = {}) }
    }
}
