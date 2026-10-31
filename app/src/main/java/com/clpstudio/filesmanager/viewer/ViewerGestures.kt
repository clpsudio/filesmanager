package com.clpstudio.filesmanager.viewer

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Viewer swipe zones (Charan, Oct 31):
 *  - Video: left third = brightness, right third = media volume (both ways while dragging),
 *    middle third: up = file info, down = close.
 *  - Photo (only at 1x): left third = brightness; middle and right thirds: up = info, down = close.
 *  - Sideways stays with the pager / next video. A direction lock decides vertical vs horizontal
 *    after [ViewerGestureMath.LOCK_DP] of movement so the gestures never fight.
 */

enum class ViewerKind { PHOTO, VIDEO }
enum class Zone { LEFT, MIDDLE, RIGHT }
enum class Axis { NONE, HORIZONTAL, VERTICAL }
enum class SwipeAction { NONE, BRIGHTNESS, VOLUME, INFO, DISMISS }

/** The pure part of the viewer gestures (unit tested). */
object ViewerGestureMath {
    /** Movement before the direction is decided. */
    const val LOCK_DP = 16f
    /** A swipe up this long opens the info sheet. */
    const val INFO_DP = 72f
    /** A swipe down this long closes the viewer on release. */
    const val DISMISS_DP = 120f
    /** Dragging this share of the view's height moves brightness / volume from 0 to 100 %. */
    const val FULL_RANGE = 0.6f
    const val MIN_BRIGHTNESS = 0.01f

    fun zoneOf(x: Float, width: Float): Zone {
        if (width <= 0f) return Zone.MIDDLE
        val f = x / width
        return when {
            f < 1f / 3f -> Zone.LEFT
            f > 2f / 3f -> Zone.RIGHT
            else -> Zone.MIDDLE
        }
    }

    /**
     * Direction lock. NONE until the finger moved [lockPx]; then VERTICAL when the drag is mostly up/down,
     * HORIZONTAL otherwise. A drag that already went more than [crossSlopPx] sideways belongs to the pager
     * (it starts at its own touch slop), so it locks HORIZONTAL even if it later turns vertical.
     */
    fun lockDirection(dx: Float, dy: Float, lockPx: Float, crossSlopPx: Float = Float.MAX_VALUE): Axis {
        if (dx * dx + dy * dy < lockPx * lockPx) return Axis.NONE
        if (abs(dx) >= crossSlopPx) return Axis.HORIZONTAL
        return if (abs(dy) > abs(dx)) Axis.VERTICAL else Axis.HORIZONTAL
    }

    /** What a vertical drag starting in [zone] does; [dy] < 0 is up. */
    fun actionFor(kind: ViewerKind, zone: Zone, dy: Float): SwipeAction = when (kind) {
        ViewerKind.VIDEO -> when (zone) {
            Zone.LEFT -> SwipeAction.BRIGHTNESS
            Zone.RIGHT -> SwipeAction.VOLUME
            Zone.MIDDLE -> if (dy < 0) SwipeAction.INFO else SwipeAction.DISMISS
        }
        ViewerKind.PHOTO -> when (zone) {
            Zone.LEFT -> SwipeAction.BRIGHTNESS
            Zone.MIDDLE, Zone.RIGHT -> if (dy < 0) SwipeAction.INFO else SwipeAction.DISMISS
        }
    }

    /**
     * Level after dragging: up (negative [dy]) increases, down decreases, linearly, both ways in one drag.
     * [height] * [FULL_RANGE] of movement covers the whole range.
     */
    fun levelFromDrag(start: Float, dy: Float, height: Float, min: Float = 0f): Float {
        if (height <= 0f) return start.coerceIn(min, 1f)
        return (start - dy / (height * FULL_RANGE)).coerceIn(min, 1f)
    }

    /** Volume step for a 0..1 level (AudioManager steps 0..[max]). */
    fun volumeStep(level: Float, max: Int): Int = if (max <= 0) 0 else (level.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max)

    fun volumeLevel(step: Int, max: Int): Float = if (max <= 0) 0f else (step.toFloat() / max).coerceIn(0f, 1f)

    /** Whole percent shown in the pill. */
    fun percent(level: Float): Int = (level.coerceIn(0f, 1f) * 100f).roundToInt()

    /** System brightness setting (0..255) to a window level. */
    fun brightnessFromSystem(value: Int, max: Int = 255): Float = (value.toFloat() / max.coerceAtLeast(1)).coerceIn(MIN_BRIGHTNESS, 1f)

    /** Drag-to-dismiss progress 0..1 for the background fade and the scale. */
    fun dismissProgress(offsetPx: Float, dismissPx: Float): Float = if (dismissPx <= 0f) 0f else (offsetPx / (dismissPx * 2.5f)).coerceIn(0f, 1f)
}

/** Brightness of this window only; reset hands it back to the system. */
object WindowBrightness {
    fun current(activity: Activity?): Float {
        val w = activity?.window ?: return 0.5f
        val v = w.attributes.screenBrightness
        if (v >= 0f) return v.coerceIn(ViewerGestureMath.MIN_BRIGHTNESS, 1f)
        val sys = runCatching { Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        return ViewerGestureMath.brightnessFromSystem(sys)
    }

    fun set(activity: Activity?, level: Float) {
        val w = activity?.window ?: return
        w.attributes = w.attributes.apply { screenBrightness = level.coerceIn(ViewerGestureMath.MIN_BRIGHTNESS, 1f) }
    }

    fun reset(activity: Activity?) {
        val w = activity?.window ?: return
        if (w.attributes.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }
}

/** STREAM_MUSIC without the system volume popup. */
class MediaVolume(context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val max: Int get() = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(15)
    fun level(): Float = ViewerGestureMath.volumeLevel(runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0), max)
    /** Sets the nearest step; returns the level actually set. */
    fun set(level: Float): Float {
        val m = max
        val step = ViewerGestureMath.volumeStep(level, m)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0) } // flags 0 = no system UI
        return ViewerGestureMath.volumeLevel(step, m)
    }
}

enum class PillKind { BRIGHTNESS, VOLUME }

@Stable
class LevelPillState {
    var kind by mutableStateOf(PillKind.BRIGHTNESS)
    var level by mutableFloatStateOf(0f)
    var visible by mutableStateOf(false)
    internal var stamp by mutableIntStateOf(0)

    fun show(kind: PillKind, level: Float) {
        this.kind = kind
        this.level = level.coerceIn(0f, 1f)
        visible = true
        stamp++
    }
}

/** Shows [state]'s pill: brightness on the left side, volume on the right; fades out shortly after the last change. */
@Composable
fun BoxScope.LevelPillHost(state: LevelPillState) {
    LaunchedEffect(state.stamp) {
        if (state.visible) {
            delay(900)
            state.visible = false
        }
    }
    AnimatedVisibility(
        state.visible,
        enter = fadeIn(), exit = fadeOut(),
        modifier = Modifier.align(if (state.kind == PillKind.BRIGHTNESS) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 28.dp),
    ) {
        LevelPill(state.kind, state.level)
    }
}

/** A dark capsule: icon, a vertical level bar and the percent. */
@Composable
fun LevelPill(kind: PillKind, level: Float, modifier: Modifier = Modifier) {
    val pct = ViewerGestureMath.percent(level)
    val icon = when (kind) {
        PillKind.BRIGHTNESS -> when {
            pct < 34 -> Icons.Filled.BrightnessLow
            pct < 67 -> Icons.Filled.BrightnessMedium
            else -> Icons.Filled.BrightnessHigh
        }
        PillKind.VOLUME -> when {
            pct == 0 -> Icons.AutoMirrored.Filled.VolumeOff
            pct < 50 -> Icons.AutoMirrored.Filled.VolumeDown
            else -> Icons.AutoMirrored.Filled.VolumeUp
        }
    }
    val label = if (kind == PillKind.BRIGHTNESS) "Brightness" else "Volume"
    Column(
        modifier
            .width(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xCC111318))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(24.dp))
        Box(
            Modifier.width(8.dp).height(132.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.22f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(level.coerceIn(0f, 1f)).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary))
        }
        Text("$pct%", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** Gives the screen brightness back to the system when the viewer leaves. */
@Composable
fun ResetBrightnessOnExit() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        onDispose { WindowBrightness.reset(context.findActivity()) }
    }
}

/** Drag-to-dismiss offset (px, downwards). */
@Stable
class DismissDragState {
    val offset = Animatable(0f)
    val value: Float get() = offset.value
}

/**
 * Photo page gestures at 1x: left third = brightness, middle / right thirds up = info, down = close with a
 * drag-to-dismiss. Put it on the page (inside the pager): it only claims a drag once it is clearly vertical,
 * so sideways swipes stay with the pager and pinch / zoomed pans stay with [zoomable].
 */
fun Modifier.photoSwipeGestures(
    zoomed: () -> Boolean,
    pill: LevelPillState,
    dismiss: DismissDragState,
    onInfo: () -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
): Modifier = composed {
    if (!enabled) return@composed this
    val context = LocalContext.current
    val density = LocalDensity.current
    val lockPx = with(density) { ViewerGestureMath.LOCK_DP.dp.toPx() }
    val infoPx = with(density) { ViewerGestureMath.INFO_DP.dp.toPx() }
    val dismissPx = with(density) { ViewerGestureMath.DISMISS_DP.dp.toPx() }
    val scope = rememberCoroutineScope()
    val latestInfo by rememberUpdatedState(onInfo)
    val latestDismiss by rememberUpdatedState(onDismiss)
    val latestZoomed by rememberUpdatedState(zoomed)
    this.pointerInput(Unit) {
        val crossSlop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (latestZoomed()) return@awaitEachGesture
            val zone = ViewerGestureMath.zoneOf(down.position.x, size.width.toFloat())
            var axis = Axis.NONE
            var action = SwipeAction.NONE
            var start = 0f
            var fired = false
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1 || latestZoomed()) { action = if (action == SwipeAction.DISMISS) SwipeAction.DISMISS else SwipeAction.NONE; break }
                val c = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                val dx = c.position.x - down.position.x
                val dy = c.position.y - down.position.y
                if (axis == Axis.NONE) {
                    if (c.isConsumed) break // a child (zoom / scroll) took it
                    axis = ViewerGestureMath.lockDirection(dx, dy, lockPx, crossSlop)
                    if (axis == Axis.HORIZONTAL) break
                    if (axis == Axis.VERTICAL) {
                        action = ViewerGestureMath.actionFor(ViewerKind.PHOTO, zone, dy)
                        if (action == SwipeAction.BRIGHTNESS) start = WindowBrightness.current(context.findActivity())
                    }
                }
                if (axis == Axis.VERTICAL) {
                    c.consume()
                    when (action) {
                        SwipeAction.BRIGHTNESS -> {
                            val v = ViewerGestureMath.levelFromDrag(start, dy, size.height.toFloat(), ViewerGestureMath.MIN_BRIGHTNESS)
                            WindowBrightness.set(context.findActivity(), v)
                            pill.show(PillKind.BRIGHTNESS, v)
                        }
                        SwipeAction.INFO -> if (!fired && -dy > infoPx) { fired = true; latestInfo() }
                        SwipeAction.DISMISS -> scope.launch { dismiss.offset.snapTo(dy.coerceAtLeast(0f)) }
                        else -> Unit
                    }
                }
            }
            if (action == SwipeAction.DISMISS) {
                if (dismiss.value > dismissPx) latestDismiss()
                else scope.launch { dismiss.offset.animateTo(0f) }
            }
        }
    }
}

/**
 * Root of the video player: watches every touch before the children (player, controller, Compose overlay)
 * and takes a drag over once its direction is locked, so a visible controller can't swallow the gestures.
 * Taps, double-taps and long-press (2x) still reach the player. The seek bar asks its parent not to intercept.
 */
@android.annotation.SuppressLint("ClickableViewAccessibility") // taps go to the player view; this only takes drags
class VideoGestureLayout(context: Context) : FrameLayout(context) {
    interface Callbacks {
        /** True while something else owns touches (2x long-press, info sheet open). */
        fun blocked(): Boolean
        fun onBrightness(level: Float)
        fun onVolume(level: Float)
        fun onInfo()
        fun onDismissDrag(offsetPx: Float)
        /** Release of a downward middle drag; [offsetPx] decides close vs spring back. */
        fun onDismissEnd(offsetPx: Float, close: Boolean)
        /** Release of a sideways drag. */
        fun onHorizontalEnd(dx: Float)
        fun brightness(): Float
        fun volume(): Float
    }

    var callbacks: Callbacks? = null
    private val density = resources.displayMetrics.density
    private val lockPx = ViewerGestureMath.LOCK_DP * density
    private val infoPx = ViewerGestureMath.INFO_DP * density
    private val dismissPx = ViewerGestureMath.DISMISS_DP * density
    private var downX = 0f
    private var downY = 0f
    private var zone = Zone.MIDDLE
    private var axis = Axis.NONE
    private var action = SwipeAction.NONE
    private var start = 0f
    private var fired = false
    private var tracking = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val cb = callbacks ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                zone = ViewerGestureMath.zoneOf(ev.x, width.toFloat())
                axis = Axis.NONE; action = SwipeAction.NONE; fired = false
                tracking = !cb.blocked()
            }
            MotionEvent.ACTION_POINTER_DOWN -> tracking = false
            MotionEvent.ACTION_MOVE -> {
                if (!tracking || cb.blocked()) { tracking = false; return false }
                val dx = ev.x - downX
                val dy = ev.y - downY
                axis = ViewerGestureMath.lockDirection(dx, dy, lockPx)
                when (axis) {
                    Axis.NONE -> return false
                    Axis.HORIZONTAL -> { action = SwipeAction.NONE; return true }
                    Axis.VERTICAL -> {
                        action = ViewerGestureMath.actionFor(ViewerKind.VIDEO, zone, dy)
                        start = when (action) {
                            SwipeAction.BRIGHTNESS -> cb.brightness()
                            SwipeAction.VOLUME -> cb.volume()
                            else -> 0f
                        }
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val cb = callbacks ?: return false
        val dx = ev.x - downX
        val dy = ev.y - downY
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (axis == Axis.VERTICAL) when (action) {
                SwipeAction.BRIGHTNESS -> cb.onBrightness(ViewerGestureMath.levelFromDrag(start, dy, height.toFloat(), ViewerGestureMath.MIN_BRIGHTNESS))
                SwipeAction.VOLUME -> cb.onVolume(ViewerGestureMath.levelFromDrag(start, dy, height.toFloat()))
                SwipeAction.INFO -> if (!fired && -dy > infoPx) { fired = true; cb.onInfo() }
                SwipeAction.DISMISS -> cb.onDismissDrag(dy.coerceAtLeast(0f))
                else -> Unit
            }
            MotionEvent.ACTION_UP -> {
                if (axis == Axis.HORIZONTAL) cb.onHorizontalEnd(dx)
                if (action == SwipeAction.DISMISS) cb.onDismissEnd(dy.coerceAtLeast(0f), dy > dismissPx)
                reset()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (action == SwipeAction.DISMISS) cb.onDismissEnd(0f, false)
                reset()
            }
        }
        return true
    }

    private fun reset() {
        axis = Axis.NONE; action = SwipeAction.NONE; tracking = false
    }
}
