package com.clpstudio.filesmanager

import android.app.Application
import android.net.Uri
import android.util.Rational
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.testTag
import androidx.test.core.app.ApplicationProvider
import com.clpstudio.filesmanager.data.AppSettings
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.OpenedFile
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.viewer.ForeignVideoScreen
import com.clpstudio.filesmanager.viewer.InlineVideoPage
import com.clpstudio.filesmanager.viewer.PagerVideo
import com.clpstudio.filesmanager.viewer.VideoControlRow
import com.clpstudio.filesmanager.viewer.VideoPip
import com.clpstudio.filesmanager.viewer.VideoSpeed
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Items 32 (2x on the real player), 33 (closing stops everything), 34 (picture-in-picture on leaving). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h800dp-mdpi")
class VideoRound7Test {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var video: PagerVideo
    private val item get() = FileItem(File(app.cacheDir, "clip.mp4").absolutePath, "clip.mp4", false, 1000L, 0L, FileKind.VIDEO)

    @Before fun setUp() { VideoSpeed.set(1f) }

    @Test fun speedPillChangesTheVisiblePlayersSpeed() {
        compose.setContent {
            FilesTheme(ThemeMode.DARK, liquidGlass = false) {
                val v = remember { PagerVideo(app).also { video = it } }
                VideoControlRow(
                    muted = false, speed = VideoSpeed.value, loop = false, fill = false,
                    onMute = {}, onSpeed = v::changeSpeed, onLoop = {}, onFit = null,
                )
            }
        }
        compose.onNodeWithText("1x").performClick()
        compose.onNodeWithText("2x").performClick()
        compose.waitForIdle()
        assertEquals(2f, video.player.playbackParameters.speed)
        assertEquals(2f, VideoSpeed.value)
        compose.onNodeWithText("2x").assertIsDisplayed() // the pill's label follows
        // The next video keeps it (load applies the shared speed to the same player).
        video.load(item, AppSettings())
        assertEquals(2f, video.player.playbackParameters.speed)
        video.release()
    }

    @Test fun pressAndHoldPlaysAt2xUntilReleased() {
        compose.setContent {
            FilesTheme(ThemeMode.DARK, liquidGlass = false) {
                val v = remember { PagerVideo(app).also { video = it; it.load(item, AppSettings()) } }
                Box(Modifier.fillMaxSize().testTag("page")) { InlineVideoPage(item, isCurrent = true, video = v, onTap = {}) }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("page").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue(video.fast)
        assertEquals(2f, video.player.playbackParameters.speed)
        compose.onNodeWithText("2x ▶▶").assertIsDisplayed()
        compose.onNodeWithTag("page").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        assertFalse(video.fast)
        assertEquals(1f, video.player.playbackParameters.speed)
        video.release()
    }

    @Test fun chosen2xSurvivesHoldAndRelease() {
        // Both ways at once: the chip set to 2x, then a press-and-hold and release keeps 2x (not back to 1x),
        // and choosing 1x afterwards really returns the visible player to normal speed.
        val v = PagerVideo(app)
        v.load(item, AppSettings())
        v.changeSpeed(2f)
        assertEquals(2f, v.player.playbackParameters.speed)
        v.startFast()
        assertEquals(2f, v.player.playbackParameters.speed)
        v.endFast()
        assertEquals(2f, v.player.playbackParameters.speed)
        v.changeSpeed(1f)
        assertEquals(1f, v.player.playbackParameters.speed)
        v.startFast()
        assertEquals(2f, v.player.playbackParameters.speed)
        v.endFast()
        assertEquals(1f, v.player.playbackParameters.speed)
        v.release()
    }

    @Test fun closingStopsPlayback() {
        val v = PagerVideo(app)
        v.load(item, AppSettings())
        assertEquals(1, v.player.mediaItemCount)
        v.closeAll(app)
        assertNull(v.path)
        assertEquals(0, v.player.mediaItemCount)
        v.release()
    }

    @Test fun leavingTheAppDecision() {
        assertEquals(VideoPip.Leave.PIP, VideoPip.onLeave(isVideo = true, playing = true, autoPip = true, backgroundPlay = true))
        assertEquals(VideoPip.Leave.PIP, VideoPip.onLeave(isVideo = true, playing = true, autoPip = true, backgroundPlay = false))
        assertEquals(VideoPip.Leave.PAUSE, VideoPip.onLeave(isVideo = true, playing = false, autoPip = true, backgroundPlay = true))
        assertEquals(VideoPip.Leave.BACKGROUND, VideoPip.onLeave(isVideo = true, playing = true, autoPip = false, backgroundPlay = true))
        assertEquals(VideoPip.Leave.PAUSE, VideoPip.onLeave(isVideo = true, playing = true, autoPip = false, backgroundPlay = false))
        assertEquals(VideoPip.Leave.PAUSE, VideoPip.onLeave(isVideo = false, playing = true, autoPip = true, backgroundPlay = true))
    }

    @Test fun pipWindowShapeIsClamped() {
        assertEquals(Rational(1920, 1080), VideoPip.clampAspect(1920, 1080))
        assertEquals(Rational(239, 100), VideoPip.clampAspect(4000, 1000))
        assertEquals(Rational(100, 239), VideoPip.clampAspect(1000, 4000))
        assertNull(VideoPip.clampAspect(0, 0))
        assertTrue(VideoPip.params(app) != null)
    }

    @Test fun cameraVideoPlaysInTheGalleryStyleScreen() {
        val f = OpenedFile(Uri.parse("content://secmedia/media/2147494530"), "VID_20261031_151200.mp4", 2048L, "video/mp4", FileKind.VIDEO, null)
        compose.setContent { FilesTheme(ThemeMode.DARK, liquidGlass = false) { ForeignVideoScreen(f, onBack = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("VID_20261031_151200.mp4").assertIsDisplayed()
        compose.onNodeWithText("1x").assertIsDisplayed()
        compose.onNodeWithText("Share").assertIsDisplayed()
        compose.onRoot().captureRoboImage("/home/user/tab/files/files_r6_camera_video.png")
    }
}
