package com.clpstudio.filesmanager.playback

import android.content.Context
import android.media.AudioAttributes as PlatformAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player

/**
 * What to do when another sound wants the speaker (an incoming call's ringtone, a navigation prompt,
 * a notification, another music app). Pure: events in, actions out. Unit-tested.
 *  - Ringing / a call (transient loss) → pause; when it ends (focus back) → play again after 2 s,
 *    but only if WE paused it and the user didn't press pause / play meanwhile.
 *    A phone on Silent / Vibrate doesn't ring, takes no focus, and playback simply continues.
 *  - Another app takes the speaker for good → pause, no auto-resume.
 *  - A notification sound (may duck) → ~30 % volume, back to full after; or nothing when
 *    Settings → "Lower volume for notifications" is off.
 *  - Headphones unplugged → pause, no auto-resume.
 */
class AudioFocusPolicy(var duckForNotifications: Boolean = true) {
    sealed interface Action {
        data object Pause : Action
        data class ResumeAfter(val delayMs: Long) : Action
        data object CancelResume : Action
        data class Duck(val volume: Float) : Action
        data object Unduck : Action
    }

    /**
     * NOTIFICATION_SOUND_START / _END (item 40): another app's notification sound started / stopped playing,
     * seen through AudioManager's playback callback — many phones (Samsung included) play notification sounds
     * without taking audio focus while media plays, so LOSS_CAN_DUCK alone never arrived.
     */
    enum class Event { LOSS_TRANSIENT, LOSS, LOSS_CAN_DUCK, GAIN, NOISY, NOTIFICATION_SOUND_START, NOTIFICATION_SOUND_END }

    var pausedByFocus = false
        private set
    var ducked = false
        private set

    fun on(event: Event, playing: Boolean): List<Action> = when (event) {
        Event.LOSS_TRANSIENT -> {
            val out = mutableListOf<Action>(Action.CancelResume)
            if (ducked) { ducked = false; out += Action.Unduck }
            if (playing) { pausedByFocus = true; out += Action.Pause }
            out
        }
        Event.LOSS -> {
            pausedByFocus = false
            val out = mutableListOf<Action>(Action.CancelResume)
            if (ducked) { ducked = false; out += Action.Unduck }
            if (playing) out += Action.Pause
            out
        }
        Event.LOSS_CAN_DUCK, Event.NOTIFICATION_SOUND_START ->
            if (duckForNotifications && playing && !ducked) { ducked = true; listOf(Action.Duck(DUCK_VOLUME)) } else emptyList()
        Event.NOTIFICATION_SOUND_END ->
            if (ducked) { ducked = false; listOf(Action.Unduck) } else emptyList()
        Event.GAIN -> {
            val out = mutableListOf<Action>()
            if (ducked) { ducked = false; out += Action.Unduck }
            if (pausedByFocus) { pausedByFocus = false; out += Action.ResumeAfter(RESUME_DELAY_MS) }
            out
        }
        Event.NOISY -> {
            pausedByFocus = false
            if (playing) listOf(Action.CancelResume, Action.Pause) else listOf(Action.CancelResume)
        }
    }

    /** Settings → "Lower volume for notifications" turned off while ducked: back to full volume at once. */
    fun onSettingOff(): List<Action> = if (ducked) { ducked = false; listOf(Action.Unduck) } else emptyList()

    /** The user pressed pause or play: never auto-resume what they chose. */
    fun onUser(): List<Action> {
        pausedByFocus = false
        return listOf(Action.CancelResume)
    }

    companion object {
        const val RESUME_DELAY_MS = 2_000L
        const val DUCK_VOLUME = 0.3f

        /** Audio usages that count as a notification sound (not ringtones/alarms: those pause or are left alone). */
        val NOTIFICATION_USAGES = setOf(
            PlatformAttributes.USAGE_NOTIFICATION,
            PlatformAttributes.USAGE_NOTIFICATION_EVENT,
            @Suppress("DEPRECATION") PlatformAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
            @Suppress("DEPRECATION") PlatformAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
            @Suppress("DEPRECATION") PlatformAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
            PlatformAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
        )

        fun isNotificationSound(usage: Int): Boolean = usage in NOTIFICATION_USAGES
    }
}

/**
 * Runs [AudioFocusPolicy] for one Media3 [player] (built with handleAudioFocus = false): requests focus
 * when playback starts, abandons it when it stops, pauses / resumes / ducks as the policy says.
 * [duckEnabled] is read each time (Settings → Lower volume for notifications).
 */
class FocusController(context: Context, private val player: Player, private val duckEnabled: () -> Boolean, movie: Boolean) {
    private val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val policy = AudioFocusPolicy()
    private var hasFocus = false
    private var internal = false
    private var volumeBeforeDuck = 1f
    private val resume = Runnable { internal = true; player.play(); internal = false }

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post {
            val ev = when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioFocusPolicy.Event.LOSS_TRANSIENT
                AudioManager.AUDIOFOCUS_LOSS -> AudioFocusPolicy.Event.LOSS
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AudioFocusPolicy.Event.LOSS_CAN_DUCK
                AudioManager.AUDIOFOCUS_GAIN, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> AudioFocusPolicy.Event.GAIN
                else -> null
            } ?: return@post
            if (ev == AudioFocusPolicy.Event.LOSS) hasFocus = false
            if (ev == AudioFocusPolicy.Event.GAIN) hasFocus = true
            run(policy.also { it.duckForNotifications = duckEnabled() }.on(ev, player.isPlaying || player.playWhenReady))
        }
    }

    // Item 40: other apps' notification sounds that play without asking for audio focus.
    private var notificationPlaying = false
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
            val now = configs.orEmpty().any { AudioFocusPolicy.isNotificationSound(it.audioAttributes.usage) }
            main.post {
                if (now == notificationPlaying) return@post
                notificationPlaying = now
                val enabled = duckEnabled()
                policy.duckForNotifications = enabled
                run(policy.on(if (now) AudioFocusPolicy.Event.NOTIFICATION_SOUND_START else AudioFocusPolicy.Event.NOTIFICATION_SOUND_END, player.isPlaying || player.playWhenReady))
            }
        }
    }
    private var watchingPlayback = false

    private val request: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            PlatformAttributes.Builder().setUsage(PlatformAttributes.USAGE_MEDIA)
                .setContentType(if (movie) PlatformAttributes.CONTENT_TYPE_MOVIE else PlatformAttributes.CONTENT_TYPE_MUSIC).build(),
        )
        // We duck (or not) ourselves, so the system must tell us instead of ducking on its own.
        .setWillPauseWhenDucked(true)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener(listener, main)
        .build()

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!internal && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) run(policy.onUser())
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) onNoisy()
            if (playWhenReady) requestFocus()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                if (!player.playWhenReady || state == Player.STATE_IDLE) abandon()
            }
        }
    }

    init {
        player.addListener(playerListener)
        if (player.playWhenReady) requestFocus()
    }

    private fun requestFocus() {
        if (!watchingPlayback) {
            watchingPlayback = runCatching { am.registerAudioPlaybackCallback(playbackCallback, main); true }.getOrDefault(false)
        }
        if (hasFocus) return
        hasFocus = am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasFocus) { internal = true; player.pause(); internal = false }
    }

    private fun abandon() {
        if (!hasFocus) return
        main.removeCallbacks(resume)
        am.abandonAudioFocusRequest(request)
        hasFocus = false
    }

    /** Headphones unplugged (ACTION_AUDIO_BECOMING_NOISY). */
    fun onNoisy() = run(policy.on(AudioFocusPolicy.Event.NOISY, player.isPlaying))

    private fun run(actions: List<AudioFocusPolicy.Action>) {
        for (a in actions) when (a) {
            AudioFocusPolicy.Action.Pause -> { internal = true; player.pause(); internal = false }
            is AudioFocusPolicy.Action.ResumeAfter -> { main.removeCallbacks(resume); main.postDelayed(resume, a.delayMs) }
            AudioFocusPolicy.Action.CancelResume -> main.removeCallbacks(resume)
            is AudioFocusPolicy.Action.Duck -> { volumeBeforeDuck = player.volume; player.volume = volumeBeforeDuck * a.volume }
            // Muted meanwhile (the video's sound button): stay muted.
            AudioFocusPolicy.Action.Unduck -> if (player.volume != 0f) player.volume = volumeBeforeDuck
        }
    }

    /** Settings → "Lower volume for notifications" changed: off restores full volume right away. */
    fun settingChanged(enabled: Boolean) {
        policy.duckForNotifications = enabled
        if (!enabled) run(policy.onSettingOff())
    }

    fun release() {
        player.removeListener(playerListener)
        if (watchingPlayback) runCatching { am.unregisterAudioPlaybackCallback(playbackCallback) }
        watchingPlayback = false
        abandon()
    }
}
