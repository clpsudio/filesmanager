package com.clpstudio.filesmanager.ui.security

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/** The pure part of the pattern pad (unit tested): which dot a point hits, and dots jumped over. */
object PatternMath {
    /** Centre of dot [i] (0–8) in a [size]-px square. */
    fun center(i: Int, size: Float): Offset {
        val cell = size / 3f
        return Offset(cell * (i % 3) + cell / 2f, cell * (i / 3) + cell / 2f)
    }

    /** The dot under [p] (within a generous radius), or -1. */
    fun hit(p: Offset, size: Float): Int {
        val r = size / 3f * 0.36f
        for (i in 0..8) {
            val c = center(i, size)
            if (hypot(p.x - c.x, p.y - c.y) <= r) return i
        }
        return -1
    }

    /** The dot exactly between [a] and [b] (0→2 passes 1, 0→8 passes 4), or -1. */
    fun between(a: Int, b: Int): Int {
        val ar = a / 3; val ac = a % 3; val br = b / 3; val bc = b % 3
        if ((ar + br) % 2 != 0 || (ac + bc) % 2 != 0) return -1
        val m = (ar + br) / 2 * 3 + (ac + bc) / 2
        return if (m == a || m == b) -1 else m
    }

    /** Adds [dot] to [path] (plus a skipped middle dot that isn't used yet), Android-style. */
    fun extend(path: List<Int>, dot: Int): List<Int> {
        if (dot < 0 || dot in path) return path
        val out = path.toMutableList()
        out.lastOrNull()?.let { last -> between(last, dot).takeIf { it >= 0 && it !in out }?.let { out += it } }
        out += dot
        return out
    }

    fun encode(path: List<Int>): String = path.joinToString("")
}

/**
 * A 3×3 pattern pad (blue lines, red after a wrong try). [onDone] gets the drawn pattern ("0125…") when the finger
 * lifts. [error] colours the last pattern red until the next touch. [hideTrail] = don't show the lines (stealth).
 */
@Composable
fun PatternPad(
    onDone: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    enabled: Boolean = true,
    hideTrail: Boolean = false,
) {
    val path = remember { mutableStateListOf<Int>() }
    var finger by remember { mutableStateOf<Offset?>(null) }
    var showError by remember { mutableStateOf(false) }
    val done by rememberUpdatedState(onDone)
    val on by rememberUpdatedState(enabled)
    showError = showError && error
    val primary = MaterialTheme.colorScheme.primary
    val bad = MaterialTheme.colorScheme.error
    val idle = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f).testTag("patternPad")
            .semantics { contentDescription = "Pattern: draw by connecting at least 4 dots" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!on) return@awaitEachGesture
                    path.clear(); showError = false
                    val s = size.width.toFloat()
                    PatternMath.extend(path.toList(), PatternMath.hit(down.position, s)).let { path.clear(); path.addAll(it) }
                    finger = down.position
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        finger = c.position
                        val next = PatternMath.extend(path.toList(), PatternMath.hit(c.position, s))
                        if (next.size != path.size) { path.clear(); path.addAll(next) }
                    }
                    finger = null
                    if (path.isNotEmpty()) {
                        showError = true // shown red only if the caller says it was wrong
                        done(PatternMath.encode(path))
                    }
                }
            },
    ) {
        Canvas(Modifier.matchParentSizeCompat()) {
            val s = size.width
            val color = if (showError && error) bad else primary
            if (!hideTrail) {
                for (k in 1 until path.size) drawLine(color, PatternMath.center(path[k - 1], s), PatternMath.center(path[k], s), strokeWidth = s / 40f, cap = StrokeCap.Round)
                val f = finger
                if (f != null && path.isNotEmpty()) drawLine(color.copy(alpha = 0.6f), PatternMath.center(path.last(), s), f, strokeWidth = s / 40f, cap = StrokeCap.Round)
            }
            for (i in 0..8) {
                val c = PatternMath.center(i, s)
                val used = i in path && !hideTrail
                if (used) drawCircle(color.copy(alpha = 0.18f), radius = s / 12f, center = c)
                drawCircle(if (used) color else idle, radius = if (used) s / 30f else s / 42f, center = c)
            }
        }
    }
}

private fun Modifier.matchParentSizeCompat(): Modifier = this.then(Modifier.fillMaxWidth().aspectRatio(1f))
