package com.clpstudio.filesmanager

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.clpstudio.filesmanager.data.DuplicateSet
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.ManageStorageData
import com.clpstudio.filesmanager.data.StorageMath
import com.clpstudio.filesmanager.data.StorageVolume
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.ui.screens.CleanupPages
import com.clpstudio.filesmanager.ui.screens.ManageStorageContent
import com.clpstudio.filesmanager.ui.screens.StorageCleanupContent
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Round 5 item 6: our own Manage storage screen and its cleanup screen (tablet portrait, same data). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w800dp-h1280dp-port-mdpi")
@OptIn(ExperimentalCoilApi::class, coil3.annotation.DelicateCoilApi::class)
class ManageStorageScreenshotTest {
    @get:org.junit.Rule val compose = createComposeRule()
    private val out = File("build/screenshots").apply { mkdirs() }
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    private val palette = listOf(0xFF1E6FF0, 0xFFF6B800, 0xFF16A34A, 0xFF9333EA, 0xFF0EA5E9, 0xFFE11D48, 0xFF64748B, 0xFFEA580C, 0xFF14B8A6)
    private val preview = AsyncImagePreviewHandler { request ->
        val path = when (val d = request.data) { is File -> d.path; is Uri -> d.path; else -> d.toString() }.orEmpty()
        Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(palette[Math.floorMod(path.hashCode(), palette.size)].toInt()) }.asImage()
    }

    private val gb = 1L shl 30
    private val mb = 1L shl 20
    private val now = 1_793_450_000_000L // 31 Oct 2026
    private val day = StorageMath.DAY
    private val root = "/storage/emulated/0"
    private val sdRoot = "/storage/1A2B-3C4D"
    private val internal = StorageVolume(root, "Internal storage", total = 128 * gb, free = 81 * gb)
    private val sd = StorageVolume(sdRoot, "SD card", total = 64 * gb, free = 50 * gb)

    private fun f(path: String, size: Long, ageDays: Long, base: String = root) =
        FileItem("$base/$path", path.substringAfterLast('/'), false, size, now - ageDays * day)

    private fun internalFiles(): List<FileItem> {
        val l = ArrayList<FileItem>()
        repeat(132) { l += f("DCIM/Camera/VID_2026${1000 + it}.mp4", 50 * mb + it * 300_000L, ageDays = (it * 3).toLong()) }
        repeat(4) { l += f("Movies/Trip_${2024 + it % 2}_part$it.mp4", 420 * mb, ageDays = 240L + it) }
        repeat(900) { l += f("DCIM/Camera/IMG_2026${10000 + it}.jpg", 3 * mb + it * 9_000L, ageDays = (it / 4).toLong()) }
        repeat(60) { l += f("Pictures/Screenshots/Screenshot_2026${100 + it}.png", 900_000L, ageDays = 10L + it) }
        repeat(180) { l += f("Music/Track ${it + 1}.mp3", 7 * mb, ageDays = 50L + it * 3) }
        repeat(70) { l += f("Documents/Report $it.pdf", 6 * mb, ageDays = 20L + it) }
        repeat(14) { l += f("Download/Setup_v$it.apk", 120 * mb, ageDays = 5L + it * 9) }
        repeat(25) { l += f("Download/archive_$it.zip", 60 * mb, ageDays = 30L + it * 6) }
        repeat(40) { l += f("Download/old_download_$it.pdf", 12 * mb, ageDays = 100L + it) }
        repeat(30) { l += f("Android/media/com.whatsapp/backup_$it.crypt14", 100 * mb, ageDays = 3) }
        // Duplicates: three copies of a video, two of a photo.
        repeat(3) { l += f("Download/copy$it/Holiday clip.mp4", 64 * mb, ageDays = 12L + it) }
        repeat(2) { l += f("Pictures/dup$it/Family.jpg", 5 * mb, ageDays = 40L + it) }
        return l
    }

    private fun data(): Pair<ManageStorageData, Map<String, List<DuplicateSet>>> {
        val files = internalFiles()
        val r1 = StorageMath.report(internal, true, files, appsBytes = 14 * gb, binBytes = 320 * mb, now = now)
        val sdFiles = List(40) { f("Movies/Film $it.mkv", 200 * mb, 400, sdRoot) } + List(300) { f("DCIM/IMG_$it.jpg", 4 * mb, 300, sdRoot) }
        val r2 = StorageMath.report(sd, false, sdFiles, null, null, now)
        val hashes = files.associate { it.path to (if (it.name == "Holiday clip.mp4" || it.name == "Family.jpg") it.name else it.path) }
        val dups = StorageMath.groupDuplicates(r1.dupCandidates, hash = { hashes[it.path] })
        return ManageStorageData(listOf(r1, r2), usageAccess = true, unusedApps = 7) to mapOf(root to dups)
    }

    private fun shoot(name: String, mode: ThemeMode, content: @Composable () -> Unit) {
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ctx).coroutineContext(Dispatchers.Unconfined).build())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides preview) {
                FilesTheme(mode, liquidGlass = true) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(800)
        compose.onRoot().captureRoboImage(File(out, name).path)
    }

    @Test fun manageStorageDark() {
        val (d, dups) = data()
        shoot("files_r5_manage_storage_dark.png", ThemeMode.DARK) {
            ManageStorageContent(d, listOf(internal, sd), dups, {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {})
        }
    }

    @Test fun manageStorageLight() {
        val (d, dups) = data()
        shoot("files_r5_manage_storage_light.png", ThemeMode.LIGHT) {
            ManageStorageContent(
                d, listOf(internal, sd), dups, {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {},
                initiallyExpanded = true, initiallyMoreSuggestions = true,
            )
        }
    }

    @Test fun manageStorageLoading() {
        shoot("files_r5_manage_storage_loading.png", ThemeMode.DARK) {
            ManageStorageContent(null, listOf(internal), null, {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {})
        }
    }

    @Test fun cleanup() {
        val (d, _) = data()
        val pages = CleanupPages.large(d.volumes[0].large)
        val items = pages[0].groups[0].items
        shoot("files_r5_cleanup.png", ThemeMode.DARK) {
            StorageCleanupContent(
                page = pages[0], chips = pages.map { it.chip }, chipIndex = 0, onChip = {},
                selected = setOf(items[0].path, items[2].path, items[5].path),
                onToggle = {}, onAll = {}, onCancel = {}, onDelete = {},
            )
        }
    }

    @Test fun cleanupDuplicates() {
        val (_, dups) = data()
        val pages = CleanupPages.duplicates(dups.getValue(root))
        shoot("files_r5_cleanup_duplicates.png", ThemeMode.LIGHT) {
            StorageCleanupContent(
                page = pages[0], chips = emptyList(), chipIndex = 0, onChip = {},
                selected = pages[0].selectAll.toSet(),
                onToggle = {}, onAll = {}, onCancel = {}, onDelete = {},
            )
        }
    }
}
