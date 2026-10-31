package com.clpstudio.filesmanager.viewer

import kotlin.math.abs
import kotlin.math.roundToLong

/** What a sideways drag on the video does, by where it started. */
enum class HorizontalAction { SCRUB, PAGE }

/** What one drag on a video does (see [VideoGestureMath.classify]). */
enum class VideoGesture { NONE, SCRUB, PAGE, BRIGHTNESS, VOLUME, INFO, DISMISS }

/**
 * The pure part of the video player's gestures (Charan, Oct 31, 12:33):
 *  - left / right third: sideways = move the timeline (live, with a time bubble); up / down = brightness / volume.
 *  - middle third: sideways = previous / next video with a slide; up = info, down = close.
 */
object VideoGestureMath {
    /** A full screen-width drag moves at most this much (or the whole video when it is shorter). */
    const val FULL_WIDTH_MS = 120_000L
    /** Share of the width a page drag must pass to change video on release (or a fast fling). */
    const val PAGE_THRESHOLD = 0.22f
    /** A fling faster than this (dp per second) changes video even when short. */
    const val FLING_DP_S = 900f
    /** Live seeks while scrubbing are spaced at least this far apart. */
    const val SCRUB_SEEK_EVERY_MS = 60L

    /** Share of the width each side zone takes (left 20 % · middle 60 % · right 20 %). */
    const val SIDE_ZONE = 0.2f

    /**
     * Charan, Oct 31 14:26: "Timeline works only at the middle, and swiping at the sides means moving to another file."
     * Sideways from the middle = timeline, from a side = previous / next file.
     */
    fun horizontalAction(zone: Zone): HorizontalAction = if (zone == Zone.MIDDLE) HorizontalAction.SCRUB else HorizontalAction.PAGE

    /** The video's zones: left 20 %, middle 60 %, right 20 %. */
    fun sideZoneOf(x: Float, width: Float): Zone {
        if (width <= 0f) return Zone.MIDDLE
        val f = x / width
        return when {
            f < SIDE_ZONE -> Zone.LEFT
            f > 1f - SIDE_ZONE -> Zone.RIGHT
            else -> Zone.MIDDLE
        }
    }

    /**
     * Every video gesture in one place (pager video and the player), from where the drag started ([x] of [width])
     * and the locked [axis]; [dy] < 0 is up. Sides: up/down = brightness (left) / volume (right), sideways = other file.
     * Middle: sideways = timeline, down = close, up = info. Side drags never close.
     */
    fun classify(x: Float, width: Float, axis: Axis, dy: Float): VideoGesture {
        val zone = sideZoneOf(x, width)
        return when (axis) {
            Axis.NONE -> VideoGesture.NONE
            Axis.HORIZONTAL -> if (horizontalAction(zone) == HorizontalAction.SCRUB) VideoGesture.SCRUB else VideoGesture.PAGE
            Axis.VERTICAL -> when (zone) {
                Zone.LEFT -> VideoGesture.BRIGHTNESS
                Zone.RIGHT -> VideoGesture.VOLUME
                Zone.MIDDLE -> if (dy < 0) VideoGesture.INFO else VideoGesture.DISMISS
            }
        }
    }

    /** How much time one full screen width covers for a video of [durationMs]. */
    fun msPerWidth(durationMs: Long): Long = when {
        durationMs <= 0L -> FULL_WIDTH_MS
        else -> minOf(durationMs, FULL_WIDTH_MS)
    }

    /** Target position after dragging [dx] px on a [width] px wide view, from [startMs]. Clamped to the video. */
    fun scrubTarget(startMs: Long, dx: Float, width: Float, durationMs: Long): Long {
        if (width <= 0f) return startMs
        val moved = (dx / width * msPerWidth(durationMs)).roundToLong()
        val max = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return (startMs + moved).coerceIn(0L, max)
    }

    /**
     * Release of a middle drag: +1 = next, -1 = previous, 0 = spring back.
     * Left swipe ([dx] < 0) goes to the next video, like a pager.
     */
    fun pageDecision(dx: Float, width: Float, velocityPxS: Float, density: Float, hasPrev: Boolean, hasNext: Boolean): Int {
        val far = width > 0f && abs(dx) > width * PAGE_THRESHOLD
        val fling = abs(velocityPxS) > FLING_DP_S * density && (velocityPxS < 0) == (dx < 0) && abs(dx) > 24f * density
        if (!far && !fling) return 0
        return if (dx < 0) (if (hasNext) 1 else 0) else (if (hasPrev) -1 else 0)
    }

    /** Page drag offset with rubber-banding at the ends of the list. */
    fun pageOffset(dx: Float, hasPrev: Boolean, hasNext: Boolean): Float = when {
        dx > 0 && !hasPrev -> dx * 0.25f
        dx < 0 && !hasNext -> dx * 0.25f
        else -> dx
    }

    /** "1:05", "12:40", "1:02:03". */
    fun formatTime(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000L)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** "+0:12" / "-1:05" for the scrub bubble. */
    fun formatDelta(deltaMs: Long): String = (if (deltaMs < 0) "-" else "+") + formatTime(abs(deltaMs))

    /** Picture-in-picture accepts 1:2.39 .. 2.39:1; anything outside is clamped. Returns (w, h). */
    fun pipAspect(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 16 to 9
        val r = width.toDouble() / height
        return when {
            r > 2.39 -> 239 to 100
            r < 1 / 2.39 -> 100 to 239
            else -> width to height
        }
    }

    /** The speed after the current one in [SPEEDS] (the chip cycles on a tap and wraps). */
    fun nextSpeed(current: Float): Float {
        val i = SPEEDS.indexOfFirst { abs(it - current) < 0.001f }
        return if (i < 0) 1f else SPEEDS[(i + 1) % SPEEDS.size]
    }
}

/**
 * The player's list can hold photos and videos (Media): the player plays only the videos, in order,
 * and shows a photo in place when the user swipes onto one.
 */
class MixedPlaylist(val kinds: List<Boolean>) { // true = video
    /** Media item index (videos only) for an entry, or -1 for a photo. */
    fun mediaIndexOf(entry: Int): Int = if (kinds.getOrNull(entry) != true) -1 else kinds.take(entry).count { it }

    /** Entry index of the [media]-th video. */
    fun entryOfMedia(media: Int): Int {
        var n = -1
        kinds.forEachIndexed { i, v -> if (v) { n++; if (n == media) return i } }
        return -1
    }

    val videoCount: Int get() = kinds.count { it }
}
