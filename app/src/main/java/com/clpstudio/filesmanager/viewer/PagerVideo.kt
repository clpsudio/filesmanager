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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
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
    var speed by mutableStateOf(1f)
        private set
    var loop by mutableStateOf(true)
        private set
    var fill by mutableStateOf(false)
    /** Double-tap feedback: -1 back, +1 forward; [seekStamp] restarts the glow. */
    var seekSide by mutableIntStateOf(0)
    var seekStamp by mutableIntStateOf(0)
    var seekAmount by mutableIntStateOf(10)

    private var repeats = 0
    private var settings: AppSettings = AppSettings()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    repeats++
                    if (!LoopPolicy.keepRepeating(settings.loopMode, settings.loopTimes, repeats)) player.repeatMode = Player.REPEAT_MODE_OFF
                }
            }
        })
    }

    /** Starts [item] (when it isn't already the one loaded). */
    fun load(item: FileItem, s: AppSettings) {
        if (path == item.path) return
        settings = s
        path = item.path
        repeats = 0
        firstFrame = false
        position = 0L
        duration = 0L
        loop = s.loopMode != LoopMode.OFF
        val uri = if (item.path.startsWith("content://")) Uri.parse(item.path) else Uri.fromFile(File(item.path))
        player.setMediaItem(MediaItem.fromUri(uri))
        player.repeatMode = if (LoopPolicy.repeatsAtStart(s.loopMode, s.loopTimes)) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.setPlaybackSpeed(speed)
        player.volume = if (muted) 0f else 1f
        player.prepare()
        player.play()
    }

    fun stop() {
        if (path == null) return
        path = null
        player.stop()
        player.clearMediaItems()
    }

    fun pause() = player.pause()

    fun playPause() {
        if (player.isPlaying) { player.pause(); return }
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    fun seek(ms: Long) {
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
        speed = s
        player.setPlaybackSpeed(s)
    }

    /** The Loop button: this video now, and remembered as Settings → Loop videos. */
    fun changeLoop(on: Boolean) {
        loop = on
        repeats = 0
        player.repeatMode = if (on) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        val mode = if (on) LoopPolicy.toggled(LoopMode.OFF, settings.loopMode) else LoopMode.OFF
        settings = settings.copy(loopMode = mode)
        runCatching { Graph.appScope.launch { Graph.settingsRepo.setLoop(mode) } }
    }

    fun tick() {
        position = player.currentPosition.coerceAtLeast(0)
        duration = player.duration.takeIf { it > 0 } ?: 0L
    }

    fun release() = player.release()
}

/** One video page: the inline player when it's the current page, its first frame otherwise. */
@OptIn(UnstableApi::class)
@Composable
fun InlineVideoPage(item: FileItem, isCurrent: Boolean, video: PagerVideo, onTap: () -> Unit, modifier: Modifier = Modifier) {
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
            Modifier.fillMaxSize().pointerInput(item.path, active) {
                detectTapGestures(
                    onTap = { onTap() },
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
    }
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
