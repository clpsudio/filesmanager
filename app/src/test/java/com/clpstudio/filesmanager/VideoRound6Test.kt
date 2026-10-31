package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.ui.VideoOpen
import com.clpstudio.filesmanager.viewer.VideoSpeed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Round 5, items 19-21: one video UI (pages a video opens with), the shared speed, the next video after a loop. */
class VideoRound6Test {
    @After fun reset() = VideoSpeed.set(1f)

    @Test fun speedIsSharedAndKept() {
        assertEquals(1f, VideoSpeed.value)
        VideoSpeed.set(2f)
        assertEquals(2f, VideoSpeed.value) // the next page / the full player read the same value
        VideoSpeed.set(0.5f)
        assertEquals(0.5f, VideoSpeed.value)
        VideoSpeed.set(7f) // not on the menu
        assertEquals(1f, VideoSpeed.value)
        for (s in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)) { VideoSpeed.set(s); assertEquals(s, VideoSpeed.value) }
    }

    @Test fun videoOpensInThePagerWithItsList() {
        val media = listOf("/a/1.jpg" to false, "/a/2.mp4" to true, "/a/3.mp4" to true)
        assertEquals(listOf("/a/1.jpg", "/a/2.mp4", "/a/3.mp4"), VideoOpen.pages("/a/2.mp4", media))
        assertEquals(listOf("/x/solo.mp4"), VideoOpen.pages("/x/solo.mp4", media)) // not in the list: alone
        assertEquals(listOf("/x/solo.mp4"), VideoOpen.pages("/x/solo.mp4", emptyList()))
    }

    @Test fun nextVideoSkipsPhotosAndStopsAtTheEnd() {
        val kinds = listOf(true, false, false, true, false)
        assertEquals(3, VideoOpen.nextVideo(kinds, 0))
        assertNull(VideoOpen.nextVideo(kinds, 3))
        assertNull(VideoOpen.nextVideo(kinds, 4))
        assertEquals(1, VideoOpen.nextVideo(listOf(true, true), 0))
    }
}
