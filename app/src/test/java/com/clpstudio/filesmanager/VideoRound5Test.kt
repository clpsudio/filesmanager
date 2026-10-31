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

    @Test fun playNTimesThenNextVideo() {
        // "Play 3 times": plays 1 and 2 end → again; play 3 ends → the next video, or stop at the last one.
        assertEquals(LoopPolicy.After.REPLAY, LoopPolicy.afterPlay(LoopMode.TIMES, 3, 1, hasNext = true))
        assertEquals(LoopPolicy.After.REPLAY, LoopPolicy.afterPlay(LoopMode.TIMES, 3, 2, hasNext = true))
        assertEquals(LoopPolicy.After.NEXT, LoopPolicy.afterPlay(LoopMode.TIMES, 3, 3, hasNext = true))
        assertEquals(LoopPolicy.After.STOP, LoopPolicy.afterPlay(LoopMode.TIMES, 3, 3, hasNext = false))
        // A player that repeats by itself: repeat while another play is due.
        assertTrue(LoopPolicy.repeatsAtStart(LoopMode.TIMES, 3))
        assertTrue(LoopPolicy.keepRepeating(LoopMode.TIMES, 3, 1))
        assertFalse(LoopPolicy.keepRepeating(LoopMode.TIMES, 3, 2))
        // Clamped to 2..10; the hint counts the current play.
        assertEquals(2, LoopPolicy.clampTimes(0))
        assertEquals(10, LoopPolicy.clampTimes(50))
        assertEquals("1/3", LoopPolicy.hint(LoopMode.TIMES, 3, 0))
        assertEquals("3/3", LoopPolicy.hint(LoopMode.TIMES, 3, 5))
        assertEquals(null, LoopPolicy.hint(LoopMode.FOREVER, 3, 1))
    }

    @Test fun foreverNeverAdvancesOffAlwaysDoes() {
        assertEquals(LoopPolicy.After.REPLAY, LoopPolicy.afterPlay(LoopMode.FOREVER, 3, 99, hasNext = true))
        assertEquals(LoopPolicy.After.NEXT, LoopPolicy.afterPlay(LoopMode.OFF, 3, 1, hasNext = true))
        assertEquals(LoopPolicy.After.STOP, LoopPolicy.afterPlay(LoopMode.OFF, 3, 1, hasNext = false))
    }

    @Test fun loopButtonTogglesAndRemembersTheLastMode() {
        assertEquals(LoopMode.OFF, LoopPolicy.toggled(LoopMode.FOREVER, LoopMode.FOREVER))
        assertEquals(LoopMode.FOREVER, LoopPolicy.toggled(LoopMode.OFF, LoopMode.OFF))
        assertEquals(LoopMode.TIMES, LoopPolicy.toggled(LoopMode.OFF, LoopMode.TIMES))
        assertEquals("Forever", LoopPolicy.label(LoopMode.FOREVER, 3))
        assertEquals("Play 3 times", LoopPolicy.label(LoopMode.TIMES, 3))
        assertEquals("Play 2 times", LoopPolicy.label(LoopMode.TIMES, 1))
    }
}
