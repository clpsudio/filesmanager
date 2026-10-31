package com.clpstudio.filesmanager

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
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
                com.clpstudio.filesmanager.ui.screens.LoopVideosCard(com.clpstudio.filesmanager.data.LoopMode.FOREVER, 3) { _, _ -> }
                com.clpstudio.filesmanager.ui.screens.LoopVideosCard(com.clpstudio.filesmanager.data.LoopMode.TIMES, 3) { _, _ -> }
                DoubleTapSkipCard(seconds = 15, onChange = {})
                DoubleTapSkipCard(seconds = 25, onChange = {})
            }
        }
    }

    @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    @Test fun addPasswordDialog() {
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ctx).coroutineContext(Dispatchers.Unconfined).build())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                FilesTheme(ThemeMode.DARK, liquidGlass = true) {
                    // A busy PDF page behind the dialog: none of it may show through.
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF1B1D22)).padding(24.dp)) {
                        Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White).padding(20.dp)) {
                            repeat(60) { Text("Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor $it", color = androidx.compose.ui.graphics.Color.Black) }
                        }
                    }
                    com.clpstudio.filesmanager.ui.components.PdfLockDialog("Annual report 2026.pdf", onConfirm = { _, _ -> }, onDismiss = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(800)
        com.github.takahirom.roborazzi.captureScreenRoboImage(File(out, "files_r5_add_password_dialog.png").path)
    }

    @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    @Test fun monthGroups() {
        val utc = java.util.TimeZone.getDefault()
        fun at(m: Int, d: Int) = java.util.Calendar.getInstance(utc).apply { clear(); set(2026, m, d, 12, 0, 0) }.timeInMillis
        val items = listOf(
            FileItem("/s/IMG_1031.jpg", "IMG_1031.jpg", false, 2_400_000, at(9, 31)),
            FileItem("/s/IMG_1024.jpg", "IMG_1024.jpg", false, 3_100_000, at(9, 24)),
            FileItem("/s/VID_1012.mp4", "VID_1012.mp4", false, 48_000_000, at(9, 12)),
            FileItem("/s/IMG_0928.jpg", "IMG_0928.jpg", false, 2_000_000, at(8, 28)),
            FileItem("/s/IMG_0903.jpg", "IMG_0903.jpg", false, 2_700_000, at(8, 3)),
            FileItem("/s/Report.pdf", "Report.pdf", false, 900_000, at(7, 19)),
            FileItem("/s/IMG_0802.jpg", "IMG_0802.jpg", false, 2_200_000, at(7, 2)),
        )
        val sel = com.clpstudio.filesmanager.ui.components.SelectionState().apply { selectAll(listOf("/s/IMG_1031.jpg", "/s/IMG_1024.jpg", "/s/VID_1012.mp4", "/s/IMG_0928.jpg")) }
        shoot("files_r5_month_groups.png") {
            GlassBackground(Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.Row(Modifier.padding(start = 8.dp, top = 28.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("4 selected", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    com.clpstudio.filesmanager.ui.components.SelectAllToggle(false) {}
                    com.clpstudio.filesmanager.ui.components.SelectAllToggle(true) {}
                }
                com.clpstudio.filesmanager.ui.components.FileCollection(
                    items = items, view = com.clpstudio.filesmanager.data.FileView.LIST, selection = sel, onClick = {},
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp), sections = true, modifier = Modifier.height(620.dp),
                )
                com.clpstudio.filesmanager.ui.screens.GroupByCard(com.clpstudio.filesmanager.data.DateGrouping.MONTH) {}
            }
        }
    }

    @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    @Test fun folderFilters() {
        val cfg = mapOf("PHOTOS" to com.clpstudio.filesmanager.data.ChipConfig(listOf("APP_WHATSAPP", "CAMERA", "SCREENSHOTS"), setOf("RAW", "HEIC", "APP_SNAPCHAT")))
        shoot("files_r5_folder_filters.png") {
            com.clpstudio.filesmanager.ui.screens.FolderFiltersContent(cfg, onBack = {}, onChange = { _, _ -> }, startTab = 1)
        }
    }

    @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    @Test fun sortingSettings() {
        shoot("files_r5_sorting_settings.png") {
            GlassBackground(Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sorting & grouping", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp, top = 24.dp))
                com.clpstudio.filesmanager.ui.screens.DefaultSortCard(com.clpstudio.filesmanager.data.SortBy.DATE, false) { _, _ -> }
                com.clpstudio.filesmanager.ui.screens.GroupByCard(com.clpstudio.filesmanager.data.DateGrouping.MONTH) {}
                com.clpstudio.filesmanager.ui.screens.FolderFiltersRow {}
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
