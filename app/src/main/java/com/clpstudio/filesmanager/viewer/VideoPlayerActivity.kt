package com.clpstudio.filesmanager.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.playback.PlaybackService
import com.clpstudio.filesmanager.playback.PlayerConnection
import com.clpstudio.filesmanager.playback.Subtitles
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * Video player. The player lives in [PlaybackService]: leaving the app or locking the phone keeps
 * the sound playing (with a notification); the screen stays on while a video plays.
 * Gestures: double-tap left/right = -/+10 s, swipe up/down on the left = brightness, right = volume,
 * swipe up in the middle = file info, swipe left/right = next / previous video,
 * long press = 2x speed while held ("2x ▶▶" pill). Rotate button: landscape / portrait / auto.
 */
@OptIn(UnstableApi::class)
class VideoPlayerActivity : ComponentActivity() {
    private lateinit var playerView: PlayerView
    private lateinit var hud: TextView
    private var controller: MediaController? = null
    private var started = false
    private val ui = VideoOverlayState()
    private var uris: List<Uri> = emptyList()
    private var names: List<String> = emptyList()
    /** Before the user rotates by hand, the orientation follows the video's shape. */
    private var userOrientation: Int? = null
    private var normalSpeed = 1f

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = syncUi()

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (userOrientation == null && videoSize.width > 0 && videoSize.height > 0) {
                val w = videoSize.width * videoSize.pixelWidthHeightRatio
                requestedOrientation = if (w > videoSize.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.clpstudio.filesmanager.ui.PrivacyGuard.install(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowSubtitleButton(true)
            setShowNextButton(true)
            setShowPreviousButton(true)
            controllerShowTimeoutMs = 3500
            keepScreenOn = false
        }
        hud = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            setPadding(48, 24, 48, 24)
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
        }
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> ui.controls = v == View.VISIBLE })
        val compose = ComposeView(this).apply {
            setContent {
                FilesTheme(ThemeMode.DARK, liquidGlass = false) {
                    val favs by com.clpstudio.filesmanager.data.Favorites.paths.collectAsState()
                    ui.favorite = currentPath()?.let { it in favs } == true
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                        VideoOverlay(
                            state = ui,
                            actions = videoActions,
                            sheetActions = videoSheetActions(
                                onShare = { currentUri()?.let { shareCurrent(it) } },
                                onOpenWith = { currentUri()?.let { openWithOther(it) } },
                                onFolder = null,
                            ),
                        )
                        LevelPillHost(pill)
                    }
                }
            }
        }
        val root = VideoGestureLayout(this).apply {
            callbacks = swipeCallbacks
            addView(playerView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(hud, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
        setupGestures()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back = close the video (stops it). Home / lock screen = keeps playing in the background.
                controller?.run { pause(); stop(); clearMediaItems() }
                finish()
            }
        })
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
                val items = intentItems()
                if (items.isNotEmpty()) {
                    c.setMediaItems(items, intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, items.size - 1), 0L)
                    c.prepare()
                }
            }
            c.repeatMode = if (loopPref()) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            ui.loop = c.repeatMode == Player.REPEAT_MODE_ONE
            ui.speed = c.playbackParameters.speed
            normalSpeed = ui.speed
            ui.muted = c.volume == 0f
            c.play()
            listener.onIsPlayingChanged(c.isPlaying)
            syncUi()
        }
    }

    override fun onStop() {
        // Detach the screen but keep the player (sound goes on in the background).
        playerView.player = null
        controller?.removeListener(listener)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        started = false
    }

    private fun intentItems(): List<MediaItem> {
        val uris = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty()
        val names = intent.getStringArrayListExtra(EXTRA_NAMES).orEmpty()
        val list = if (uris.isNotEmpty()) uris else listOfNotNull(intent.data?.toString())
        this.uris = list.map { Uri.parse(it) }
        this.names = list.indices.map { i -> names.getOrNull(i) ?: Uri.parse(list[i]).lastPathSegment ?: "Video" }
        ui.items = this.uris.mapIndexed { i, u ->
            if (u.scheme == "file") FileItem.of(File(u.path!!)) else FileItem(u.toString(), this.names[i], false, 0, 0)
        }
        return list.mapIndexed { i, s ->
            val uri = Uri.parse(s)
            val subs = if (uri.scheme == "file") Subtitles.siblings(File(uri.path ?: "")) else emptyList()
            PlaybackService.item(uri, names.getOrNull(i) ?: uri.lastPathSegment ?: "Video", isVideo = true, subtitles = subs)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        var longPress = false
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (playerView.isControllerFullyVisible) playerView.hideController() else playerView.showController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val c = controller ?: return true
                val forward = e.x > playerView.width / 2f
                c.seekTo((c.currentPosition + if (forward) 10_000 else -10_000).coerceAtLeast(0))
                show(if (forward) "+10 s" else "-10 s")
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val c = controller ?: return
                longPress = true
                normalSpeed = c.playbackParameters.speed.takeIf { it > 0f && it != 2f } ?: 1f
                c.setPlaybackSpeed(2f)
                if (!c.isPlaying) c.play()
                ui.fast = true
                playerView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }

            // Vertical (brightness / volume / info / close) and sideways drags are handled by the root
            // VideoGestureLayout (ViewerGestures.kt) with a direction lock; the player keeps taps and long-press.
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

    /** Swipe zones: left = brightness, right = volume, middle up = info, middle down = close, sideways = next / previous. */
    private val swipeCallbacks = object : VideoGestureLayout.Callbacks {
        override fun blocked(): Boolean = ui.fast || ui.info != null
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
                onBackPressedDispatcher.onBackPressed()
            } else {
                playerView.animate().translationY(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start()
            }
        }
        override fun onHorizontalEnd(dx: Float) {
            if (abs(dx) <= playerView.width * 0.18f) return
            val c = controller ?: return
            if (dx < 0) {
                if (c.hasNextMediaItem()) { c.seekToNextMediaItem(); show("Next: " + (names.getOrNull(c.currentMediaItemIndex) ?: "")) } else show("Last video")
            } else {
                if (c.hasPreviousMediaItem()) { c.seekToPreviousMediaItem(); show("Previous: " + (names.getOrNull(c.currentMediaItemIndex) ?: "")) } else show("First video")
            }
        }
    }

    override fun onDestroy() {
        WindowBrightness.reset(this) // this window only; the system value comes back
        super.onDestroy()
    }

    private fun syncUi() {
        val i = currentIndex()
        ui.index = i
        ui.title = names.getOrNull(i) ?: ""
    }

    private fun currentPath(): String? = currentUri()?.takeIf { it.scheme == "file" }?.path

    private val prefs by lazy { getSharedPreferences("video", MODE_PRIVATE) }

    /** Loop: remembered once chosen; before that, on for a single video and off for a list. */
    private fun loopPref(): Boolean = if (prefs.contains("loop")) prefs.getBoolean("loop", false) else uris.size <= 1

    private val videoActions = object : VideoActions {
        override fun back() = onBackPressedDispatcher.onBackPressed()
        override fun info() = showInfo()
        override fun rotate() = this@VideoPlayerActivity.rotate()
        override fun autoRotate() = setUserOrientation(null)
        override fun toggleFavorite() { currentPath()?.let { com.clpstudio.filesmanager.data.Favorites.toggle(it) } }
        override fun share() { currentUri()?.let { shareCurrent(it) } }
        override fun openWith() { currentUri()?.let { openWithOther(it) } }
        override fun edit() {
            val uri = currentUri() ?: return
            val u = if (uri.scheme == "file") com.clpstudio.filesmanager.data.FileOps.uriFor(this@VideoPlayerActivity, File(uri.path!!)) else uri
            val edit = Intent(Intent.ACTION_EDIT).setDataAndType(u, "video/*")
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
                    android.widget.Toast.makeText(this@VideoPlayerActivity, "Couldn't hide this video", android.widget.Toast.LENGTH_SHORT).show()
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
                .setMessage(names.getOrNull(currentIndex()) ?: "")
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
            show(if (on) "Loop on" else "Loop off")
        }
        override fun toggleMute() {
            val c = controller ?: return
            val mute = c.volume > 0f
            c.volume = if (mute) 0f else 1f
            ui.muted = mute
        }
        override fun jumpTo(index: Int) {
            controller?.seekTo(index, 0L)
            controller?.play()
        }
    }

    /** Drops the current video from the playlist (after delete / hide); closes when none is left. */
    private fun removeCurrent() {
        val c = controller ?: return
        val i = currentIndex()
        if (uris.size <= 1) {
            c.run { stop(); clearMediaItems() }
            finish()
            return
        }
        uris = uris.filterIndexed { k, _ -> k != i }
        names = names.filterIndexed { k, _ -> k != i }
        ui.items = ui.items.filterIndexed { k, _ -> k != i }
        val wasLast = i >= c.mediaItemCount - 1
        c.removeMediaItem(i)
        // Show the next video, or the previous one when the deleted video was the last.
        if (wasLast && c.mediaItemCount > 0) c.seekTo(c.mediaItemCount - 1, 0L)
        c.prepare()
        c.play()
        syncUi()
    }

    private fun currentIndex(): Int = controller?.currentMediaItemIndex?.coerceIn(0, (uris.size - 1).coerceAtLeast(0)) ?: 0

    private fun currentUri(): Uri? = uris.getOrNull(currentIndex())

    private fun showInfo() {
        val uri = currentUri() ?: return
        val name = names.getOrNull(currentIndex()) ?: uri.lastPathSegment ?: "Video"
        ui.info = InfoTarget(uri, name, uri.takeIf { it.scheme == "file" }?.path)
    }

    /** Rotate button: landscape <-> portrait (locked until "Auto-rotate"). */
    private fun rotate() {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
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
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/*").putExtra(Intent.EXTRA_STREAM, shareUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
    }

    private fun openWithOther(uri: Uri) {
        val u = if (uri.scheme == "file") com.clpstudio.filesmanager.data.FileOps.uriFor(this, File(uri.path!!)) else uri
        com.clpstudio.filesmanager.data.FileOps.openWithOtherApp(this, u, "video/*")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    private val hideOverlay = Runnable { hud.visibility = View.GONE }

    private fun show(text: String) {
        hud.text = text
        hud.visibility = View.VISIBLE
        hud.removeCallbacks(hideOverlay)
        hud.postDelayed(hideOverlay, 700)
    }

    companion object {
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_NAMES = "names"
        private const val EXTRA_INDEX = "index"

        fun startFiles(context: Context, files: List<FileItem>, index: Int) {
            context.startActivity(
                Intent(context, VideoPlayerActivity::class.java)
                    .putStringArrayListExtra(EXTRA_URIS, ArrayList(files.map { Uri.fromFile(File(it.path)).toString() }))
                    .putStringArrayListExtra(EXTRA_NAMES, ArrayList(files.map { it.name }))
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
    }
}
