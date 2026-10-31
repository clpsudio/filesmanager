package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.FileEvents
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.KeepLastGoodLoader
import com.clpstudio.filesmanager.data.MediaStoreSync
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The deletion bus: removed files leave every cached list at once, and come back only when put back. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileEventsTest {
    private fun item(path: String, modified: Long = 1_000L) = FileItem(path, path.substringAfterLast('/'), false, 10, modified)

    @Before fun setUp() = FileEvents.reset()
    @After fun tearDown() = FileEvents.reset()

    @Test fun deletedFileLeavesTheList() {
        val list = listOf(item("/s/DCIM/a.jpg"), item("/s/DCIM/b.jpg"), item("/s/Download/c.pdf"))
        FileEvents.removed(listOf("/s/DCIM/b.jpg"), now = 5_000L)
        assertEquals(listOf("/s/DCIM/a.jpg", "/s/Download/c.pdf"), FileEvents.filter(list).map { it.path })
    }

    @Test fun deletedFolderHidesEverythingInside() {
        val list = listOf(item("/s/Trip/1.jpg"), item("/s/Trip/sub/2.mp4"), item("/s/Trips/3.jpg"))
        FileEvents.removed(listOf("/s/Trip/"), now = 5_000L)
        assertEquals(listOf("/s/Trips/3.jpg"), FileEvents.filter(list).map { it.path })
    }

    @Test fun unchangedListIsTheSameInstance() {
        val list = listOf(item("/s/a.jpg"))
        assertSame(list, FileEvents.filter(list))
        FileEvents.removed(listOf("/s/other.jpg"))
        assertSame(list, FileEvents.filter(list))
    }

    @Test fun renameAndMoveDropTheOldPathOnly() {
        FileEvents.moved("/s/a.jpg", "/s/b.jpg")
        assertTrue(FileEvents.isGone("/s/a.jpg"))
        assertFalse(FileEvents.isGone("/s/b.jpg"))
        FileEvents.moved(listOf("/s/x/1.png" to "/s/y/1.png"))
        assertTrue(FileEvents.isGone("/s/x/1.png"))
        assertFalse(FileEvents.isGone("/s/y/1.png"))
    }

    @Test fun restoreOrMoveBackShowsItAgain() {
        FileEvents.removed(listOf("/s/a.jpg"))
        assertTrue(FileEvents.isGone("/s/a.jpg", 1_000L))
        FileEvents.added(listOf("/s/a.jpg"))
        assertFalse(FileEvents.isGone("/s/a.jpg", 1_000L))
        // Restoring a folder also clears removals recorded inside it.
        FileEvents.removed(listOf("/s/F/x.jpg"))
        FileEvents.added(listOf("/s/F"))
        assertFalse(FileEvents.isGone("/s/F/x.jpg"))
    }

    @Test fun aNewerFileAtTheSamePathIsShown() {
        FileEvents.removed(listOf("/s/Download/report.pdf"), now = 10_000L)
        assertTrue(FileEvents.isGone("/s/Download/report.pdf", modified = 9_000L)) // the deleted one
        assertFalse(FileEvents.isGone("/s/Download/report.pdf", modified = 60_000L)) // downloaded again later
    }

    @Test fun fileUriPathsAndTrailingSlashesMatch() {
        FileEvents.removed(listOf("file:///s/a.jpg"))
        assertTrue(FileEvents.isGone("/s/a.jpg"))
        assertEquals(emptyList<String>(), FileEvents.filterPaths(listOf("/s/a.jpg")))
    }

    @Test fun listenersAndVersionHearEveryChange() {
        val heard = ArrayList<FileEvents.Change>()
        FileEvents.addListener { heard += it }
        val v0 = FileEvents.version.value
        FileEvents.removed(listOf("/s/a.jpg"))
        FileEvents.moved("/s/b.jpg", "/s/c.jpg")
        FileEvents.removed(emptyList()) // nothing: no event
        assertEquals(2, heard.size)
        assertEquals(listOf("/s/b.jpg"), heard[1].removed)
        assertEquals(listOf("/s/c.jpg"), heard[1].added)
        assertEquals(v0 + 2, FileEvents.version.value)
    }

    @Test fun keepLastGoodLoaderDropsDeletedFilesWithoutAReload() = runTest {
        var loads = 0
        val loader = KeepLastGoodLoader(this) { loads++; FileEvents.filter(listOf(item("/s/a.jpg"), item("/s/b.jpg"))) }
        // The way CategoryRepository wires every category loader to the bus.
        FileEvents.addListener { loader.prune(FileEvents::filter) }
        loader.refresh(); advanceUntilIdle()
        assertEquals(2, loader.state.value.value!!.size)
        FileEvents.removed(listOf("/s/a.jpg"))
        assertEquals(listOf("/s/b.jpg"), loader.state.value.value!!.map { it.path }) // at once
        assertEquals(1, loads)
        // A reload whose source still has the stale row (MediaStore not caught up) keeps it out.
        loader.refresh(); advanceUntilIdle()
        assertEquals(listOf("/s/b.jpg"), loader.state.value.value!!.map { it.path })
    }

    @Test fun pruneKeepsStateWhenNothingChanged() = runTest {
        val loader = KeepLastGoodLoader(this) { listOf(item("/s/a.jpg")) }
        loader.refresh(); advanceUntilIdle()
        val before = loader.state.value
        loader.prune(FileEvents::filter)
        assertSame(before, loader.state.value)
    }

    @Test fun mediaStoreLikePatternEscapesWildcards() {
        assertEquals("/s/my\\_photos\\%/%", MediaStoreSync.likePrefix("/s/my_photos%"))
        assertEquals("/s/DCIM/%", MediaStoreSync.likePrefix("/s/DCIM"))
    }
}
