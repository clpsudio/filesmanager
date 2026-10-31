package com.clpstudio.filesmanager

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.clpstudio.filesmanager.data.AppSettings
import com.clpstudio.filesmanager.data.ArchiveFormat
import com.clpstudio.filesmanager.data.ArchiveOps
import com.clpstudio.filesmanager.data.DeleteRules
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.HiddenScan
import com.clpstudio.filesmanager.data.PermanentDelete
import com.clpstudio.filesmanager.data.Playlist
import com.clpstudio.filesmanager.data.PlaylistType
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.ui.components.FileActionDialogs
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.components.SelectionActionsRow
import com.clpstudio.filesmanager.ui.components.rememberFileActions
import com.clpstudio.filesmanager.ui.screens.PlaylistDetailContent
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.viewer.PagerVideo
import com.clpstudio.filesmanager.viewer.VideoClose
import com.clpstudio.filesmanager.viewer.VideoClose.OnStop
import com.clpstudio.filesmanager.viewer.ZipViewerScreen
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Item 47 (Delete wherever files are selected; hidden files: Delete permanently only, no Unhide),
 * item 48 (video sound stops when the video is closed), item 49 (no "Allow changing file extension"),
 * item 50 (no Delete inside APKs).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class DeleteEverywhereTest {
    @get:Rule val compose = createComposeRule()
    private val root = "/storage/emulated/0"
    private val shots = "/home/user/tab/files"
    private lateinit var ctx: Application

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Graph.init(ctx)
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ctx).coroutineContext(Dispatchers.Unconfined).build())
    }

    // ---------- hidden = permanent ----------

    @Test fun whatCountsAsHiddenForDelete() {
        val noMedia = setOf("$root/WhatsApp/Media/Private")
        fun hidden(p: String) = DeleteRules.isHidden(p, listOf(root)) { it in noMedia }
        assertTrue(hidden("$root/.notes.txt"))                      // dot-file
        assertTrue(hidden("$root/.stuff/a/b/photo.jpg"))            // inside a dot-folder, any depth
        assertTrue(hidden("$root/DCIM/.hidden/IMG_1.jpg"))          // the app's own Hide folder
        assertTrue(hidden("$root/WhatsApp/Media/Private/v.mp4"))    // .nomedia folder
        assertTrue(hidden("$root/WhatsApp/Media/Private/sub/x.jpg"))
        assertFalse(hidden("$root/DCIM/Camera/IMG_2.jpg"))
        assertFalse(hidden("$root/Download/report.pdf"))
    }

    @Test fun planSendsHiddenToPermanentAndTheRestToBin() {
        val paths = listOf("$root/.a.jpg", "$root/DCIM/b.jpg", "$root/.cfg", "$root/DCIM/b.jpg")
        val plan = DeleteRules.plan(paths, isDir = { it.endsWith(".cfg") }) { DeleteRules.isHidden(it, listOf(root)) { false } }
        assertEquals(listOf("$root/.a.jpg", "$root/.cfg"), plan.permanent)
        assertEquals(listOf("$root/DCIM/b.jpg"), plan.toBin)
        assertTrue(plan.mixed); assertEquals(3, plan.total); assertEquals(1, plan.folders)
        assertEquals("Delete", plan.confirmLabel())
        assertTrue(plan.message().contains("2 hidden items will be deleted permanently"))
        assertTrue(plan.message().contains("can't be undone"))

        val onlyHidden = DeleteRules.plan(listOf("$root/.a.jpg")) { true }
        assertTrue(onlyHidden.allPermanent)
        assertEquals("Delete .a.jpg permanently?", onlyHidden.title(".a.jpg"))
        assertEquals("Delete permanently", onlyHidden.confirmLabel())
        assertTrue(onlyHidden.message().contains("skips the Bin")); assertTrue(onlyHidden.message().endsWith("This can't be undone."))

        val normal = DeleteRules.plan(listOf("$root/x.jpg", "$root/y.jpg")) { false }
        assertEquals("Move to Bin", normal.confirmLabel())
        assertEquals("Delete 2 items?", normal.title(null))
    }

    @Test fun permanentDeleteRemovesTheFileAndSkipsTheBin() = runBlocking {
        val dir = File(ctx.cacheDir, "f10").apply { deleteRecursively(); mkdirs() }
        val dot = File(dir, ".secret.txt").apply { writeText("x") }
        val hideDir = File(dir, ".hidden").apply { mkdirs() }
        File(hideDir, ".nomedia").writeText("")
        val hiddenPhoto = File(hideDir, "IMG_1.jpg").apply { writeText("jpg") }
        val dotFolder = File(dir, ".private").apply { mkdirs() }
        File(dotFolder, "a.mp4").writeText("v")
        val binBefore = Graph.bin.list().size
        val n = PermanentDelete.delete(ctx, listOf(dot, hiddenPhoto, dotFolder))
        assertEquals(3, n)
        assertFalse(dot.exists()); assertFalse(hiddenPhoto.exists()); assertFalse(dotFolder.exists())
        assertFalse("the empty Hide folder goes too", hideDir.exists())
        assertEquals("nothing went to the Bin", binBefore, Graph.bin.list().size)
        assertTrue(Graph.bin.list().none { it.name == ".secret.txt" || it.name == "IMG_1.jpg" })
    }

    // ---------- every selection surface has Delete ----------

    /** Each selection bar in the app and the Delete it must carry (content description in its source). */
    private val surfaces = mapOf(
        "Folders list/grid, SD card, Categories (Photos/Videos/Audio/Documents/Downloads/Archives), albums, Recent, Favorites, Manage storage lists, Search" to
            ("ui/components/FileActions.kt" to "Icon(Icons.Filled.Delete, \"Delete\")"),
        "APKs tab (Apps)" to ("ui/screens/ApkSelection.kt" to "Icon(Icons.Filled.Delete, \"Delete\")"),
        "Hidden files" to ("ui/screens/HiddenFilesScreen.kt" to "\"Delete permanently\""),
        "Playlist items" to ("ui/screens/PlaylistScreens.kt" to "Icon(Icons.Filled.Delete, \"Delete\")"),
        "Search results" to ("ui/screens/SearchScreen.kt" to "SelectionActionsRow("),
        "Clean up (duplicates, large files)" to ("ui/screens/StorageCleanupScreen.kt" to "AppState.delete("),
        "Bin" to ("ui/screens/BinScreen.kt" to "deleteForever("),
        "Vault" to ("ui/vault/VaultScreens.kt" to "\"Delete forever\""),
        "Inside a ZIP" to ("viewer/ZipViewerScreen.kt" to "Icon(Icons.Filled.Delete, \"Delete\")"),
    )

    private fun src(rel: String) = File("src/main/java/com/clpstudio/filesmanager/$rel").readText()

    @Test fun everySelectionSurfaceHasDelete() {
        for ((surface, where) in surfaces) assertTrue("$surface: no Delete in ${where.first}", src(where.first).contains(where.second))
        // Screens that select files through the shared bar: Browser, Category (list + grid + albums + Recent), Favorites,
        // Manage-storage kind lists and Search.
        assertTrue(src("ui/screens/BrowserScreen.kt").contains("SelectionActionsRow("))
        assertTrue(src("ui/screens/CategoryScreen.kt").contains("SelectionActionsRow("))
        assertTrue(src("ui/screens/FavoritesScreen.kt").contains("FileListScreen("))
        assertTrue(src("ui/screens/StorageKindScreen.kt").contains("FileListScreen("))
    }

    @Test fun noUnhideAnywhere() {
        val main = File("src/main/java")
        val offenders = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { f ->
            val t = f.readText()
            t.contains("\"Unhide\"") || t.contains("HiddenOps") || Regex("""fun unhide\(""").containsMatchIn(t) || t.contains("unhideTarget")
        }.map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun sharedSelectionBarHasDelete_render() {
        val items = listOf(
            FileItem("$root/DCIM/IMG_1.jpg", "IMG_1.jpg", false, 1000, 1, FileKind.IMAGE),
            FileItem("$root/DCIM/IMG_2.jpg", "IMG_2.jpg", false, 1000, 1, FileKind.IMAGE),
        )
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = false) {
                val actions = rememberFileActions(afterCopyOrMove = {})
                Box(Modifier.fillMaxSize()) {
                    GlassTopBar("2 selected", actions = { SelectionActionsRow(items, actions) {} })
                }
            }
        }
        compose.onNodeWithContentDescription("Delete").assertExists()
        compose.onNodeWithContentDescription("Share").assertExists()
        compose.onRoot().captureRoboImage("$shots/files_r10_selection_bar.png")
    }

    @Test fun hiddenFilesScreen_selectionHasOnlyDeletePermanently_render() {
        val dir = File(ctx.cacheDir, "hid").apply { deleteRecursively(); mkdirs() }
        val a = File(dir, ".notes.txt").apply { writeText("x") }
        val b = File(File(dir, ".hidden").apply { mkdirs() }, "IMG_7.jpg").apply { writeText("x") }
        HiddenScan.setForTest(listOf(FileItem.of(a), FileItem.of(b)))
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = false) {
                val nav = androidx.navigation.compose.rememberNavController()
                val opener = remember { com.clpstudio.filesmanager.ui.Opener(ctx, nav) }
                com.clpstudio.filesmanager.ui.screens.HiddenFilesScreen(opener, onBack = {}, onFolder = {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(".notes.txt").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Delete permanently").assertExists()
        compose.onAllNodesWithContentDescription("Unhide").assertCountEquals(0)
        compose.onAllNodesWithText("Unhide").assertCountEquals(0)
        compose.onRoot().captureRoboImage("$shots/files_r10_hidden_selection.png")
        HiddenScan.setForTest(null)
    }

    @Test fun hiddenDeleteConfirm_render() {
        val dir = File(ctx.cacheDir, "hid2").apply { deleteRecursively(); mkdirs() }
        val a = File(dir, ".secret.mp4").apply { writeText("x") }
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = false) {
                val actions = rememberFileActions(afterCopyOrMove = {})
                actions.deleteTargets = listOf(FileItem.of(a))
                FileActionDialogs(actions)
            }
        }
        compose.onNodeWithText("Delete permanently").assertExists()
        compose.onAllNodesWithText("Move to Bin").assertCountEquals(0)
        compose.onRoot().captureRoboImage("$shots/files_r10_hidden_delete_confirm.png")
    }

    @Test fun playlistSelectionHasDeleteAndRemove_render() {
        val dir = File(ctx.cacheDir, "pl10").apply { mkdirs() }
        val songs = (1..4).map { File(dir, "Song $it.mp3").apply { writeText("x") } }.map { FileItem.of(it) }
        val p = Playlist("p1", PlaylistType.AUDIO, "Road trip", songs.map { it.path }, null, 1L, 1L)
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = false) {
                PlaylistDetailContent(
                    playlist = p, items = songs, missing = 0, onBack = {}, onPlay = {}, onShuffle = {}, onAdd = {}, onRemove = {},
                    onReorder = {}, onMissing = {}, onRename = {}, onCover = {}, onClearCover = {}, onDelete = {},
                    initialSelection = setOf(songs[0].path, songs[2].path),
                )
            }
        }
        compose.onNodeWithContentDescription("Delete").assertExists()
        compose.onNodeWithContentDescription("Remove from playlist").assertExists()
        compose.onNodeWithText("2 selected").assertExists()
        compose.onRoot().captureRoboImage("$shots/files_r10_playlist_selection.png")
    }

    @Test fun searchResultsCanBeSelectedAndDeleted() {
        val items = listOf(FileItem("$root/Download/invoice.pdf", "invoice.pdf", false, 1000, 1, FileKind.PDF))
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = false) {
                val selection = remember { com.clpstudio.filesmanager.ui.components.SelectionState().apply { toggle(items[0].path) } }
                val actions = rememberFileActions(afterCopyOrMove = {})
                com.clpstudio.filesmanager.ui.screens.SearchContent(
                    root = null, text = "invoice", onText = {}, typedRangeLabel = null, quick = com.clpstudio.filesmanager.data.QuickDate.ANY,
                    onQuick = {}, customLabel = null, types = emptySet(), onToggleType = {}, queryEmpty = false, results = items,
                    searching = false, onBack = {}, onOpen = {}, autoFocus = false, selection = selection,
                    selectionActions = { SelectionActionsRow(items, actions) {} },
                )
            }
        }
        compose.onNodeWithText("1 selected").assertExists()
        compose.onNodeWithContentDescription("Delete").assertExists()
    }

    // ---------- archives ----------

    private fun makeZip(name: String, entries: Map<String, String>): File {
        val f = File(ctx.cacheDir, name).apply { delete() }
        java.util.zip.ZipOutputStream(f.outputStream()).use { z ->
            for ((n, body) in entries) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(body.toByteArray()); z.closeEntry() }
        }
        return f
    }

    @Test fun deleteInsideZipRemovesEntriesAndFolders() {
        val zip = makeZip("t.zip", mapOf("a.txt" to "a", "docs/b.txt" to "b", "docs/sub/c.txt" to "c", "keep.txt" to "k"))
        assertEquals(null, ArchiveOps.deleteInsideBlocker(zip, ArchiveFormat.ZIP))
        assertEquals(3, ArchiveOps.removeEntries(zip, listOf("a.txt", "docs")))
        val left = java.util.zip.ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
        assertEquals(listOf("keep.txt"), left)
        assertTrue(ArchiveOps.deleteInsideBlocker(zip, ArchiveFormat.SEVEN_Z)!!.contains("isn't supported"))
    }

    @Test fun apkContentsAreReadOnly_noDeleteShown() {
        val apk = makeZip("app.apk", mapOf("AndroidManifest.xml" to "m", "classes.dex" to "d", "res/raw/x.txt" to "x"))
        val noExt = makeZip("shared_file", mapOf("AndroidManifest.xml" to "m", "classes.dex" to "d"))
        val noExtEntries = listOf(com.clpstudio.filesmanager.data.ArchiveEntry("AndroidManifest.xml", "AndroidManifest.xml", false, 1, 0, false))
        for (ext in listOf("apk", "apks", "xapk", "apkm", "aab")) assertTrue(ArchiveOps.isAppPackage(File("/x/a.$ext")))
        assertTrue(ArchiveOps.isAppPackage(noExt, noExtEntries))
        assertFalse(ArchiveOps.showDeleteInside(apk)); assertFalse(ArchiveOps.showDeleteInside(noExt, noExtEntries))
        assertTrue(ArchiveOps.showDeleteInside(File("/x/photos.zip")))
        assertTrue(ArchiveOps.deleteInsideBlocker(apk, ArchiveFormat.ZIP) != null)
        // In the browser: select a file inside the APK → Extract is there, Delete is not.
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { ZipViewerScreen(apk.absolutePath, onBack = {}) {} } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("classes.dex").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("classes.dex").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("1 selected").assertExists()
        compose.onNodeWithContentDescription("Extract").assertExists()
        compose.onAllNodesWithContentDescription("Delete").assertCountEquals(0)
        compose.onRoot().captureRoboImage("$shots/files_r10_apk_inside_selection.png")
    }

    @Test fun zipBrowserSelectionHasDelete() {
        val zip = makeZip("photos.zip", mapOf("a.txt" to "a", "b.txt" to "b"))
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { ZipViewerScreen(zip.absolutePath, onBack = {}) {} } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("a.txt").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("a.txt").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Delete").assertExists()
    }

    // ---------- video sound stops on close (item 48) ----------

    private fun stop(
        config: Boolean = false, finishing: Boolean = false, pipNow: Boolean = false, pipSinceResume: Boolean = false,
        playing: Boolean = true, backgroundPlay: Boolean = true, screenOff: Boolean = false,
    ) = VideoClose.onStop(config, finishing, pipNow, pipSinceResume, playing, hasVideo = true, backgroundPlay = backgroundPlay, screenOff = screenOff)

    @Test fun closePathsStopTheVideo() {
        assertEquals("back / X / swipe down", OnStop.CLOSE, stop(finishing = true))
        assertEquals("PiP closed, Android said 'stopped' first", OnStop.CLOSE, stop(pipNow = true, pipSinceResume = true))
        assertEquals("PiP closed, Android said 'left PiP' first (the bug)", OnStop.CLOSE, stop(pipNow = false, pipSinceResume = true))
        assertEquals("PiP closed with background play off", OnStop.CLOSE, stop(pipSinceResume = true, backgroundPlay = false))
        assertEquals("rotation", OnStop.NOTHING, stop(config = true, finishing = true))
        assertEquals("Home with PiP off, background play on", OnStop.HAND_OFF, stop())
        assertEquals("Home with background play off", OnStop.PAUSE, stop(backgroundPlay = false))
        assertEquals("paused video", OnStop.PAUSE, stop(playing = false))
        assertEquals("screen off while in PiP is not a close", OnStop.HAND_OFF, stop(pipNow = true, pipSinceResume = true, screenOff = true))
        // Leaving the screen inside the app or closing the app stops everything; Android tearing it down in the background doesn't.
        assertTrue(VideoClose.onDispose(changingConfig = false, finishing = false, appVisible = true))
        assertTrue(VideoClose.onDispose(changingConfig = false, finishing = true, appVisible = false))
        assertFalse(VideoClose.onDispose(changingConfig = true, finishing = false, appVisible = true))
        assertFalse(VideoClose.onDispose(changingConfig = false, finishing = false, appVisible = false))
    }

    @Test fun pipFlagFollowsTheWindow() {
        com.clpstudio.filesmanager.viewer.VideoPip.resetForTest()
        val act = org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().get()
        com.clpstudio.filesmanager.viewer.VideoPip.onModeChanged(act, true, act.lifecycle)
        assertTrue(com.clpstudio.filesmanager.viewer.VideoPip.pipSinceResume)
        com.clpstudio.filesmanager.viewer.VideoPip.onModeChanged(act, false, act.lifecycle) // "left PiP" before "stopped"
        assertTrue("still known as a PiP session until resumed", com.clpstudio.filesmanager.viewer.VideoPip.pipSinceResume)
        com.clpstudio.filesmanager.viewer.VideoPip.onResumed()
        assertFalse(com.clpstudio.filesmanager.viewer.VideoPip.pipSinceResume)
    }

    @Test fun closeAllStopsThePlayer() {
        val f = File(ctx.cacheDir, "clip.mp4").apply { writeBytes(ByteArray(64)) }
        compose.runOnIdle {}
        val v = PagerVideo(ctx)
        v.load(FileItem.of(f), AppSettings())
        assertEquals(1, v.player.mediaItemCount)
        v.closeAll(ctx)
        assertEquals(null, v.path)
        assertEquals(0, v.player.mediaItemCount)
        assertEquals(Player.STATE_IDLE, v.player.playbackState)
        assertFalse(v.player.isPlaying)
        v.release()
    }
}
