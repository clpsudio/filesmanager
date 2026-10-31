package com.clpstudio.filesmanager.viewer

import android.content.Context
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** What the pager video page asks of the screen around it. */
interface VideoPageGestureCallbacks {
    /** Sideways from a side zone: the page follows the finger ([dx] since the drag started). */
    fun onPageDrag(dx: Float)
    /** Release of a side drag: the screen decides next / previous / spring back. */
    fun onPageEnd(dx: Float, velocityPxS: Float)
    fun onInfo()
    fun onDismiss()
}

/**
 * Every drag on a video page in the photo / video pager (Media, Recent, Favorites, folders, Videos):
 * left side up/down = brightness, right side = volume, middle sideways = timeline (time bubble),
 * side sideways = previous / next file, middle down = close, middle up = info ([VideoGestureMath.classify]).
 * Taps and double-taps stay with the page's tap detector; a drag is only claimed once its direction is locked.
 */
fun Modifier.videoPageGestures(
    video: PagerVideo,
    enabled: Boolean,
    pill: LevelPillState,
    dismiss: DismissDragState,
    callbacks: VideoPageGestureCallbacks,
): Modifier = composed {
    if (!enabled) return@composed this
    val context = LocalContext.current
    val density = LocalDensity.current
    val lockPx = with(density) { ViewerGestureMath.LOCK_DP.dp.toPx() }
    val infoPx = with(density) { ViewerGestureMath.INFO_DP.dp.toPx() }
    val dismissPx = with(density) { ViewerGestureMath.DISMISS_DP.dp.toPx() }
    val scope = rememberCoroutineScope()
    val volume = remember { MediaVolume(context) }
    val cb by rememberUpdatedState(callbacks)
    this.pointerInput(video) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val width = size.width.toFloat()
            var axis = Axis.NONE
            var gesture = VideoGesture.NONE
            var start = 0f
            var startMs = 0L
            var lastSeekAt = 0L
            var fired = false
            var dxLast = 0f
            var dyLast = 0f
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, down.position)
            var cancelled = false
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1) { cancelled = true; break }
                val c = event.changes.firstOrNull { it.id == down.id } ?: break
                velocity.addPosition(c.uptimeMillis, c.position)
                if (!c.pressed) break
                val dx = c.position.x - down.position.x
                val dy = c.position.y - down.position.y
                dxLast = dx; dyLast = dy
                if (axis == Axis.NONE) {
                    if (c.isConsumed) { cancelled = true; break } // a control above took it
                    axis = ViewerGestureMath.lockDirection(dx, dy, lockPx)
                    if (axis == Axis.NONE) continue
                    gesture = VideoGestureMath.classify(down.position.x, width, axis, dy)
                    when (gesture) {
                        VideoGesture.BRIGHTNESS -> start = WindowBrightness.current(context.findActivity())
                        VideoGesture.VOLUME -> start = volume.level()
                        VideoGesture.SCRUB -> { startMs = video.position; video.scrubStart(startMs) }
                        else -> Unit
                    }
                }
                c.consume()
                when (gesture) {
                    VideoGesture.BRIGHTNESS -> {
                        val v = ViewerGestureMath.levelFromDrag(start, dy, size.height.toFloat(), ViewerGestureMath.MIN_BRIGHTNESS)
                        WindowBrightness.set(context.findActivity(), v)
                        pill.show(PillKind.BRIGHTNESS, v)
                    }
                    VideoGesture.VOLUME -> {
                        val v = volume.set(ViewerGestureMath.levelFromDrag(start, dy, size.height.toFloat()))
                        pill.show(PillKind.VOLUME, v)
                    }
                    VideoGesture.SCRUB -> {
                        val target = VideoGestureMath.scrubTarget(startMs, dx, width, video.duration)
                        val now = c.uptimeMillis
                        video.scrubTo(target, seek = now - lastSeekAt >= VideoGestureMath.SCRUB_SEEK_EVERY_MS)
                        if (now - lastSeekAt >= VideoGestureMath.SCRUB_SEEK_EVERY_MS) lastSeekAt = now
                    }
                    VideoGesture.PAGE -> cb.onPageDrag(dx)
                    VideoGesture.INFO -> if (!fired && -dy > infoPx) { fired = true; cb.onInfo() }
                    VideoGesture.DISMISS -> scope.launch { dismiss.offset.snapTo(dy.coerceAtLeast(0f)) }
                    VideoGesture.NONE -> Unit
                }
            }
            when (gesture) {
                VideoGesture.SCRUB -> video.scrubEnd(cancelled)
                VideoGesture.PAGE -> if (cancelled) cb.onPageEnd(0f, 0f) else cb.onPageEnd(dxLast, velocity.calculateVelocity().x)
                VideoGesture.DISMISS -> if (!cancelled && dyLast > dismissPx) cb.onDismiss() else scope.launch { dismiss.offset.animateTo(0f) }
                else -> Unit
            }
        }
    }
}
