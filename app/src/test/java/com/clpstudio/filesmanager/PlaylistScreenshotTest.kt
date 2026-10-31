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
import com.clpstudio.filesmanager.data.Playlist
import com.clpstudio.filesmanager.data.PlaylistType
import com.clpstudio.filesmanager.ui.screens.PlaylistDetailContent
import com.clpstudio.filesmanager.ui.screens.PlaylistsContent

/** Renders Home's "Playlists" section and the playlist screens (Roborazzi). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class PlaylistScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val out = File("build/screenshots")
    private lateinit var photos: List<FileItem>
    private lateinit var songs: List<FileItem>

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ctx).coroutineContext(Dispatchers.Unconfined).build())
        out.mkdirs()
        val base = File(ctx.cacheDir, "pl").apply { mkdirs() }
        val palettes = listOf(
            intArrayOf(0xFF1E6FF0.toInt(), 0xFF7CC4FF.toInt()), intArrayOf(0xFFF6B800.toInt(), 0xFFFF6B3D.toInt()),
            intArrayOf(0xFF9333EA.toInt(), 0xFFF0ABFC.toInt()), intArrayOf(0xFF0EA5E9.toInt(), 0xFF155E75.toInt()),
            intArrayOf(0xFFE11D48.toInt(), 0xFFFDA4AF.toInt()), intArrayOf(0xFF334155.toInt(), 0xFF94A3B8.toInt()),
        )
        photos = (0 until 6).map { i ->
            val p = palettes[i]
            val f = File(base, "IMG_2026103${i}_10$i.png")
            val bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 240f, 240f, p[0], p[1], Shader.TileMode.CLAMP) })
            c.drawCircle(80f + 20 * i, 90f, 46f, Paint().apply { color = 0x66FFFFFF })
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileItem(f.absolutePath, f.name, false, 2_400_000L + i * 310_000, 1_793_400_000_000L - i * 3_600_000L)
        }
        songs = listOf("Naatu Naatu.mp3", "Samajavaragamana.mp3", "Butta Bomma.mp3", "Srivalli.mp3", "Oo Antava.mp3").mapIndexed { i, n ->
            FileItem("/storage/emulated/0/Music/$n", n, false, 4_200_000L + i * 600_000, 0)
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
            HomeTile.APPS to "APKs & installed", HomeTile.INTERNAL to "System storage", HomeTile.BIN to "3 items", HomeTile.VAULT to "Set up",
        ),
        recent = emptyList(),
    )
    private val counts = mapOf(PlaylistType.AUDIO to 3, PlaylistType.VIDEO to 1, PlaylistType.PHOTO to 0, PlaylistType.GALLERY to 2)

    @Config(qualifiers = "w393dp-h1100dp-xxhdpi")
    @Test fun home_playlists_light() = shot("files_pl_home_light") {
        FilesTheme(ThemeMode.LIGHT, liquidGlass = true) { HomeContent(homeState(), {}, {}, {}, {}, { _, _ -> }, playlistCounts = counts) }
    }

    @Config(qualifiers = "w393dp-h1100dp-xxhdpi")
    @Test fun home_playlists_dark_plain() = shot("files_pl_home_dark_plain") {
        FilesTheme(ThemeMode.DARK, liquidGlass = false) { HomeContent(homeState(), {}, {}, {}, {}, { _, _ -> }, playlistCounts = counts) }
    }

    private fun galleryLists() = listOf(
        Playlist("1", PlaylistType.GALLERY, "Goa trip 2026", photos.map { it.path }, cover = photos[3].path),
        Playlist("2", PlaylistType.GALLERY, "Family", photos.take(2).map { it.path }),
        Playlist("3", PlaylistType.GALLERY, "Empty one"),
    )

    @Test fun playlists_gallery_light() = shot("files_pl_list_light") {
        FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
            val lists = galleryLists()
            PlaylistsContent(PlaylistType.GALLERY, lists, lists.associate { it.id to it.items }, {}, {}, {})
        }
    }

    @Test fun playlists_gallery_dark() = shot("files_pl_list_dark") {
        FilesTheme(ThemeMode.DARK, liquidGlass = true) {
            val lists = galleryLists()
            PlaylistsContent(PlaylistType.GALLERY, lists, lists.associate { it.id to it.items }, {}, {}, {})
        }
    }

    @Test fun playlist_audio_detail_light() = shot("files_pl_detail_audio_light") {
        FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
            val p = Playlist("a", PlaylistType.AUDIO, "Telugu hits", songs.map { it.path } + "/storage/emulated/0/Music/gone.mp3", cover = photos[0].path)
            PlaylistDetailContent(p, songs, 1, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }

    @Test fun playlist_photo_detail_dark_plain() = shot("files_pl_detail_photo_dark") {
        FilesTheme(ThemeMode.DARK, liquidGlass = false) {
            val p = Playlist("p", PlaylistType.PHOTO, "Best photos", photos.map { it.path })
            PlaylistDetailContent(p, photos, 0, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }

    @Test fun playlist_empty_light() = shot("files_pl_detail_empty") {
        FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
            PlaylistDetailContent(Playlist("v", PlaylistType.VIDEO, "Movies"), emptyList(), 0, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
}
