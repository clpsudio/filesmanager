package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.viewer.Axis
import com.clpstudio.filesmanager.viewer.HorizontalAction
import com.clpstudio.filesmanager.viewer.MixedPlaylist
import com.clpstudio.filesmanager.viewer.SPEEDS
import com.clpstudio.filesmanager.viewer.SwipeAction
import com.clpstudio.filesmanager.viewer.VideoGestureMath
import com.clpstudio.filesmanager.viewer.ViewerGestureMath
import com.clpstudio.filesmanager.viewer.ViewerKind
import com.clpstudio.filesmanager.viewer.Zone
import com.clpstudio.filesmanager.viewer.speedLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Video round (Oct 31, 12:33): side swipes scrub, middle swipes page, vertical stays brightness / volume / info. */
class VideoGestureMathTest {
    private val w = 1080f
    private val lock = 16f * 2.75f

    private fun gesture(x: Float, dx: Float, dy: Float): String {
        val zone = ViewerGestureMath.zoneOf(x, w)
        return when (ViewerGestureMath.lockDirection(dx, dy, lock)) {
            Axis.NONE -> "none"
            Axis.HORIZONTAL -> VideoGestureMath.horizontalAction(zone).name
            Axis.VERTICAL -> ViewerGestureMath.actionFor(ViewerKind.VIDEO, zone, dy).name
        }
    }

    @Test fun sidewaysOnTheSidesScrubs() {
        assertEquals("SCRUB", gesture(100f, 200f, 10f))
        assertEquals("SCRUB", gesture(1000f, -200f, -20f))
        assertEquals(HorizontalAction.SCRUB, VideoGestureMath.horizontalAction(Zone.LEFT))
        assertEquals(HorizontalAction.SCRUB, VideoGestureMath.horizontalAction(Zone.RIGHT))
    }

    @Test fun sidewaysInTheMiddlePages() {
        assertEquals("PAGE", gesture(540f, 200f, 0f))
        assertEquals("PAGE", gesture(540f, -200f, 30f))
        assertEquals(HorizontalAction.PAGE, VideoGestureMath.horizontalAction(Zone.MIDDLE))
    }

    @Test fun verticalKeepsBrightnessVolumeInfoClose() {
        assertEquals(SwipeAction.BRIGHTNESS.name, gesture(100f, 10f, -200f))
        assertEquals(SwipeAction.BRIGHTNESS.name, gesture(100f, 10f, 200f))
        assertEquals(SwipeAction.VOLUME.name, gesture(1000f, -5f, -200f))
        assertEquals(SwipeAction.INFO.name, gesture(540f, 0f, -200f))
        assertEquals(SwipeAction.DISMISS.name, gesture(540f, 0f, 200f))
    }

    @Test fun smallMovesWaitForTheLock() {
        assertEquals("none", gesture(100f, 20f, 20f))
        assertEquals("none", gesture(540f, -30f, 0f))
    }

    @Test fun scrubMapping() {
        // Short video: one full width = the whole video.
        assertEquals(30_000L, VideoGestureMath.scrubTarget(0L, w, w, 30_000L))
        assertEquals(15_000L, VideoGestureMath.scrubTarget(0L, w / 2, w, 30_000L))
        // Long video: one full width = 2 minutes.
        assertEquals(60_000L + 120_000L, VideoGestureMath.scrubTarget(60_000L, w, w, 3_600_000L))
        assertEquals(0L, VideoGestureMath.scrubTarget(10_000L, -w, w, 3_600_000L)) // clamped at the start
        assertEquals(30_000L, VideoGestureMath.scrubTarget(29_000L, w, w, 30_000L)) // clamped at the end
        assertEquals(5_000L, VideoGestureMath.scrubTarget(5_000L, 100f, 0f, 30_000L)) // no width yet
    }

    @Test fun pageDecision() {
        val d = 2.75f
        assertEquals(1, VideoGestureMath.pageDecision(-400f, w, 0f, d, hasPrev = true, hasNext = true))
        assertEquals(-1, VideoGestureMath.pageDecision(400f, w, 0f, d, hasPrev = true, hasNext = true))
        assertEquals(0, VideoGestureMath.pageDecision(-100f, w, 0f, d, hasPrev = true, hasNext = true)) // short and slow
        assertEquals(1, VideoGestureMath.pageDecision(-100f, w, -4000f, d, hasPrev = true, hasNext = true)) // fling
        assertEquals(0, VideoGestureMath.pageDecision(-100f, w, 4000f, d, hasPrev = true, hasNext = true)) // fling back
        assertEquals(0, VideoGestureMath.pageDecision(-400f, w, 0f, d, hasPrev = true, hasNext = false)) // last video
        assertEquals(0, VideoGestureMath.pageDecision(400f, w, 0f, d, hasPrev = false, hasNext = true)) // first video
        assertEquals(-50f, VideoGestureMath.pageOffset(-200f, hasPrev = true, hasNext = false), 0.01f) // rubber band
        assertEquals(-200f, VideoGestureMath.pageOffset(-200f, hasPrev = false, hasNext = true), 0.01f)
    }

    @Test fun timeLabels() {
        assertEquals("0:00", VideoGestureMath.formatTime(0))
        assertEquals("1:23", VideoGestureMath.formatTime(83_400))
        assertEquals("1:02:03", VideoGestureMath.formatTime(3_723_000))
        assertEquals("+0:12", VideoGestureMath.formatDelta(12_000))
        assertEquals("-1:05", VideoGestureMath.formatDelta(-65_000))
    }

    @Test fun speedList() {
        assertEquals(listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f), SPEEDS)
        assertEquals(listOf("0.25x", "0.5x", "0.75x", "1x", "1.25x", "1.5x", "1.75x", "2x", "3x"), SPEEDS.map { speedLabel(it) })
        assertEquals(1.25f, VideoGestureMath.nextSpeed(1f), 0f)
        assertEquals(0.25f, VideoGestureMath.nextSpeed(3f), 0f) // wraps
        assertEquals(1f, VideoGestureMath.nextSpeed(1.1f), 0f) // unknown -> normal
    }

    @Test fun pipAspectIsClamped() {
        assertEquals(1920 to 1080, VideoGestureMath.pipAspect(1920, 1080))
        assertEquals(239 to 100, VideoGestureMath.pipAspect(3000, 1000))
        assertEquals(100 to 239, VideoGestureMath.pipAspect(500, 2000))
        assertEquals(16 to 9, VideoGestureMath.pipAspect(0, 0))
    }

    @Test fun mixedPlaylistMapsPhotosAndVideos() {
        // photo, video, photo, video, video
        val p = MixedPlaylist(listOf(false, true, false, true, true))
        assertEquals(3, p.videoCount)
        assertEquals(-1, p.mediaIndexOf(0))
        assertEquals(0, p.mediaIndexOf(1))
        assertEquals(1, p.mediaIndexOf(3))
        assertEquals(2, p.mediaIndexOf(4))
        assertEquals(1, p.entryOfMedia(0))
        assertEquals(4, p.entryOfMedia(2))
        assertEquals(-1, p.entryOfMedia(5))
        assertTrue((0 until p.videoCount).all { p.mediaIndexOf(p.entryOfMedia(it)) == it })
    }
}
