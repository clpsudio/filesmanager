package com.clpstudio.filesmanager.viewer

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.AppSettings
import com.clpstudio.filesmanager.data.DoubleTapSkip
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.LoopMode
import com.clpstudio.filesmanager.data.LoopPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** What a page of the photo / video pager shows. A video is always the real player, never a "Play" card. */
enum class PagerPage { PHOTO, VIDEO, PDF, TEXT, DOCUMENT, CARD }

fun pagerPageType(kind: FileKind): PagerPage = when (kind) {
    FileKind.IMAGE -> PagerPage.PHOTO
    FileKind.VIDEO -> PagerPage.VIDEO
    FileKind.PDF -> PagerPage.PDF
    FileKind.TEXT -> PagerPage.TEXT
    FileKind.DOCUMENT -> PagerPage.DOCUMENT
    else -> PagerPage.CARD
}

/**
 * The pager's one video player: the current video page plays inline (Media, Recent, Favorites,
 * folders), with the same controls as the video player. Loops per Settings → Loop videos.
 */
@OptIn(UnstableApi::class)
class PagerVideo(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext, com.clpstudio.filesmanager.playback.MediaRenderers.factory(context))
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
        .setHandleAudioBecomingNoisy(true)
        .build()

    var path by mutableStateOf<String?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var firstFrame by mutableStateOf(false)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var muted by mutableStateOf(false)
        private set
    /** The video's shape, for the picture-in-picture window. */
    var aspect by mutableStateOf<android.util.Rational?>(null)
        private set
    /** True while a long press plays at 2x (item 32). */
    var fast by mutableStateOf(false)
        private set
    /** Shared by every video view (pager pages and the full player), kept while paging. */
    val speed: Float get() = VideoSpeed.value
    var loop by mutableStateOf(true)
        private set
    var fill by mutableStateOf(false)
    /** Double-tap feedback: -1 back, +1 forward; [seekStamp] restarts the glow. */
    var seekSide by mutableIntStateOf(0)
    var seekStamp by mutableIntStateOf(0)
    var seekAmount by mutableIntStateOf(10)

    private var repeats = 0
    /** Opened by "Play N" (item 37): loop settings ignored, always on to the next video, the first after the last. */
    var playlist = false
    /** Set by the pager: go to the next video page (null at the last one). */
    var onNext: (() -> Unit)? = null
    /** "2/3" while "Play N times" is counting. */
    var loopHint by mutableStateOf<String?>(null)
    /** Item 12 (F11): why this video can't play, shown on the page instead of a stuck black screen. */
    var error by mutableStateOf<String?>(null)
        private set
    private var settings: AppSettings = AppSettings()

    // Calls pause it and it plays again 2 s after; notifications lower it (Settings).
    private val focus = com.clpstudio.filesmanager.playback.FocusController(
        context, player, duckEnabled = { runCatching { Graph.settings.value.duckForNotifications }.getOrDefault(true) }, movie = true,
    )

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                error = VideoErrors.message(path.orEmpty().substringAfterLast('/'), e.errorCode)
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                val turned = videoSize.unappliedRotationDegrees % 180 != 0
                aspect = if (turned) VideoPip.clampAspect(videoSize.height, videoSize.width) else VideoPip.clampAspect(videoSize.width, videoSize.height)
            }
            override fun onPlaybackStateChanged(state: Int) {
                // One play finished: again, the next video, or stop (Settings → Loop videos / the Loop button).
                if (state != Player.STATE_ENDED || path == null) return
                repeats++
                val mode = com.clpstudio.filesmanager.data.PlaylistPlay.loopMode(playlist, if (loop) settings.loopMode.takeIf { it != LoopMode.OFF } ?: LoopMode.FOREVER else LoopMode.OFF)
                when (LoopPolicy.afterPlay(mode, settings.loopTimes, repeats, hasNext = onNext != null)) {
                    LoopPolicy.After.REPLAY -> { player.seekTo(0); player.play() }
                    LoopPolicy.After.NEXT -> onNext?.invoke()
                    LoopPolicy.After.STOP -> Unit
                }
                loopHint = if (loop && !playlist) LoopPolicy.hint(mode, settings.loopTimes, repeats) else null
            }
        })
    }

    /** Starts [item] (when it isn't already the one loaded). */
    fun load(item: FileItem, s: AppSettings) {
        if (path == item.path) return
        settings = s
        focus.settingChanged(s.duckForNotifications)
        path = item.path
        repeats = 0
        firstFrame = false
        error = null
        if (fast) { fast = false } // a hold never carries over to the next video
        position = 0L
        duration = 0L
        loop = s.loopMode != LoopMode.OFF
        val uri = if (item.path.startsWith("content://")) Uri.parse(item.path) else Uri.fromFile(File(item.path))
        player.setMediaItem(MediaItem.fromUri(uri))
        // We count plays ourselves (no REPEAT_MODE_ONE, which never ends).
        player.repeatMode = Player.REPEAT_MODE_OFF
        loopHint = if (loop && !playlist) LoopPolicy.hint(s.loopMode, s.loopTimes, 0) else null
        player.setPlaybackSpeed(speed)
        player.volume = if (muted) 0f else 1f
        player.prepare()
        pendingSeek?.let { (p, ms) -> if (p == item.path) player.seekTo(ms) }
        pendingSeek = null
        player.play()
    }

    fun stop() {
        if (path == null) return
        path = null
        player.stop()
        player.clearMediaItems()
    }

    fun pause() = player.pause()

    // ---- Background play (Settings → Video player → Background play): leaving the app hands the video to the
    // media service (sound + notification keep going); coming back takes it back at the same spot.
    private var handed: List<FileItem> = emptyList()
    private var pendingSeek: Pair<String, Long>? = null

    fun handOff(context: Context, videos: List<FileItem>, index: Int) {
        if (videos.isEmpty() || index !in videos.indices) return
        val at = player.currentPosition.coerceAtLeast(0L)
        player.pause()
        handed = videos
        val items = videos.map { com.clpstudio.filesmanager.playback.PlaybackService.item(Uri.fromFile(File(it.path)), it.name.substringBeforeLast('.'), isVideo = true) }
        val repeatOne = !playlist && loop && settings.loopMode == LoopMode.FOREVER
        Graph.appScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            runCatching {
                val c = com.clpstudio.filesmanager.playback.PlayerConnection.controller(context)
                c.setMediaItems(items, index, at)
                c.repeatMode = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
                c.setPlaybackSpeed(VideoSpeed.value)
                c.prepare()
                c.play()
            }
        }
    }

    /** Back in the app: stop the background copy; [onResume] gets the file it was on and where. */
    fun takeBack(context: Context, onResume: (path: String, positionMs: Long, playing: Boolean) -> Unit) {
        val list = handed
        if (list.isEmpty()) return
        handed = emptyList()
        Graph.appScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            runCatching {
                val c = com.clpstudio.filesmanager.playback.PlayerConnection.controller(context)
                val item = list.getOrNull(c.currentMediaItemIndex) ?: return@runCatching
                val pos = c.currentPosition.coerceAtLeast(0L)
                val playing = c.isPlaying
                c.pause()
                c.clearMediaItems()
                if (item.path == path) {
                    seek(pos)
                    if (playing) player.play()
                } else {
                    pendingSeek = item.path to pos
                }
                onResume(item.path, pos, playing)
            }
        }
    }

    /** The list wrapped around to this same video: play it again from the start (new count). */
    fun restart() {
        repeats = 0
        loopHint = if (loop && !playlist) LoopPolicy.hint(settings.loopMode, settings.loopTimes, 0) else null
        player.seekTo(0)
        player.play()
    }

    /** Wider than tall (or not known yet): full screen turns to landscape. */
    fun isWide(): Boolean {
        val s = player.videoSize
        if (s.width == 0 || s.height == 0) return true
        val turned = s.unappliedRotationDegrees % 180 != 0
        return if (turned) s.height >= s.width else s.width >= s.height
    }

    fun playPause() {
        if (player.isPlaying) { player.pause(); return }
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    /** Middle sideways drag: the time bubble shows [scrubMs] (null when not scrubbing). */
    var scrubMs by mutableStateOf<Long?>(null)
        private set
    var scrubStartMs by mutableLongStateOf(0L)
        private set
    private var scrubWasPlaying = false

    fun scrubStart(fromMs: Long) {
        scrubStartMs = fromMs
        scrubMs = fromMs
        scrubWasPlaying = player.isPlaying
        player.pause()
    }

    /** Live position while dragging; [seek] = also move the picture now (throttled by the caller). */
    fun scrubTo(ms: Long, seek: Boolean) {
        scrubMs = ms
        position = ms
        if (seek) player.seekTo(ms)
    }

    fun scrubEnd(cancelled: Boolean) {
        val target = scrubMs
        scrubMs = null
        if (!cancelled && target != null) seek(target) else player.seekTo(scrubStartMs)
        if (scrubWasPlaying) player.play()
    }

    fun seek(ms: Long) {
        if (ms < 1_000) { repeats = 0; loopHint = if (loop && !playlist) LoopPolicy.hint(settings.loopMode, settings.loopTimes, 0) else null }
        player.seekTo(ms.coerceIn(0, duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
        position = ms
    }

    fun skip(side: Int, seconds: Int) {
        seek(player.currentPosition + side * DoubleTapSkip.ms(seconds))
        seekAmount = if (seekSide == side && seekStamp > 0 && System.currentTimeMillis() - lastSkip < 1_200) seekAmount + seconds else seconds
        lastSkip = System.currentTimeMillis()
        seekSide = side
        seekStamp++
    }
    private var lastSkip = 0L

    fun toggleMute() {
        muted = !muted
        player.volume = if (muted) 0f else 1f
    }

    fun changeSpeed(s: Float) {
        VideoSpeed.set(s)
        fast = false
        player.setPlaybackSpeed(VideoSpeed.value)
    }

    /** Press and hold the video: 2x while held, like the full player (item 32). */
    fun startFast() {
        // Not "if (fast) return": a hold that was cut off (page changed, video ended, screen recomposed) never
        // reached endFast(), [fast] stayed true and every later long press did nothing (item 17 cause).
        if (path == null) return
        fast = true
        player.setPlaybackSpeed(2f)
        if (!player.isPlaying) player.play()
    }

    /** Finger up: back to the chosen speed. */
    fun endFast() {
        if (!fast) return
        fast = false
        player.setPlaybackSpeed(VideoSpeed.value)
    }

    /**
     * Closing the video (back, swipe down, the screen closing, the picture-in-picture window's X), item 33:
     * nothing plays on, and a copy handed to the background service is stopped too (its notification goes).
     */
    fun closeAll(context: Context) {
        stop()
        if (handed.isNotEmpty()) {
            handed = emptyList()
            pendingSeek = null
            Graph.appScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                runCatching { com.clpstudio.filesmanager.playback.PlayerConnection.controller(context).run { stop(); clearMediaItems() } }
            }
        }
    }

    /** The Loop button: this video now, and remembered as Settings → Loop videos. */
    fun changeLoop(on: Boolean) {
        loop = on
        repeats = 0
        loopHint = null
        val mode = if (on) LoopPolicy.toggled(LoopMode.OFF, settings.loopMode) else LoopMode.OFF
        settings = settings.copy(loopMode = mode)
        runCatching { Graph.appScope.launch { Graph.settingsRepo.setLoop(mode) } }
    }

    fun tick() {
        position = player.currentPosition.coerceAtLeast(0)
        duration = player.duration.takeIf { it > 0 } ?: 0L
    }

    fun release() {
        focus.release()
        player.release()
    }
}

/** One video page: the inline player when it's the current page, its first frame otherwise. */
@OptIn(UnstableApi::class)
@Composable
fun InlineVideoPage(
    item: FileItem,
    isCurrent: Boolean,
    video: PagerVideo,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    gestures: VideoPageGestureCallbacks? = null,
    pill: LevelPillState? = null,
    dismiss: DismissDragState? = null,
) {
    val active = isCurrent && video.path == item.path
    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (active) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        setKeepContentOnPlayerReset(true)
                        player = video.player
                    }
                },
                update = { v ->
                    if (v.player !== video.player) v.player = video.player
                    v.resizeMode = if (video.fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // The first frame until the video draws (no black flash on swipe).
        if (!active || !video.firstFrame) EntryStill(item, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize()
                .videoPageGestures(video, enabled = active && gestures != null && pill != null && dismiss != null, pill = pill ?: LevelPillState(), dismiss = dismiss ?: DismissDragState(), callbacks = gestures ?: NoPageGestures)
                .pointerInput(item.path, active) {
                detectTapGestures(
                    onTap = { onTap() },
                    // Press and hold = 2x while held (item 32), back to the chosen speed on release.
                    onLongPress = { if (active) video.startFast() },
                    // finally: the hold also ends when this page's gesture is cancelled (not only on finger up).
                    onPress = { try { tryAwaitRelease() } finally { video.endFast() } },
                    onDoubleTap = { o ->
                        if (active) {
                            val side = if (o.x > size.width / 2f) 1 else -1
                            video.skip(side, Graph.settings.value.doubleTapSkipSeconds)
                        }
                    },
                )
            },
        )
        if (active) SkipGlow(video)
        val err = video.error
        if (active && err != null) VideoErrorCard(item, err, Modifier.align(Alignment.Center))
        // Leaving the page while holding: back to the chosen speed.
        androidx.compose.runtime.DisposableEffect(active) { onDispose { if (active) video.endFast() } }
        if (active && video.fast) SpeedPill(Modifier.align(Alignment.TopCenter).padding(top = 96.dp))
        val scrub = video.scrubMs
        if (active && scrub != null) {
            ScrubBubble(scrub, video.duration, scrub - video.scrubStartMs, Modifier.align(Alignment.Center))
        }
    }
}

private object NoPageGestures : VideoPageGestureCallbacks {
    override fun onPageDrag(dx: Float) {}
    override fun onPageEnd(dx: Float, velocityPxS: Float) {}
    override fun onInfo() {}
    override fun onDismiss() {}
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.SkipGlow(video: PagerVideo) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(video.seekStamp) {
        if (video.seekStamp > 0) { visible = true; delay(550); visible = false }
    }
    val forward = video.seekSide > 0
    AnimatedVisibility(
        visible, enter = fadeIn(tween(120)), exit = fadeOut(tween(300)),
        modifier = Modifier.align(if (forward) Alignment.CenterEnd else Alignment.CenterStart).fillMaxHeight().fillMaxWidth(0.34f),
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(if (forward) listOf(Color.Transparent, Color.White.copy(alpha = 0.14f)) else listOf(Color.White.copy(alpha = 0.14f), Color.Transparent)),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (forward) Icons.Filled.FastForward else Icons.Filled.FastRewind, null, tint = Color.White, modifier = Modifier.size(34.dp))
                Text("${video.seekAmount} s", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * The playback speed every video view shares (the "1x" pill in the pager and in the player): chosen once,
 * kept while paging to the next video and when the full player opens. Not saved across app restarts.
 */
object VideoSpeed {
    var value by mutableFloatStateOf(1f)
        private set
    /** The speed menu is open: the controls don't hide under the finger (item 32). */
    var menuOpen by androidx.compose.runtime.mutableStateOf(false)

    /** Only the menu's speeds; anything else falls back to normal. */
    fun set(s: Float) {
        value = if (s in SPEEDS) s else 1f
    }
}


/** Item 12 (F11): plain words for a video the phone can't play (pure, unit tested). */
object VideoErrors {
    /** Containers / codecs no Android phone decodes and Files doesn't bundle a decoder for. */
    private val windowsMedia = setOf("wmv", "asf", "wma")
    private val realMedia = setOf("rm", "rmvb")

    fun message(name: String, errorCode: Int): String {
        val ext = com.clpstudio.filesmanager.data.FileTypes.extension(name)
        return when {
            ext in windowsMedia -> "Files can't play Windows Media (WMV) videos. Android has no WMV player built in and Files doesn't include one yet. Open it with another app."
            ext in realMedia -> "Files can't play RealMedia (RMVB) videos. Open it with another app."
            errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                "This phone can't decode this video's format. Open it with another app."
            errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                "This video file's format isn't supported here, or the file is damaged. Open it with another app."
            errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "This video isn't there any more."
            else -> "This video can't be played here. Open it with another app."
        }
    }
}

@Composable
private fun VideoErrorCard(item: FileItem, message: String, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.foundation.layout.Column(
        modifier.padding(28.dp).background(Color(0xCC000000), androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.Text(message, color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
        androidx.compose.material3.Button(onClick = {
            val f = java.io.File(item.path)
            runCatching { com.clpstudio.filesmanager.data.FileOps.openWithOtherApp(context, com.clpstudio.filesmanager.data.FileOps.uriFor(context, f), com.clpstudio.filesmanager.data.FileTypes.mimeOf(f.name)) }
        }) { androidx.compose.material3.Text("Open with another app") }
    }
}
