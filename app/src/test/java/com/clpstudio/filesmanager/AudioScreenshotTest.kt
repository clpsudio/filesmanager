package com.clpstudio.filesmanager

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

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

/** Audio: the floating mini player over a list, and the full player with the volume pill. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class AudioScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val out = File("build/screenshots")

    private fun shot(name: String, content: @Composable () -> Unit) {
        out.mkdirs()
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { content() } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage(File(out, "$name.png").path)
    }

    private val playing = com.clpstudio.filesmanager.playback.PlayerUi(
        hasMedia = true, isPlaying = true, title = "Arijit Singh - Tum Hi Ho (Aashiqui 2) Official Lyrical Video.mp3",
        artist = "Aashiqui 2", position = 95_000, duration = 262_000,
    )

    @Test fun miniPlayer() = shot("files_r3_audio_miniplayer") {
        FilesTheme(ThemeMode.DARK, liquidGlass = true) {
            val now = System.currentTimeMillis()
            val songs = (1..12).map { FileItem("/storage/emulated/0/Music/Track $it - Artist name.mp3", "Track $it - Artist name.mp3", false, 3_400_000L + it * 120_000L, now - it * 3_600_000L) }
            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(com.clpstudio.filesmanager.ui.LocalMiniPlayerInset provides com.clpstudio.filesmanager.ui.screens.MiniPlayerHeight) {
                    GlassScaffold(topBar = { GlassTopBar(title = "Audio", onBack = {}) }) { p ->
                        FileCollection(songs, FileView.LIST, SelectionState(), {}, contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = p.calculateTopPadding() + 4.dp, bottom = 24.dp))
                    }
                }
                Box(Modifier.align(Alignment.BottomCenter)) { com.clpstudio.filesmanager.ui.screens.MiniPlayer(playing, onOpen = {}) }
            }
        }
    }

    @Test fun volumePill() = shot("files_r3_audio_volume") {
        FilesTheme(ThemeMode.DARK, liquidGlass = true) {
            val pill = remember { com.clpstudio.filesmanager.viewer.LevelPillState().apply { show(com.clpstudio.filesmanager.viewer.PillKind.VOLUME, 0.6f) } }
            com.clpstudio.filesmanager.viewer.MusicPlayerContent(
                ui = playing, onBack = {}, onPlayPause = {}, onNext = {}, onPrevious = {}, onSeek = {}, onShuffle = {}, onRepeat = {}, onPick = {},
                pill = pill,
            )
        }
    }
}
