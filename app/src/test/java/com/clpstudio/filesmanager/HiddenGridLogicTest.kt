package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.Category
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.HiddenFilter
import com.clpstudio.filesmanager.data.HiddenMedia
import com.clpstudio.filesmanager.data.SearchHidden
import com.clpstudio.filesmanager.data.SortBy
import com.clpstudio.filesmanager.data.sortItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** "Show hidden files" in grids: the hidden-media walk and the merge with MediaStore rows. */
class HiddenGridLogicTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(root: File, rel: String, t: Long = 1_000L): File =
        File(root, rel).apply { parentFile!!.mkdirs(); writeText(rel); setLastModified(t) }

    private fun storage(): Pair<File, File> {
        val root = tmp.newFolder("emulated0")
        val vault = tmp.newFolder("app-private", "vault")
        file(root, "DCIM/Camera/IMG_1.jpg")                       // normal: MediaStore has it
        file(root, "DCIM/.hidden/IMG_2.jpg", 5_000)               // our Hide folder
        file(root, "Pictures/.secret_selfie.png", 4_000)          // dot-file
        file(root, ".Private/trip/VID_3.mp4", 3_000)              // inside a dot-folder (nested)
        file(root, "WhatsApp/Media/Statuses/.nomedia")
        file(root, "WhatsApp/Media/Statuses/status1.jpg", 6_000)  // .nomedia folder
        file(root, "WhatsApp/Media/Statuses/old/clip.mp4", 2_000) // under a .nomedia folder
        file(root, "Music/.ringtones/tone.mp3", 7_000)            // hidden audio
        file(root, ".Private/notes.txt")                          // hidden but not media
        file(root, "DCIM/.thumbnails/123.jpg")                    // thumbnail cache: skipped
        file(root, "DCIM/Camera/.trashed-1790000000-IMG_9.jpg")   // MediaStore trash: skipped
        file(root, "Android/data/com.app/files/.cache/a.jpg")     // unreadable on 11+: skipped
        file(vault, ".x.jpg")                                     // vault: never, even hidden-looking
        return root to vault
    }

    private fun names(items: List<FileItem>) = items.map { it.name }.toSet()

    @Test fun walk_findsDotFilesDotFoldersAndNoMedia() {
        val (root, vault) = storage()
        val found = HiddenMedia.walk(listOf(root, vault.parentFile!!), privateRoots = listOf(vault.absolutePath))
        assertEquals(setOf("IMG_2.jpg", ".secret_selfie.png", "VID_3.mp4", "status1.jpg", "clip.mp4", "tone.mp3"), names(found))
    }

    @Test fun walk_respectsCaps() {
        val (root, _) = storage()
        assertEquals(2, HiddenMedia.walk(listOf(root), emptyList(), maxResults = 2).size)
        // Depth 1 = only folders directly under the root (e.g. .Private but not .Private/trip).
        assertFalse("VID_3.mp4" in names(HiddenMedia.walk(listOf(root), emptyList(), maxDepth = 1)))
    }

    @Test fun hiddenPath_rules() {
        val r = "/storage/emulated/0"
        val nomedia = setOf("$r/WhatsApp/Media/Statuses")
        val has = { d: String -> d in nomedia }
        assertTrue(HiddenMedia.isHiddenPath("$r/DCIM/.hidden/a.jpg", listOf(r), has))
        assertTrue(HiddenMedia.isHiddenPath("$r/Pictures/.a.jpg", listOf(r), has))
        assertTrue(HiddenMedia.isHiddenPath("$r/WhatsApp/Media/Statuses/x/b.jpg", listOf(r), has))
        assertFalse(HiddenMedia.isHiddenPath("$r/DCIM/Camera/a.jpg", listOf(r), has))
        assertFalse(HiddenMedia.isHiddenPath("/elsewhere/.a.jpg", listOf(r), has))
    }

    private val r = "/storage/emulated/0"
    private val vault = "/data/user/0/com.clpstudio.filesmanager/no_backup/vault"
    private val bin = "$r/Android/data/com.clpstudio.filesmanager/files/bin"
    private val indexed = listOf(
        FileItem("$r/DCIM/Camera/IMG_1.jpg", "IMG_1.jpg", false, 10, 1_000),
        FileItem("$r/DCIM/Camera/IMG_0.jpg", "IMG_0.jpg", false, 10, 500),
    )
    private val hidden = listOf(
        FileItem("$r/DCIM/.hidden/IMG_2.jpg", "IMG_2.jpg", false, 10, 5_000),
        FileItem("$r/.Private/VID_3.mp4", "VID_3.mp4", false, 10, 3_000),
        FileItem("$r/Music/.r/tone.mp3", "tone.mp3", false, 10, 7_000),
        FileItem("$r/DCIM/Camera/IMG_1.jpg", "IMG_1.jpg", false, 10, 1_000), // also in the index
        FileItem("$vault/secret.jpg", "secret.jpg", false, 10, 9_000),
        FileItem("$bin/deleted.jpg", "deleted.jpg", false, 10, 9_000),
    )

    private fun filter(on: Boolean) = HiddenFilter(on, privateRoots = listOf(vault, bin), storageRoots = listOf(r))

    @Test fun on_mergesHiddenMedia_dedupedAndNewestFirst() {
        val photos = HiddenMedia.merge(indexed, hidden, filter(true)) { HiddenMedia.inCategory(it, Category.PHOTOS) }
        assertEquals(listOf("IMG_2.jpg", "IMG_1.jpg", "IMG_0.jpg"), photos.map { it.name })
        val media = HiddenMedia.merge(indexed, hidden, filter(true)) { HiddenMedia.inCategory(it, Category.MEDIA) }
        assertEquals(listOf("IMG_2.jpg", "VID_3.mp4", "IMG_1.jpg", "IMG_0.jpg"), media.map { it.name })
        val audio = HiddenMedia.merge(emptyList(), hidden, filter(true)) { HiddenMedia.inCategory(it, Category.AUDIO) }
        assertEquals(listOf("tone.mp3"), audio.map { it.name })
        assertEquals(media.size, media.map { it.path }.toSet().size)
    }

    @Test fun vaultAndBin_neverAppear_evenWithHiddenOn() {
        val all = HiddenMedia.merge(indexed + FileItem("$bin/b.jpg", "b.jpg", false, 1, 1), hidden, filter(true))
        assertTrue(all.none { it.path.startsWith(vault) || it.path.startsWith(bin) })
        val sdOff = HiddenFilter(true, privateRoots = listOf(vault), storageRoots = listOf(r, "/storage/1A2B-3C4D"), hiddenRoots = listOf("/storage/1A2B-3C4D"))
        val card = listOf(FileItem("/storage/1A2B-3C4D/.x/a.jpg", "a.jpg", false, 1, 1))
        assertTrue(HiddenMedia.merge(emptyList(), card, sdOff).isEmpty())
    }

    @Test fun off_hidesHiddenEverywhere_gridsToo() {
        val withHiddenRows = indexed + FileItem("$r/Pictures/.x/leak.jpg", "leak.jpg", false, 1, 9_999)
        val off = HiddenMedia.merge(withHiddenRows, hidden, filter(false))
        assertEquals(listOf("IMG_1.jpg", "IMG_0.jpg"), off.map { it.name })
    }

    @Test fun off_noMediaRowsDropped_onShown() {
        val root = tmp.newFolder("s")
        file(root, "WA/Statuses/.nomedia")
        val st = file(root, "WA/Statuses/s.jpg")
        val rows = listOf(FileItem(st.absolutePath, "s.jpg", false, 1, 1))
        assertTrue(HiddenMedia.merge(rows, emptyList(), HiddenFilter(false, storageRoots = listOf(root.absolutePath))).isEmpty())
        assertEquals(1, HiddenMedia.merge(rows, emptyList(), HiddenFilter(true, storageRoots = listOf(root.absolutePath))).size)
    }

    @Test fun screenSort_appliesToMergedList() {
        val merged = HiddenMedia.merge(indexed, hidden, filter(true)) { HiddenMedia.inCategory(it, Category.MEDIA) }
        assertEquals(listOf("IMG_0.jpg", "IMG_1.jpg", "IMG_2.jpg", "VID_3.mp4"), sortItems(merged, SortBy.NAME, true).map { it.name })
        assertEquals(merged.map { it.name }, sortItems(merged, SortBy.DATE, false).map { it.name })
    }

    @Test fun recent_onlyLast30Days_andSearchSettingUntouched() {
        val cutoff = 4_000L
        val recent = HiddenMedia.merge(indexed, hidden, filter(true)) { HiddenMedia.inCategory(it, Category.RECENT) && it.modified > cutoff }
        assertEquals(listOf("tone.mp3", "IMG_2.jpg", "IMG_1.jpg", "IMG_0.jpg"), recent.map { it.name })
        // Item 35 stays separate: hidden in search needs both switches.
        assertFalse(SearchHidden.include(showHidden = true, hiddenInSearch = false))
        assertTrue(SearchHidden.include(showHidden = true, hiddenInSearch = true))
    }
}
