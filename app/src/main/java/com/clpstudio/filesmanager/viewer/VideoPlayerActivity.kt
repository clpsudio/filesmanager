package com.clpstudio.filesmanager.viewer

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.playback.PlaybackService
import com.clpstudio.filesmanager.playback.PlayerConnection
import com.clpstudio.filesmanager.playback.Subtitles
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Video player. The player lives in [PlaybackService]: leaving the app or locking the phone keeps the sound
 * playing with a media notification (Settings → Background play), and Home / app switch while playing goes to
 * picture-in-picture (Settings → Auto picture-in-picture) with previous / play-pause / next.
 *
 * Gestures (Charan, Oct 31):
 *  - left / right third: sideways = move the timeline (time bubble), up / down = brightness / volume;
 *  - middle third: sideways = previous / next with a slide, up = info, down = close;
 *  - tap = controls, double-tap left / right = -/+10 s, long press = 2x while held.
 * The controls never dim the video: only soft gradients behind the top bar and the bottom controls.
 * Opened from Media, the list holds photos too: the player shows a photo in place and plays only the videos.
 */
@OptIn(UnstableApi::class)
class VideoPlayerActivity : ComponentActivity() {
    private lateinit var playerView: PlayerView
    private var controller: MediaController? = null
    private var started = false
    private val ui = VideoOverlayState()

    /** Every entry of the list (videos, and photos when opened from Media), in the list's order. */
    private var entries: List<FileItem> = emptyList()
    private var playlist = MixedPlaylist(emptyList())
    private var entry = 0

    /** Before the user rotates by hand, the orientation follows the video's shape. */
    private var userOrientation: Int? = null
    private var normalSpeed = 1f
    private var videoW = 16
    private var videoH = 9

    private val settings get() = Graph.settings.value

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ui.playing = isPlaying
            updatePip()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val c = controller ?: return
            if (ui.photo != null) return
            val e = playlist.entryOfMedia(c.currentMediaItemIndex)
            if (e >= 0 && e != entry) {
                entry = e
                ui.flashName()
            }
            syncUi()
        }

        override fun onRenderedFirstFrame() {
            ui.cover = null
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                videoW = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
                videoH = videoSize.height
                updatePip()
                if (userOrientation == null && !ui.pip) {
                    requestedOrientation = if (videoW > videoH) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recents privacy; on Android 8–12 the FLAG_SECURE step is skipped while in picture-in-picture
        // (it would black out the small window).
        com.clpstudio.filesmanager.ui.PrivacyGuard.install(this) { !isInPipSafe() }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ui.pipSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            // Our own controls (VideoOverlay): the built-in controller darkens the whole video.
            useController = false
            keepScreenOn = false
        }
        val compose = ComposeView(this).apply {
            setContent {
                FilesTheme(ThemeMode.DARK, liquidGlass = false) {
                    val favs by com.clpstudio.filesmanager.data.Favorites.paths.collectAsState()
                    ui.favorite = currentPath()?.let { it in favs } == true
                    Box(Modifier.fillMaxSize()) {
                        VideoOverlay(
                            state = ui,
                            actions = videoActions,
                            sheetActions = videoSheetActions(
                                onShare = { currentUri()?.let { shareCurrent(it) } },
                                onOpenWith = { currentUri()?.let { openWithOther(it) } },
                                onFolder = null,
                            ),
                        )
                        if (!ui.pip) LevelPillHost(pill)
                    }
                }
            }
        }
        val root = VideoGestureLayout(this).apply {
            callbacks = swipeCallbacks
            addView(playerView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
        setupGestures()
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closePlayer()
        })
        // Position / duration for the timeline.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    controller?.let { c ->
                        if (!ui.scrubbing) ui.position = c.currentPosition.coerceAtLeast(0L)
                        ui.duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
                        ui.playing = c.isPlaying
                    }
                    delay(if (ui.controls || ui.scrubbing) 200L else 500L)
                }
            }
        }
    }

    /** Back / close: stops the video (Home keeps it playing in picture-in-picture or in the background). */
    private fun closePlayer() {
        controller?.run { pause(); stop(); clearMediaItems() }
        finish()
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            val c = runCatching { PlayerConnection.controller(this@VideoPlayerActivity) }.getOrNull() ?: return@launch
            controller = c
            c.addListener(listener)
            playerView.player = c
            if (!started) {
                started = true
                val resume = intent.action == ACTION_RESUME
                if (resume) {
                    rebuildFromController(c)
                } else {
                    val items = intentItems()
                    if (items.isNotEmpty()) {
                        val startEntry = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, entries.size - 1)
                        entry = startEntry
                        val media = playlist.mediaIndexOf(startEntry).coerceAtLeast(0)
                        c.setMediaItems(items, media, 0L)
                        c.prepare()
                    }
                    c.repeatMode = if (loopPref()) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    if (isVideoEntry(entry)) c.play() else showPhoto(entry)
                }
                ui.flashName()
            }
            ui.loop = c.repeatMode == Player.REPEAT_MODE_ONE
            ui.speed = c.playbackParameters.speed
            normalSpeed = ui.speed
            ui.muted = c.volume == 0f
            listener.onIsPlayingChanged(c.isPlaying)
            syncUi()
        }
    }

    override fun onStop() {
        val c = controller
        // Background play off (Settings) and not in picture-in-picture: pause when the player leaves the screen.
        if (c != null && !isChangingConfigurations && !isInPipSafe() && !settings.backgroundPlay) c.pause()
        playerView.player = null
        c?.removeListener(listener)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_RESUME) return // back from the notification: same list, keep it
        setIntent(intent)
        started = false
    }

    private fun isVideoEntry(i: Int) = playlist.kinds.getOrNull(i) == true

    private fun intentItems(): List<MediaItem> {
        val list = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty().ifEmpty { listOfNotNull(intent.data?.toString()) }
        val names = intent.getStringArrayListExtra(EXTRA_NAMES).orEmpty()
        val kinds = intent.getBooleanArrayExtra(EXTRA_KINDS)?.toList()?.takeIf { it.size == list.size } ?: list.map { true }
        entries = list.mapIndexed { i, s ->
            val u = Uri.parse(s)
            val name = names.getOrNull(i) ?: u.lastPathSegment ?: "Video"
            if (u.scheme == "file") FileItem.of(File(u.path!!)) else FileItem(u.toString(), name, false, 0, 0)
        }
        playlist = MixedPlaylist(kinds)
        ui.items = entries
        return list.indices.filter { kinds[it] }.map { i ->
            val uri = Uri.parse(list[i])
            val subs = if (uri.scheme == "file") Subtitles.siblings(File(uri.path ?: "")) else emptyList()
            PlaybackService.item(uri, entries[i].name, isVideo = true, subtitles = subs)
        }
    }

    /** Opened from the notification after the screen was gone: rebuild the list from the player. */
    private fun rebuildFromController(c: MediaController) {
        entries = (0 until c.mediaItemCount).map { i ->
            val item = c.getMediaItemAt(i)
            val u = Uri.parse(item.mediaId)
            val name = item.mediaMetadata.title?.toString() ?: u.lastPathSegment ?: "Video"
            if (u.scheme == "file") FileItem.of(File(u.path!!)) else FileItem(u.toString(), name, false, 0, 0)
        }
        playlist = MixedPlaylist(entries.map { true })
        ui.items = entries
        entry = c.currentMediaItemIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
    }

    // ---------------------------------------------------------------- moving between entries

    /** Shows [target] at once (no slide): a video plays from the start, a photo pauses the player. */
    private fun goTo(target: Int) {
        if (target !in entries.indices) return
        val c = controller ?: return
        entry = target
        if (isVideoEntry(target)) {
            ui.photo = null
            c.seekTo(playlist.mediaIndexOf(target), 0L)
            c.prepare()
            c.play()
        } else {
            showPhoto(target)
        }
        ui.flashName()
        syncUi()
    }

    private fun showPhoto(target: Int) {
        controller?.pause()
        ui.photo = entries.getOrNull(target)
        ui.cover = null
    }

    private var pageAnim: ValueAnimator? = null

    private fun setPageOffset(off: Float) {
        ui.pageOffset = off
        playerView.translationX = if (ui.photo == null) off else 0f
    }

    /** Slides the current page by [from]..[to] px, then runs [end]. */
    private fun animatePage(from: Float, to: Float, ms: Long, end: () -> Unit) {
        pageAnim?.cancel()
        pageAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { setPageOffset(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) end() }
            })
            start()
        }
    }

    /** Next (+1) / previous (-1) with the slide; [fromOffset] is where a finger left it. */
    private fun slideTo(direction: Int, fromOffset: Float = 0f) {
        val target = entry + direction
        if (target !in entries.indices) {
            ui.note(if (direction > 0) "Last video" else "First video")
            animatePage(fromOffset, 0f, 180) { ui.incoming = null }
            return
        }
        if (ui.pip) { goTo(target); return }
        val width = playerView.width.toFloat().coerceAtLeast(1f)
        ui.incoming = entries[target]
        // Keep the incoming page on the right side even when the drag started at 0.
        if (fromOffset == 0f) setPageOffset(-direction * 0.001f)
        animatePage(ui.pageOffset, -direction * width, 260) {
            val next = entries[target]
            if (isVideoEntry(target)) ui.cover = next // the still stays until the first frame is drawn
            ui.incoming = null
            setPageOffset(0f)
            goTo(target)
            if (ui.cover != null) playerView.postDelayed({ ui.cover = null }, 900) // safety net
        }
    }

    /** The nearest video after (+1) / before (-1) the current entry (picture-in-picture skips photos). */
    private fun videoEntry(direction: Int): Int? {
        var i = entry + direction
        while (i in entries.indices) { if (isVideoEntry(i)) return i; i += direction }
        return null
    }

    private fun neighbourFor(dx: Float): FileItem? = entries.getOrNull(if (dx < 0) entry + 1 else entry - 1)

    // ---------------------------------------------------------------- gestures

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        var longPress = false
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                ui.controls = !ui.controls
                ui.touchStamp++
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val c = controller ?: return true
                if (ui.photo != null) return true
                val forward = e.x > playerView.width / 2f
                c.seekTo((c.currentPosition + if (forward) 10_000 else -10_000).coerceAtLeast(0))
                ui.seekSide = if (forward) 1 else -1
                ui.seekAmount = 10
                ui.seekStamp++
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val c = controller ?: return
                if (ui.photo != null || ui.scrubbing) return
                longPress = true
                normalSpeed = c.playbackParameters.speed.takeIf { it > 0f && it != 2f } ?: 1f
                c.setPlaybackSpeed(2f)
                if (!c.isPlaying) c.play()
                ui.fast = true
                playerView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }

            // Drags are handled by the root VideoGestureLayout (ViewerGestures.kt) with a direction lock.
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean = longPress
        })
        playerView.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (longPress) {
                    longPress = false
                    controller?.setPlaybackSpeed(normalSpeed)
                    ui.fast = false
                }
            }
            true
        }
    }

    private val pill = LevelPillState()
    private val volume by lazy { MediaVolume(this) }

    // Timeline scrub state (side swipe or seek bar).
    private var scrubWasPlaying = false
    private var lastScrubSeek = 0L
    private var sliderActive = false

    private fun beginScrub(): Boolean {
        val c = controller ?: return false
        if (ui.photo != null) return false
        scrubWasPlaying = c.isPlaying || c.playWhenReady
        c.pause()
        ui.scrubStartMs = c.currentPosition.coerceAtLeast(0L)
        ui.scrubMs = ui.scrubStartMs
        lastScrubSeek = 0L
        return true
    }

    private fun scrubTo(ms: Long) {
        val c = controller ?: return
        ui.scrubMs = ms
        ui.position = ms
        val now = SystemClock.uptimeMillis()
        if (now - lastScrubSeek >= VideoGestureMath.SCRUB_SEEK_EVERY_MS) {
            lastScrubSeek = now
            c.seekTo(ms) // the video itself is the preview
        }
    }

    private fun endScrub(finalMs: Long) {
        val c = controller ?: return
        c.seekTo(finalMs)
        ui.position = finalMs
        if (scrubWasPlaying) c.play()
    }

    private val swipeCallbacks = object : VideoGestureLayout.Callbacks {
        override fun blocked(): Boolean = ui.fast || ui.info != null || ui.pip
        override fun overControls(x: Float, y: Float): Boolean = ui.isOverControls(x, y)
        override fun horizontalFor(zone: Zone): HorizontalAction =
            if (ui.photo != null) HorizontalAction.PAGE else VideoGestureMath.horizontalAction(zone)
        override fun brightness(): Float = WindowBrightness.current(this@VideoPlayerActivity)
        override fun volume(): Float = volume.level()
        override fun onBrightness(level: Float) {
            WindowBrightness.set(this@VideoPlayerActivity, level)
            pill.show(PillKind.BRIGHTNESS, level)
        }
        override fun onVolume(level: Float) {
            pill.show(PillKind.VOLUME, volume.set(level))
        }
        override fun onInfo() = showInfo()
        override fun onDismissDrag(offsetPx: Float) {
            val p = ViewerGestureMath.dismissProgress(offsetPx, ViewerGestureMath.DISMISS_DP * resources.displayMetrics.density)
            playerView.translationY = offsetPx
            playerView.scaleX = 1f - 0.15f * p
            playerView.scaleY = 1f - 0.15f * p
            playerView.alpha = 1f - 0.5f * p
        }
        override fun onDismissEnd(offsetPx: Float, close: Boolean) {
            if (close) {
                closePlayer()
            } else {
                playerView.animate().translationY(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start()
            }
        }

        override fun onScrubStart() {
            ui.scrubbing = beginScrub()
        }
        override fun onScrub(dx: Float) {
            if (!ui.scrubbing) return
            scrubTo(VideoGestureMath.scrubTarget(ui.scrubStartMs, dx, playerView.width.toFloat(), ui.duration))
        }
        override fun onScrubEnd(cancelled: Boolean) {
            if (!ui.scrubbing) return
            endScrub(if (cancelled) ui.scrubStartMs else ui.scrubMs)
            ui.scrubbing = false
        }

        override fun onPageDrag(dx: Float) {
            pageAnim?.cancel()
            val hasPrev = entry > 0
            val hasNext = entry < entries.size - 1
            ui.incoming = neighbourFor(dx)
            setPageOffset(VideoGestureMath.pageOffset(dx, hasPrev, hasNext))
        }
        override fun onPageEnd(dx: Float, velocityPxS: Float) {
            val d = VideoGestureMath.pageDecision(
                dx, playerView.width.toFloat(), velocityPxS, resources.displayMetrics.density,
                hasPrev = entry > 0, hasNext = entry < entries.size - 1,
            )
            if (d == 0) {
                if (dx != 0f && neighbourFor(dx) == null && kotlin.math.abs(dx) > playerView.width * VideoGestureMath.PAGE_THRESHOLD) {
                    ui.note(if (dx < 0) "Last video" else "First video")
                }
                animatePage(ui.pageOffset, 0f, 200) { ui.incoming = null }
            } else {
                slideTo(d, ui.pageOffset)
            }
        }
    }

    override fun onDestroy() {
        WindowBrightness.reset(this) // this window only; the system value comes back
        pageAnim?.cancel()
        runCatching { unregisterReceiver(pipReceiver) }
        super.onDestroy()
    }

    private fun syncUi() {
        ui.index = entry
        ui.title = entries.getOrNull(entry)?.name.orEmpty()
        ui.hasPrev = entry > 0
        ui.hasNext = entry < entries.size - 1
        updatePip()
    }

    private fun currentPath(): String? = currentUri()?.takeIf { it.scheme == "file" }?.path

    private val prefs by lazy { getSharedPreferences("video", MODE_PRIVATE) }

    /** Loop: remembered once chosen; before that, on for a single video and off for a list. */
    private fun loopPref(): Boolean = if (prefs.contains("loop")) prefs.getBoolean("loop", false) else playlist.videoCount <= 1

    private val videoActions = object : VideoActions {
        override fun back() = closePlayer()
        override fun info() = showInfo()
        override fun rotate() = this@VideoPlayerActivity.rotate()
        override fun autoRotate() = setUserOrientation(null)
        override fun toggleFavorite() { currentPath()?.let { com.clpstudio.filesmanager.data.Favorites.toggle(it) } }
        override fun share() { currentUri()?.let { shareCurrent(it) } }
        override fun openWith() { currentUri()?.let { openWithOther(it) } }
        override fun edit() {
            val uri = currentUri() ?: return
            val u = if (uri.scheme == "file") com.clpstudio.filesmanager.data.FileOps.uriFor(this@VideoPlayerActivity, File(uri.path!!)) else uri
            val edit = Intent(Intent.ACTION_EDIT).setDataAndType(u, if (ui.photo != null) "image/*" else "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            try {
                startActivity(Intent.createChooser(edit, "Edit with"))
            } catch (e: Exception) {
                openWithOther(uri)
            }
        }
        override fun hide() {
            val path = currentPath() ?: return
            controller?.pause()
            lifecycleScope.launch {
                val hidden = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.clpstudio.filesmanager.data.HideFile.hide(this@VideoPlayerActivity, File(path))
                }
                if (hidden == null) {
                    android.widget.Toast.makeText(this@VideoPlayerActivity, "Couldn't hide this file", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this@VideoPlayerActivity, "Hidden. Turn on Show hidden files to see it.", android.widget.Toast.LENGTH_LONG).show()
                    com.clpstudio.filesmanager.ui.AppState.refresh()
                    removeCurrent()
                }
            }
        }
        override fun delete() {
            val path = currentPath() ?: return
            controller?.pause()
            android.app.AlertDialog.Builder(this@VideoPlayerActivity)
                .setTitle("Move to Bin?")
                .setMessage(entries.getOrNull(entry)?.name.orEmpty())
                .setPositiveButton("Move to Bin") { _, _ ->
                    lifecycleScope.launch {
                        val n = com.clpstudio.filesmanager.Graph.bin.moveToBin(listOf(File(path)))
                        if (n > 0) {
                            com.clpstudio.filesmanager.data.Favorites.onDeleted(listOf(path))
                            com.clpstudio.filesmanager.ui.AppState.refresh()
                            removeCurrent()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        override fun setSpeed(speed: Float) {
            controller?.setPlaybackSpeed(speed)
            normalSpeed = speed
            ui.speed = speed
        }
        override fun setLoop(on: Boolean) {
            prefs.edit().putBoolean("loop", on).apply()
            controller?.repeatMode = if (on) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            ui.loop = on
            ui.note(if (on) "Loop on" else "Loop off")
        }
        override fun toggleMute() {
            val c = controller ?: return
            val mute = c.volume > 0f
            c.volume = if (mute) 0f else 1f
            ui.muted = mute
        }
        override fun jumpTo(index: Int) {
            if (index == entry) return
            val d = index - entry
            if (kotlin.math.abs(d) == 1) slideTo(d) else goTo(index)
        }
        override fun playPause() {
            val c = controller ?: return
            if (c.isPlaying) c.pause() else {
                if (c.playbackState == Player.STATE_ENDED) c.seekTo(0L)
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                c.play()
            }
        }
        override fun next() = slideTo(1)
        override fun previous() {
            // Like most players: past 3 s, "previous" goes back to the start first.
            val c = controller
            if (c != null && ui.photo == null && c.currentPosition > 3_000) { c.seekTo(0L); return }
            slideTo(-1)
        }
        override fun seekBar(ms: Long, done: Boolean) {
            if (!sliderActive) {
                if (!beginScrub()) return
                sliderActive = true
            }
            if (done) {
                endScrub(ms)
                sliderActive = false
            } else {
                scrubTo(ms)
            }
        }
        override fun pip() = enterPip()
    }

    /** Drops the current entry (after delete / hide); closes when none is left. */
    private fun removeCurrent() {
        val c = controller ?: return
        val i = entry
        if (entries.size <= 1) {
            c.run { stop(); clearMediaItems() }
            finish()
            return
        }
        val media = playlist.mediaIndexOf(i)
        entries = entries.filterIndexed { k, _ -> k != i }
        playlist = MixedPlaylist(playlist.kinds.filterIndexed { k, _ -> k != i })
        ui.items = entries
        if (media >= 0) c.removeMediaItem(media)
        // Show the next entry, or the previous one when the removed entry was the last.
        goTo(i.coerceAtMost(entries.size - 1))
    }

    private fun currentUri(): Uri? = entries.getOrNull(entry)?.let { e ->
        if (e.path.startsWith("content://") || e.path.startsWith("file://")) Uri.parse(e.path) else Uri.fromFile(File(e.path))
    }

    private fun showInfo() {
        val uri = currentUri() ?: return
        val name = entries.getOrNull(entry)?.name ?: uri.lastPathSegment ?: "Video"
        ui.info = InfoTarget(uri, name, uri.takeIf { it.scheme == "file" }?.path)
    }

    /** Rotate button: landscape <-> portrait (locked until "Auto-rotate"). */
    private fun rotate() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        setUserOrientation(if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
    }

    /** null = follow the phone's sensor (when auto-rotate is on in the phone's settings). */
    private fun setUserOrientation(o: Int?) {
        userOrientation = o
        ui.orientationLocked = o != null
        requestedOrientation = o ?: ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    }

    private fun shareCurrent(uri: Uri) {
        val shareUri = if (uri.scheme == "file") com.clpstudio.filesmanager.data.FileOps.uriFor(this, File(uri.path!!)) else uri
        val type = if (ui.photo != null) "image/*" else "video/*"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, shareUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
    }

    private fun openWithOther(uri: Uri) {
        val u = if (uri.scheme == "file") com.clpstudio.filesmanager.data.FileOps.uriFor(this, File(uri.path!!)) else uri
        com.clpstudio.filesmanager.data.FileOps.openWithOtherApp(this, u, if (ui.photo != null) "image/*" else "video/*")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    // ---------------------------------------------------------------- picture-in-picture

    private fun isInPipSafe(): Boolean = runCatching { isInPictureInPictureMode }.getOrDefault(false)

    private fun pipAction(icon: Int, title: String, cmd: Int, enabled: Boolean = true): RemoteAction {
        val pi = PendingIntent.getBroadcast(
            this, cmd, Intent(ACTION_PIP).setPackage(packageName).putExtra(EXTRA_CMD, cmd),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteAction(Icon.createWithResource(this, icon), title, title, pi).apply { isEnabled = enabled }
    }

    private fun pipParams(): PictureInPictureParams? {
        if (!ui.pipSupported) return null
        return runCatching {
            val (w, h) = VideoGestureMath.pipAspect(videoW, videoH)
            val playing = controller?.isPlaying == true
            val b = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(w, h))
                .setActions(
                    listOf(
                        pipAction(androidx.media3.ui.R.drawable.exo_icon_previous, "Previous", CMD_PREV, videoEntry(-1) != null),
                        if (playing) pipAction(androidx.media3.ui.R.drawable.exo_icon_pause, "Pause", CMD_PLAY_PAUSE)
                        else pipAction(androidx.media3.ui.R.drawable.exo_icon_play, "Play", CMD_PLAY_PAUSE),
                        pipAction(androidx.media3.ui.R.drawable.exo_icon_next, "Next", CMD_NEXT, videoEntry(1) != null),
                    ),
                )
            val r = Rect()
            if (playerView.getGlobalVisibleRect(r) && !r.isEmpty) b.setSourceRectHint(r)
            if (Build.VERSION.SDK_INT >= 31) {
                b.setAutoEnterEnabled(settings.autoPip && playing && ui.photo == null)
                b.setSeamlessResizeEnabled(true)
            }
            b.build()
        }.getOrNull()
    }

    private fun updatePip() {
        if (!ui.pipSupported) return
        pipParams()?.let { runCatching { setPictureInPictureParams(it) } }
    }

    private fun enterPip() {
        if (!ui.pipSupported) {
            ui.note("Picture-in-picture isn't available on this device")
            return
        }
        val p = pipParams() ?: return
        ui.controls = false
        runCatching { enterPictureInPictureMode(p) }.onFailure { ui.note("Turn on picture-in-picture for Files in Android settings") }
    }

    /** Android 8–11: Home while playing goes to picture-in-picture (12+ does it smoothly by itself). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && settings.autoPip && controller?.isPlaying == true && ui.photo == null) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        ui.pip = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            ui.controls = false
            pageAnim?.cancel(); setPageOffset(0f); ui.incoming = null
        } else if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            // The small window was closed (✕ or dragged away): stop, like YouTube.
            closePlayer()
        }
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(EXTRA_CMD, 0)) {
                CMD_PLAY_PAUSE -> videoActions.playPause()
                CMD_NEXT -> videoEntry(1)?.let { goTo(it) }
                CMD_PREV -> videoEntry(-1)?.let { goTo(it) }
            }
            updatePip()
        }
    }

    companion object {
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_NAMES = "names"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_KINDS = "kinds"
        private const val ACTION_PIP = "com.clpstudio.filesmanager.PIP_CONTROL"
        const val ACTION_RESUME = "com.clpstudio.filesmanager.VIDEO_RESUME"
        private const val EXTRA_CMD = "cmd"
        private const val CMD_PLAY_PAUSE = 1
        private const val CMD_NEXT = 2
        private const val CMD_PREV = 3

        /** [files] may mix photos and videos (Media): the player shows the photos in place and plays the videos. */
        fun startFiles(context: Context, files: List<FileItem>, index: Int) {
            context.startActivity(
                Intent(context, VideoPlayerActivity::class.java)
                    .putStringArrayListExtra(EXTRA_URIS, ArrayList(files.map { Uri.fromFile(File(it.path)).toString() }))
                    .putStringArrayListExtra(EXTRA_NAMES, ArrayList(files.map { it.name }))
                    .putExtra(EXTRA_KINDS, files.map { it.kind == FileKind.VIDEO }.toBooleanArray())
                    .putExtra(EXTRA_INDEX, index),
            )
        }

        /** A video from another app (content:// with a read grant). */
        fun startUri(context: Context, uri: Uri, name: String) {
            context.startActivity(
                Intent(context, VideoPlayerActivity::class.java)
                    .setData(uri)
                    .putStringArrayListExtra(EXTRA_NAMES, arrayListOf(name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }

        /** The media notification's tap target while a video plays: back to the player as it is. */
        fun resumeIntent(context: Context): Intent =
            Intent(context, VideoPlayerActivity::class.java).setAction(ACTION_RESUME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
