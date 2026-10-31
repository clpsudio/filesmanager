package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.ui.VideoOpen
import com.clpstudio.filesmanager.data.LoopMode
import com.clpstudio.filesmanager.playback.AudioLoop
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

    @Test fun nextVideoSkipsPhotosAndWrapsToTheFirst() {
        val kinds = listOf(true, false, false, true, false)
        assertEquals(3, VideoOpen.nextVideo(kinds, 0))
        assertEquals(0, VideoOpen.nextVideo(kinds, 3)) // after the last video: the first again (item 24)
        assertEquals(0, VideoOpen.nextVideo(kinds, 4))
        assertEquals(1, VideoOpen.nextVideo(listOf(true, true), 0))
        assertEquals(0, VideoOpen.nextVideo(listOf(true, true), 1))
        assertEquals(2, VideoOpen.nextVideo(listOf(false, false, true), 2)) // the only video: itself
        assertNull(VideoOpen.nextVideo(listOf(false, false), 0))
        assertNull(VideoOpen.nextVideo(emptyList(), 0))
    }

    @Test fun audioLoopPerTrackListAlwaysWraps() {
        val one = androidx.media3.common.Player.REPEAT_MODE_ONE
        val all = androidx.media3.common.Player.REPEAT_MODE_ALL
        // Off: every track once, the list wraps.
        assertEquals(all, AudioLoop.repeatModeFor(LoopMode.OFF, 3, 0))
        // Forever: the same track.
        assertEquals(one, AudioLoop.repeatModeFor(LoopMode.FOREVER, 3, 0))
        assertEquals(one, AudioLoop.repeatModeFor(LoopMode.FOREVER, 3, 50))
        // Play 3 times: plays 1 and 2 repeat, after play 3 the next track.
        assertEquals(one, AudioLoop.repeatModeFor(LoopMode.TIMES, 3, 0))
        assertEquals(one, AudioLoop.repeatModeFor(LoopMode.TIMES, 3, 1))
        assertEquals(all, AudioLoop.repeatModeFor(LoopMode.TIMES, 3, 2))
        // Play 2 times.
        assertEquals(one, AudioLoop.repeatModeFor(LoopMode.TIMES, 2, 0))
        assertEquals(all, AudioLoop.repeatModeFor(LoopMode.TIMES, 2, 1))
    }

    @Test fun audioLoopButtonTogglesBackToTheLastMode() {
        AudioLoop.lastOn = LoopMode.TIMES
        assertEquals(LoopMode.OFF, AudioLoop.toggledMode(LoopMode.TIMES))
        assertEquals(LoopMode.TIMES, AudioLoop.toggledMode(LoopMode.OFF))
        AudioLoop.lastOn = LoopMode.FOREVER
        assertEquals(LoopMode.FOREVER, AudioLoop.toggledMode(LoopMode.OFF))
    }

    private fun audio(name: String) = com.clpstudio.filesmanager.data.FileItem.of(java.io.File("/m/" + name))

    @Test fun audioQueueIsTheWholeListFromTheTappedSong() {
        val list = listOf(audio("a.mp3"), audio("cover.jpg"), audio("b.m4a"), audio("c.ogg"))
        val (q, i) = com.clpstudio.filesmanager.playback.AudioQueue.build(list[2], list)
        assertEquals(listOf("/m/a.mp3", "/m/b.m4a", "/m/c.ogg"), q.map { it.path })
        assertEquals(1, i)
        val (solo, j) = com.clpstudio.filesmanager.playback.AudioQueue.build(audio("x.mp3"), emptyList())
        assertEquals(listOf("/m/x.mp3"), solo.map { it.path })
        assertEquals(0, j)
    }

    /** Plays a 3-song list the way the player does (repeat one / all from [AudioLoop.repeatModeFor]) and checks the order. */
    private fun simulate(mode: LoopMode, times: Int, ends: Int): List<Int> {
        val order = mutableListOf(0)
        var cur = 0
        var plays = 0
        repeat(ends) {
            val rm = AudioLoop.repeatModeFor(mode, times, plays)
            val next = if (rm == androidx.media3.common.Player.REPEAT_MODE_ONE) cur else (cur + 1) % 3
            // The pure "what's next" agrees with what the player does with that repeat mode.
            assertEquals(next, com.clpstudio.filesmanager.playback.AudioQueue.nextAfterEnd(cur, 3, mode, times, plays + 1))
            plays = if (rm == androidx.media3.common.Player.REPEAT_MODE_ONE) plays + 1 else 0
            cur = next
            order += cur
        }
        return order
    }

    @Test fun songEndsThenTheNextPlaysAndTheListWraps() {
        assertEquals(listOf(0, 1, 2, 0, 1), simulate(LoopMode.OFF, 3, 4)) // one by one, then from the first
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 0), simulate(LoopMode.TIMES, 2, 6)) // each twice
        assertEquals(listOf(0, 0, 0, 0), simulate(LoopMode.FOREVER, 3, 3))
    }

    @Test fun audioAndVideoLoopSettingsAreSeparate() {
        val s = com.clpstudio.filesmanager.data.AppSettings()
        assertEquals(LoopMode.FOREVER, s.loopMode) // videos: loop forever by default
        assertEquals(LoopMode.OFF, s.audioLoopMode) // songs: one by one by default
        val changed = s.copy(audioLoopMode = LoopMode.TIMES, audioLoopTimes = 4)
        assertEquals(LoopMode.FOREVER, changed.loopMode)
        assertEquals(3, changed.loopTimes)
        val video = s.copy(loopMode = LoopMode.OFF)
        assertEquals(LoopMode.OFF, video.audioLoopMode)
        assertEquals(LoopMode.OFF, video.loopMode)
    }
}
