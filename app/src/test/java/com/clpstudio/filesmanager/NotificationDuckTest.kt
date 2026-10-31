package com.clpstudio.filesmanager

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.clpstudio.filesmanager.playback.AudioFocusPolicy
import com.clpstudio.filesmanager.playback.FocusController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Item 40: "Lower volume for notifications" really lowers (and only when on). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationDuckTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun notif() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
    private fun music() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()

    @Test fun policyDucksOnNotificationSoundOnlyWhenOn() {
        val on = AudioFocusPolicy(duckForNotifications = true)
        assertEquals(listOf(AudioFocusPolicy.Action.Duck(AudioFocusPolicy.DUCK_VOLUME)), on.on(AudioFocusPolicy.Event.NOTIFICATION_SOUND_START, playing = true))
        assertEquals(listOf(AudioFocusPolicy.Action.Unduck), on.on(AudioFocusPolicy.Event.NOTIFICATION_SOUND_END, playing = true))
        val off = AudioFocusPolicy(duckForNotifications = false)
        assertTrue(off.on(AudioFocusPolicy.Event.NOTIFICATION_SOUND_START, playing = true).isEmpty())
        assertTrue(off.on(AudioFocusPolicy.Event.LOSS_CAN_DUCK, playing = true).isEmpty()) // no pause either
        // Turned off while ducked: back to full at once.
        val p = AudioFocusPolicy(true)
        p.on(AudioFocusPolicy.Event.NOTIFICATION_SOUND_START, true)
        assertEquals(listOf(AudioFocusPolicy.Action.Unduck), p.onSettingOff())
    }

    @Test fun whichSoundsCount() {
        assertTrue(AudioFocusPolicy.isNotificationSound(AudioAttributes.USAGE_NOTIFICATION))
        assertTrue(AudioFocusPolicy.isNotificationSound(AudioAttributes.USAGE_NOTIFICATION_EVENT))
        assertFalse(AudioFocusPolicy.isNotificationSound(AudioAttributes.USAGE_MEDIA))
        assertFalse(AudioFocusPolicy.isNotificationSound(AudioAttributes.USAGE_ALARM))
        assertFalse(AudioFocusPolicy.isNotificationSound(AudioAttributes.USAGE_NOTIFICATION_RINGTONE))
    }

    private fun run(enabled: Boolean): Pair<Float, Float> {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val shadow = Shadows.shadowOf(am)
        val player = ExoPlayer.Builder(app).build()
        val focus = FocusController(app, player, duckEnabled = { enabled }, movie = true)
        player.playWhenReady = true // asks for focus and starts watching other apps' sounds
        ShadowLooper.idleMainLooper()
        shadow.setActivePlaybackConfigurationsFor(listOf(music(), notif()), true)
        ShadowLooper.idleMainLooper()
        val during = player.volume
        shadow.setActivePlaybackConfigurationsFor(listOf(music()), true)
        ShadowLooper.idleMainLooper()
        val after = player.volume
        focus.release(); player.release()
        return during to after
    }

    @Test fun notificationSoundWithoutFocusStillDucksWhenOn() {
        val (during, after) = run(enabled = true)
        assertEquals(AudioFocusPolicy.DUCK_VOLUME, during, 0.001f)
        assertEquals(1f, after, 0.001f)
    }

    @Test fun offKeepsFullVolume() {
        val (during, after) = run(enabled = false)
        assertEquals(1f, during, 0.001f)
        assertEquals(1f, after, 0.001f)
    }
}
