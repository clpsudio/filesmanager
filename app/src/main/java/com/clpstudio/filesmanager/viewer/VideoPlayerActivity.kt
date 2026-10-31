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
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
 * Gestures: double-tap left/right = -/+10 s, swipe up/down on the left = brightness, right = volume.
 */
@OptIn(UnstableApi::class)
class VideoPlayerActivity : ComponentActivity() {
    private lateinit var playerView: PlayerView
    private lateinit var hud: TextView
    private var controller: MediaController? = null
    private var started = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                val w = videoSize.width * videoSize.pixelWidthHeightRatio
                requestedOrientation = if (w > videoSize.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val root = FrameLayout(this).apply {
            addView(playerView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(hud, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
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
            c.play()
            listener.onIsPlayingChanged(c.isPlaying)
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
        return list.mapIndexed { i, s ->
            val uri = Uri.parse(s)
            val subs = if (uri.scheme == "file") Subtitles.siblings(File(uri.path ?: "")) else emptyList()
            PlaybackService.item(uri, names.getOrNull(i) ?: uri.lastPathSegment ?: "Video", isVideo = true, subtitles = subs)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var mode = 0 // 0 none, 1 brightness, 2 volume
        var startY = 0f
        var startValue = 0f
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                mode = 0
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                playerView.performClick()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val c = controller ?: return true
                val forward = e.x > playerView.width / 2f
                c.seekTo((c.currentPosition + if (forward) 10_000 else -10_000).coerceAtLeast(0))
                show(if (forward) "+10 s" else "-10 s")
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val start = e1 ?: return false
                if (mode == 0) {
                    if (abs(e2.y - start.y) < 24 || abs(e2.x - start.x) > abs(e2.y - start.y)) return false
                    mode = if (start.x < playerView.width / 2f) 1 else 2
                    startY = e2.y
                    startValue = if (mode == 1) {
                        val b = window.attributes.screenBrightness
                        if (b < 0) 0.5f else b
                    } else {
                        audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    }
                }
                val delta = (startY - e2.y) / (playerView.height * 0.8f)
                val v = (startValue + delta).coerceIn(0.01f, 1f)
                if (mode == 1) {
                    window.attributes = window.attributes.apply { screenBrightness = v }
                    show("Brightness ${(v * 100).toInt()}%")
                } else {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).toInt(), 0)
                    show("Volume ${(v * 100).toInt()}%")
                }
                return true
            }
        })
        playerView.setOnTouchListener { _, ev -> detector.onTouchEvent(ev); true }
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
