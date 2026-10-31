package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.AppSettings
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.FileTypes
import com.clpstudio.filesmanager.data.LoopMode
import com.clpstudio.filesmanager.data.LoopPolicy
import com.clpstudio.filesmanager.viewer.PagerPage
import com.clpstudio.filesmanager.viewer.pagerPageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRound5Test {
    @Test fun videoPagesAreThePlayerNeverACard() {
        for (mime in listOf("video/mp4", "video/x-matroska", "video/webm", "video/3gpp")) {
            val kind = FileTypes.kindOfMime(mime)
            assertEquals(mime, FileKind.VIDEO, kind)
            assertEquals(mime, PagerPage.VIDEO, pagerPageType(kind!!))
        }
        for (name in listOf("VID_20261031.mp4", "clip.MKV", "a.webm", "b.3gp", "c.mov")) {
            assertEquals(name, PagerPage.VIDEO, pagerPageType(FileTypes.kindOf(name)))
        }
        assertEquals(PagerPage.PHOTO, pagerPageType(FileTypes.kindOf("IMG_1.jpg")))
        assertEquals(PagerPage.CARD, pagerPageType(FileTypes.kindOf("song.mp3")))
    }

    @Test fun loopIsForeverByDefault() {
        val s = AppSettings()
        assertEquals(LoopMode.FOREVER, s.loopMode)
        assertTrue(LoopPolicy.repeatsAtStart(s.loopMode, s.loopTimes))
        // No limit: still repeating after any number of plays.
        assertTrue(LoopPolicy.keepRepeating(LoopMode.FOREVER, 3, 1_000_000))
        assertEquals(LoopMode.FOREVER, LoopMode.parse(null))
        assertEquals(LoopMode.FOREVER, LoopMode.parse("bogus"))
    }

    @Test fun loopOffNeverRepeats() {
        assertFalse(LoopPolicy.repeatsAtStart(LoopMode.OFF, 3))
        assertFalse(LoopPolicy.keepRepeating(LoopMode.OFF, 3, 0))
    }

    @Test fun loopNTimesPlaysNExtraTimesThenStops() {
        // 2 more times: first play, repeat #1 (keep), repeat #2 (stop after this one).
        assertTrue(LoopPolicy.repeatsAtStart(LoopMode.TIMES, 2))
        assertTrue(LoopPolicy.keepRepeating(LoopMode.TIMES, 2, 1))
        assertFalse(LoopPolicy.keepRepeating(LoopMode.TIMES, 2, 2))
        assertFalse(LoopPolicy.keepRepeating(LoopMode.TIMES, 1, 1))
        // Clamped to 1..10.
        assertEquals(1, LoopPolicy.clampTimes(0))
        assertEquals(10, LoopPolicy.clampTimes(50))
        assertTrue(LoopPolicy.keepRepeating(LoopMode.TIMES, 50, 9))
        assertFalse(LoopPolicy.keepRepeating(LoopMode.TIMES, 50, 10))
    }

    @Test fun loopButtonTogglesAndRemembersTheLastMode() {
        assertEquals(LoopMode.OFF, LoopPolicy.toggled(LoopMode.FOREVER, LoopMode.FOREVER))
        assertEquals(LoopMode.FOREVER, LoopPolicy.toggled(LoopMode.OFF, LoopMode.OFF))
        assertEquals(LoopMode.TIMES, LoopPolicy.toggled(LoopMode.OFF, LoopMode.TIMES))
        assertEquals("Forever", LoopPolicy.label(LoopMode.FOREVER, 3))
        assertEquals("3 more times", LoopPolicy.label(LoopMode.TIMES, 3))
        assertEquals("1 more time", LoopPolicy.label(LoopMode.TIMES, 1))
    }
}
