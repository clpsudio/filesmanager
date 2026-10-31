package com.clpstudio.filesmanager

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.clpstudio.filesmanager.data.StorageVolume
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.picker.PickerContent
import com.clpstudio.filesmanager.picker.PickerRequest
import com.clpstudio.filesmanager.picker.PickerSection
import com.clpstudio.filesmanager.picker.PickerUi
import com.clpstudio.filesmanager.ui.glass.GlassBackground
import com.clpstudio.filesmanager.ui.screens.DoubleTapSkipCard
import com.clpstudio.filesmanager.ui.screens.StorageBreakdown
import com.clpstudio.filesmanager.ui.screens.StorageContent
import com.clpstudio.filesmanager.ui.screens.VolumeInfo
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Bug-fix round 5: Files as a picker for other apps, the double-tap skip setting, the in-app storage screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoilApi::class, coil3.annotation.DelicateCoilApi::class)
class BugRound5ScreenshotTest {
    @get:org.junit.Rule val compose = createComposeRule()
    private val out = File("build/screenshots").apply { mkdirs() }
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    private val preview = AsyncImagePreviewHandler { request ->
        val path = when (val d = request.data) {
            is File -> d.path
            is Uri -> d.path
            is String -> d
            else -> d.toString()
        }
        val bmp = path?.let { BitmapFactory.decodeFile("$it.jpg") }
        (bmp ?: Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF334155.toInt()) }).asImage()
    }

    private fun photos(): List<FileItem> {
        val dir = File(ctx.cacheDir, "DCIM/Camera").apply { mkdirs() }
        val colors = listOf(0xFF1E6FF0, 0xFFF6B800, 0xFF16A34A, 0xFF9333EA, 0xFF0EA5E9, 0xFFE11D48, 0xFF64748B, 0xFFEA580C,
            0xFF14B8A6, 0xFF8B5CF6, 0xFFF43F5E, 0xFF22C55E, 0xFF3B82F6, 0xFFFACC15, 0xFF06B6D4, 0xFFA855F7, 0xFFEF4444, 0xFF10B981)
        return colors.mapIndexed { i, col ->
            val f = File(dir, "IMG_20261031_1%03d.jpg".format(100 + i * 7))
            val b = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(col.toInt()) }
            File(dir, f.name + ".jpg").outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            f.writeBytes(ByteArray(2_400_000 + i * 50_000))
            FileItem.of(f)
        }
    }

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ctx).coroutineContext(Dispatchers.Unconfined).build())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides preview) {
                FilesTheme(ThemeMode.DARK, liquidGlass = true) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(800)
        compose.onRoot().captureRoboImage(File(out, name).path)
    }

    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    @Test fun picker() {
        val items = photos()
        val req = PickerRequest.from("image/*", null, allowMultiple = true)
        shoot("files_r5_picker.png") {
            PickerContent(
                ui = PickerUi(request = req, section = PickerSection.PHOTOS, items = items),
                selected = setOf(items[1].path, items[4].path, items[5].path),
                onSection = {}, onQuery = {}, onOpenFolder = {}, onUp = {}, onTap = {}, onLongPress = {},
                onDone = {}, onCancel = {}, onAllowAccess = {},
            )
        }
    }

    @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    @Test fun skipSetting() {
        shoot("files_r5_skip_setting.png") {
            GlassBackground(Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Video player", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp, top = 24.dp))
                DoubleTapSkipCard(seconds = 15, onChange = {})
                DoubleTapSkipCard(seconds = 25, onChange = {})
            }
        }
    }

    @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    @Test fun storage() {
        val gb = 1L shl 30
        val internal = StorageVolume("/storage/emulated/0", "Internal storage", total = 128 * gb, free = 41 * gb)
        val sd = StorageVolume("/storage/1A2B-3C4D", "SD card", total = 64 * gb, free = 50 * gb)
        fun big(path: String, size: Long) = FileItem(path, path.substringAfterLast('/'), false, size, 1)
        val lists = mapOf(
            Category.PHOTOS to listOf(big("/storage/emulated/0/DCIM/a.jpg", 22 * gb), big("/storage/1A2B-3C4D/DCIM/b.jpg", 6 * gb)),
            Category.VIDEOS to listOf(big("/storage/emulated/0/Movies/v.mp4", 31 * gb), big("/storage/1A2B-3C4D/Movies/w.mp4", 5 * gb)),
            Category.AUDIO to listOf(big("/storage/emulated/0/Music/s.mp3", 4 * gb)),
            Category.DOCUMENTS to listOf(big("/storage/emulated/0/Documents/d.pdf", 3 * gb)),
        )
        fun dirs(root: String, vararg names: Pair<String, Int>) = names.map { (n, c) -> FileItem("$root/$n", n, true, 0, 1, childCount = c) }
        val vols = listOf(
            VolumeInfo(internal, StorageBreakdown.slices(internal, lists), dirs(internal.path, "Alarms" to 0, "DCIM" to 3, "Documents" to 42, "Download" to 318, "Movies" to 12, "Music" to 86, "Pictures" to 9)),
            VolumeInfo(sd, StorageBreakdown.slices(sd, lists), dirs(sd.path, "DCIM" to 2, "Movies" to 7)),
        )
        shoot("files_r5_storage.png") { StorageContent(vols, onBack = {}, onBrowse = {}, onCategory = {}) }
    }
}
