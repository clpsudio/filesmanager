package com.clpstudio.filesmanager

import androidx.media3.common.Player
import com.clpstudio.filesmanager.data.LoopMode
import com.clpstudio.filesmanager.data.LoopPolicy
import com.clpstudio.filesmanager.data.PlaylistPlay
import com.clpstudio.filesmanager.playback.AudioLoop
import com.clpstudio.filesmanager.viewer.SlideshowOptions
import com.clpstudio.filesmanager.viewer.SlideshowPlan
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.ui.ViewerSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item 37: playlists ignore the loop settings, play one by one and start again from the first. */
class PlaylistPlayTest {
    @Test fun nextItemWrapsToFirst() {
        assertEquals(1, PlaylistPlay.next(0, 3))   // first -> next
        assertEquals(2, PlaylistPlay.next(1, 3))   // middle -> next
        assertEquals(0, PlaylistPlay.next(2, 3))   // last -> first
        assertEquals(0, PlaylistPlay.next(0, 1))   // a single item plays again
        assertEquals(0, PlaylistPlay.next(0, 0))
    }

    @Test fun loopSettingIsIgnoredInPlaylistMode() {
        for (mode in LoopMode.entries) {
            val m = PlaylistPlay.loopMode(playlist = true, mode = mode)
            assertEquals(LoopMode.OFF, m)
            // Never "repeat this item": the player moves on (and REPEAT_MODE_ALL wraps after the last).
            assertEquals(LoopPolicy.After.NEXT, LoopPolicy.afterPlay(m, 3, 1, hasNext = true))
            assertEquals(Player.REPEAT_MODE_ALL, AudioLoop.repeatModeFor(m, 3, 0))
        }
    }

    @Test fun singleFileKeepsItsLoopSetting() {
        assertEquals(LoopMode.FOREVER, PlaylistPlay.loopMode(playlist = false, mode = LoopMode.FOREVER))
        assertEquals(Player.REPEAT_MODE_ONE, AudioLoop.repeatModeFor(PlaylistPlay.loopMode(false, LoopMode.FOREVER), 3, 0))
        assertEquals(LoopPolicy.After.REPLAY, LoopPolicy.afterPlay(PlaylistPlay.loopMode(false, LoopMode.TIMES), 3, 1, hasNext = true))
    }

    @Test fun playNVideosMarksThePagerAsPlaylistAndAnyOtherOpenClearsIt() {
        ViewerSession.files = listOf("/a.mp4", "/b.mp4")
        ViewerSession.playlist = true
        assertTrue(ViewerSession.playlist)
        ViewerSession.files = listOf("/c.mp4") // a video opened normally from a folder
        assertFalse(ViewerSession.playlist)
    }

    @Test fun slideshowPlaylistStartsAgainAfterTheLast() {
        val items = listOf("a.jpg", "b.mp4", "c.mp3").map { FileItem("/x/$it", it, false, 1L, 0L, if (it.endsWith("jpg")) FileKind.IMAGE else if (it.endsWith("mp4")) FileKind.VIDEO else FileKind.AUDIO) }
        val plan = SlideshowPlan(items, SlideshowOptions())
        assertTrue(plan.next()); assertTrue(plan.next())
        assertTrue(plan.next()) // after the last: the first again
        assertEquals("a.jpg", plan.current!!.name)
    }
}
