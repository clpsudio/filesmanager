package com.clpstudio.filesmanager.viewer

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle

/**
 * Picture-in-picture for the gallery-style video views (pager in Files / "Open with", item 34):
 * leaving the app while a video plays shows it in a small window (Android 12+: auto-enter; 8-11: on Home).
 * Closing the small window stops the video and its notification (item 33).
 */
object VideoPip {
    enum class Leave { PIP, BACKGROUND, PAUSE }

    /**
     * What leaving the app (Home, recents, another app) does with what's on screen (pure, unit tested):
     * a playing video -> picture-in-picture (Settings -> Auto picture-in-picture, default on); with that off,
     * Background play keeps the sound going; a paused video or no video -> nothing plays on.
     */
    fun onLeave(isVideo: Boolean, playing: Boolean, autoPip: Boolean, backgroundPlay: Boolean): Leave = when {
        !isVideo || !playing -> Leave.PAUSE
        autoPip -> Leave.PIP
        backgroundPlay -> Leave.BACKGROUND
        else -> Leave.PAUSE
    }

    /** What the screen hosting a video does in picture-in-picture. */
    interface Host {
        fun playPause()
        fun next()
        fun previous()
        /** The small window was closed: stop playback (and any background copy). */
        fun stop()
    }

    var inPip by mutableStateOf(false)
        private set
    private var host: Host? = null
    private var playing = false
    private var aspect: Rational? = null
    private var hasPrevNext = false

    const val ACTION = "com.clpstudio.filesmanager.VIDEO_PIP"
    private const val EXTRA_CMD = "cmd"
    private const val CMD_PLAY_PAUSE = 1
    private const val CMD_NEXT = 2
    private const val CMD_PREV = 3

    private fun autoPip() = runCatching { com.clpstudio.filesmanager.Graph.settings.value.autoPip }.getOrDefault(true)

    /** The aspect ratio Android accepts (between 1:2.39 and 2.39:1). */
    fun clampAspect(w: Int, h: Int): Rational? {
        if (w <= 0 || h <= 0) return null
        val r = w.toFloat() / h
        return when {
            r > 2.39f -> Rational(239, 100)
            r < 1f / 2.39f -> Rational(100, 239)
            else -> Rational(w, h)
        }
    }

    fun params(context: Context): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val b = PictureInPictureParams.Builder()
        aspect?.let { b.setAspectRatio(it) }
        val actions = buildList {
            if (hasPrevNext) add(action(context, CMD_PREV, android.R.drawable.ic_media_previous, "Previous"))
            add(
                if (playing) action(context, CMD_PLAY_PAUSE, android.R.drawable.ic_media_pause, "Pause")
                else action(context, CMD_PLAY_PAUSE, android.R.drawable.ic_media_play, "Play"),
            )
            if (hasPrevNext) add(action(context, CMD_NEXT, android.R.drawable.ic_media_next, "Next"))
        }
        b.setActions(actions)
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(host != null && playing && autoPip())
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    private fun action(context: Context, cmd: Int, icon: Int, title: String): RemoteAction {
        val pi = PendingIntent.getBroadcast(
            context, cmd, Intent(ACTION).setPackage(context.packageName).putExtra(EXTRA_CMD, cmd),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteAction(Icon.createWithResource(context, icon), title, title, pi)
    }

    private fun push(activity: Activity?) {
        val a = activity ?: return
        if (Build.VERSION.SDK_INT < 26) return
        runCatching { params(a)?.let { a.setPictureInPictureParams(it) } }
    }

    /** Called by the video view whenever what it shows changes. */
    fun update(activity: Activity?, host: Host?, playing: Boolean, aspect: Rational?, hasPrevNext: Boolean) {
        this.host = host
        this.playing = playing
        if (aspect != null) this.aspect = aspect
        this.hasPrevNext = hasPrevNext
        push(activity)
    }

    fun clear(activity: Activity?, host: Host) {
        if (this.host !== host) return
        this.host = null
        playing = false
        push(activity)
    }

    /** Android 8-11 (12+ enters by itself): Home while a video plays -> the small window. */
    fun onUserLeaveHint(activity: Activity) {
        if (Build.VERSION.SDK_INT !in 26..30) return
        if (host == null || !playing || !autoPip()) return
        runCatching { params(activity)?.let { activity.enterPictureInPictureMode(it) } }
    }

    /** From the activity: the small window opened or closed. Closed while not visible = the user dismissed it. */
    fun onModeChanged(activity: Activity, isInPip: Boolean, lifecycle: Lifecycle) {
        inPip = isInPip
        if (!isInPip && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) host?.stop()
    }

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val h = host ?: return
            when (intent.getIntExtra(EXTRA_CMD, 0)) {
                CMD_PLAY_PAUSE -> h.playPause()
                CMD_NEXT -> h.next()
                CMD_PREV -> h.previous()
            }
        }
    }

    fun register(activity: Activity) {
        runCatching { ContextCompat.registerReceiver(activity, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    fun unregister(activity: Activity) {
        runCatching { activity.unregisterReceiver(receiver) }
    }
}

/** Wires a gallery video view to picture-in-picture while it is on screen. */
@Composable
fun VideoPipEffect(video: PagerVideo, isVideo: Boolean, hasPrevNext: Boolean, onNext: () -> Unit, onPrevious: () -> Unit) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val host = androidx.compose.runtime.remember(video) {
        object : VideoPip.Host {
            var next: () -> Unit = {}
            var prev: () -> Unit = {}
            override fun playPause() = video.playPause()
            override fun next() = next.invoke()
            override fun previous() = prev.invoke()
            override fun stop() = video.closeAll(context)
        }
    }
    host.next = onNext
    host.prev = onPrevious
    LaunchedEffect(isVideo, video.playing, video.aspect, hasPrevNext) {
        if (isVideo) VideoPip.update(activity, host, video.playing, video.aspect, hasPrevNext) else VideoPip.clear(activity, host)
    }
    DisposableEffect(host) { onDispose { VideoPip.clear(activity, host) } }
}
