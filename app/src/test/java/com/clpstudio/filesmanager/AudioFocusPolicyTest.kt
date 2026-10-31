package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.playback.AudioFocusPolicy
import com.clpstudio.filesmanager.playback.AudioFocusPolicy.Action
import com.clpstudio.filesmanager.playback.AudioFocusPolicy.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusPolicyTest {
    @Test fun callPausesThenResumesTwoSecondsAfter() {
        val p = AudioFocusPolicy()
        assertEquals(listOf(Action.CancelResume, Action.Pause), p.on(Event.LOSS_TRANSIENT, playing = true))
        assertTrue(p.pausedByFocus)
        assertEquals(listOf(Action.ResumeAfter(2_000)), p.on(Event.GAIN, playing = false))
        assertFalse(p.pausedByFocus)
    }

    @Test fun silentPhoneTakesNoFocusSoNothingHappens() {
        // No ringtone = no focus event at all; and focus back with nothing paused does nothing.
        val p = AudioFocusPolicy()
        assertEquals(emptyList<Action>(), p.on(Event.GAIN, playing = true))
    }

    @Test fun userPausedDuringTheCallIsNotResumed() {
        val p = AudioFocusPolicy()
        p.on(Event.LOSS_TRANSIENT, playing = true)
        assertEquals(listOf(Action.CancelResume), p.onUser())
        assertEquals(emptyList<Action>(), p.on(Event.GAIN, playing = false))
    }

    @Test fun alreadyPausedByUserStaysPaused() {
        val p = AudioFocusPolicy()
        assertEquals(listOf(Action.CancelResume), p.on(Event.LOSS_TRANSIENT, playing = false))
        assertEquals(emptyList<Action>(), p.on(Event.GAIN, playing = false))
    }

    @Test fun focusLostAgainCancelsThePendingResume() {
        val p = AudioFocusPolicy()
        p.on(Event.LOSS_TRANSIENT, playing = true)
        p.on(Event.GAIN, playing = false) // resume scheduled
        assertEquals(listOf(Action.CancelResume), p.on(Event.LOSS_TRANSIENT, playing = false))
    }

    @Test fun anotherAppForGoodPausesWithoutResume() {
        val p = AudioFocusPolicy()
        assertEquals(listOf(Action.CancelResume, Action.Pause), p.on(Event.LOSS, playing = true))
        assertEquals(emptyList<Action>(), p.on(Event.GAIN, playing = false))
    }

    @Test fun notificationsDuckOnlyWhenTheSettingIsOn() {
        val on = AudioFocusPolicy(duckForNotifications = true)
        assertEquals(listOf(Action.Duck(0.3f)), on.on(Event.LOSS_CAN_DUCK, playing = true))
        assertEquals(listOf(Action.Unduck), on.on(Event.GAIN, playing = true))
        val off = AudioFocusPolicy(duckForNotifications = false)
        assertEquals(emptyList<Action>(), off.on(Event.LOSS_CAN_DUCK, playing = true))
        assertEquals(emptyList<Action>(), off.on(Event.GAIN, playing = true))
    }

    @Test fun callWhileDuckedRestoresVolumeAndPauses() {
        val p = AudioFocusPolicy()
        p.on(Event.LOSS_CAN_DUCK, playing = true)
        assertEquals(listOf(Action.CancelResume, Action.Unduck, Action.Pause), p.on(Event.LOSS_TRANSIENT, playing = true))
    }

    @Test fun headphonesUnpluggedPausesAndForgetsTheCall() {
        val p = AudioFocusPolicy()
        p.on(Event.LOSS_TRANSIENT, playing = true)
        p.on(Event.NOISY, playing = false)
        assertEquals(emptyList<Action>(), p.on(Event.GAIN, playing = false))
        assertEquals(listOf(Action.CancelResume, Action.Pause), AudioFocusPolicy().on(Event.NOISY, playing = true))
    }
}
