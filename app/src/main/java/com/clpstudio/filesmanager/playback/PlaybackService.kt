package com.clpstudio.filesmanager.playback

import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.clpstudio.filesmanager.MainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * One player for music and video. It lives in this service, so sound keeps playing in the
 * background (and with the screen off) with a media notification and lock-screen controls.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var focus: FocusController? = null

    override fun onCreate() {
        super.onCreate()
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractors))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                // Our own focus handling (FocusController): pause on a call, resume 2 s after it, duck per Settings.
                false,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_NOW_PLAYING)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Tapping the notification: back to the video player for a video, the music screen for audio.
        val openVideo = PendingIntent.getActivity(
            this, 1, com.clpstudio.filesmanager.viewer.VideoPlayerActivity.resumeIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(Callback())
            .build()
        focus = FocusController(this, player, duckEnabled = { runCatching { com.clpstudio.filesmanager.Graph.settings.value.duckForNotifications }.getOrDefault(true) }, movie = false)
        com.clpstudio.filesmanager.data.FileEvents.addListener(fileListener)
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val video = mediaItem?.mediaMetadata?.let { m ->
                    m.mediaType == MediaMetadata.MEDIA_TYPE_VIDEO || m.extras?.getBoolean(EXTRA_IS_VIDEO) == true
                } == true
                runCatching { session?.setSessionActivity(if (video) openVideo else open) }
                // Audio loop (Settings → Loop, shared with videos): count the plays of this track.
                if (!video) {
                    AudioLoop.plays = if (reason == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) AudioLoop.plays + 1 else 0
                    applyAudioLoop()
                }
            }
        })
        loopJob = com.clpstudio.filesmanager.Graph.appScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            com.clpstudio.filesmanager.Graph.settings.collect { applyAudioLoop() }
        }
    }

    private var loopJob: kotlinx.coroutines.Job? = null

    /**
     * Audio tracks follow Settings → Audio player → Audio loop (items 23-26, separate from the video loop): Off = each track once, then the next (after
     * the last, the first again); Play N times = each track N plays in total, then the next; Forever = the same track again and again.
     * The service counts plays itself, so it works in the background too. Videos keep their own handling.
     */
    private fun applyAudioLoop() {
        val p = session?.player ?: return
        val item = p.currentMediaItem ?: return
        val isVideo = item.mediaMetadata.extras?.getBoolean(EXTRA_IS_VIDEO) == true || item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_VIDEO
        if (isVideo) return
        val s = runCatching { com.clpstudio.filesmanager.Graph.settings.value }.getOrNull() ?: return
        // The audio player's own loop setting (item 26): the video one never applies here.
        AudioLoop.mode = s.audioLoopMode
        if (s.audioLoopMode != com.clpstudio.filesmanager.data.LoopMode.OFF) AudioLoop.lastOn = s.audioLoopMode
        AudioLoop.times = s.audioLoopTimes
        val want = AudioLoop.repeatModeFor(s.audioLoopMode, s.audioLoopTimes, AudioLoop.plays)
        if (p.repeatMode != want) p.repeatMode = want
    }

    /** Audio deleted / moved through the app leaves the queue (videos are handled by the video player). */
    private val fileListener: (com.clpstudio.filesmanager.data.FileEvents.Change) -> Unit = { change ->
        if (change.removed.isNotEmpty()) main.post { dropRemovedAudio() }
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun dropRemovedAudio() {
        val p = session?.player ?: return
        for (i in p.mediaItemCount - 1 downTo 0) {
            val item = p.getMediaItemAt(i)
            val isVideo = item.mediaMetadata.extras?.getBoolean(EXTRA_IS_VIDEO) == true ||
                item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_VIDEO
            if (isVideo) continue
            val uri = runCatching { Uri.parse(item.mediaId) }.getOrNull() ?: continue
            val path = uri.path?.takeIf { uri.scheme == "file" } ?: continue
            if (com.clpstudio.filesmanager.data.FileEvents.isGone(path)) p.removeMediaItem(i)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        loopJob?.cancel()
        loopJob = null
        focus?.release()
        focus = null
        com.clpstudio.filesmanager.data.FileEvents.removeListener(fileListener)
        main.removeCallbacksAndMessages(null)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Items from a controller may arrive without their uri: rebuild it from the extras. */
    private class Callback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { restore(it) }.toMutableList())
    }

    companion object {
        const val EXTRA_SUBTITLES = "subs"
        const val EXTRA_IS_VIDEO = "isVideo"

        fun restore(item: MediaItem): MediaItem {
            if (item.localConfiguration != null) return item
            val uri = item.requestMetadata.mediaUri ?: Uri.parse(item.mediaId)
            val subs = item.requestMetadata.extras?.getStringArrayList(EXTRA_SUBTITLES).orEmpty()
            return item.buildUpon()
                .setUri(uri)
                .setSubtitleConfigurations(subs.map { Subtitles.config(Uri.parse(it)) })
                .build()
        }

        /** A media item for a local or content uri, with sibling subtitles for videos. */
        fun item(uri: Uri, title: String, isVideo: Boolean, subtitles: List<Uri> = emptyList(), artist: String? = null): MediaItem {
            val extras = Bundle().apply {
                putStringArrayList(EXTRA_SUBTITLES, ArrayList(subtitles.map { it.toString() }))
                putBoolean(EXTRA_IS_VIDEO, isVideo)
            }
            return MediaItem.Builder()
                .setMediaId(uri.toString())
                .setUri(uri)
                .setSubtitleConfigurations(subtitles.map { Subtitles.config(it) })
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).setExtras(extras).build())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setDisplayTitle(title)
                        .setArtist(artist)
                        .setExtras(Bundle().apply { putBoolean(EXTRA_IS_VIDEO, isVideo) })
                        .setMediaType(if (isVideo) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build(),
                )
                .build()
        }
    }
}

/** The audio loop as the music player shows it (same process as the service). */
object AudioLoop {
    var plays by androidx.compose.runtime.mutableIntStateOf(0)
    var mode by androidx.compose.runtime.mutableStateOf(com.clpstudio.filesmanager.data.LoopMode.OFF)
    var times by androidx.compose.runtime.mutableIntStateOf(com.clpstudio.filesmanager.data.LoopPolicy.DEFAULT_TIMES)
    /** The looping mode the Loop button turns back on. */
    var lastOn = com.clpstudio.filesmanager.data.LoopMode.FOREVER

    /** The music player's Loop button: off ↔ the last looping mode (saved as the shared Loop setting). */
    fun toggledMode(current: com.clpstudio.filesmanager.data.LoopMode): com.clpstudio.filesmanager.data.LoopMode =
        if (current != com.clpstudio.filesmanager.data.LoopMode.OFF) com.clpstudio.filesmanager.data.LoopMode.OFF
        else if (lastOn == com.clpstudio.filesmanager.data.LoopMode.OFF) com.clpstudio.filesmanager.data.LoopMode.FOREVER else lastOn

    /**
     * The player's repeat mode for the current track after [playsDone] repeats (pure: unit tested): the same track
     * again while a play is due, otherwise on through the list, and after the last track from the first again
     * (item 24: the loop is per file; the list itself always wraps).
     */
    fun repeatModeFor(mode: com.clpstudio.filesmanager.data.LoopMode, times: Int, playsDone: Int): Int =
        if (com.clpstudio.filesmanager.data.LoopPolicy.repeatCurrent(mode, times, playsDone)) androidx.media3.common.Player.REPEAT_MODE_ONE
        else androidx.media3.common.Player.REPEAT_MODE_ALL

    /** "2/3" while "Play N times" counts (null otherwise). */
    val hint: String? get() = com.clpstudio.filesmanager.data.LoopPolicy.hint(mode, times, plays)
}
