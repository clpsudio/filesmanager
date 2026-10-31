package com.clpstudio.filesmanager.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** Which tab a horizontal swipe of [dx] px lands on (left = next, right = previous); -1 / count = past an edge. */
object SwipeTabsLogic {
    fun target(index: Int, count: Int, dx: Float, threshold: Float): Int = when {
        dx <= -threshold -> index + 1
        dx >= threshold -> index - 1
        else -> index
    }

    fun clamp(target: Int, count: Int): Int = target.coerceIn(0, (count - 1).coerceAtLeast(0))
}

/**
 * Swipe left / right on a screen's content to move between its tabs or chips ([index] of [count]).
 * Only a clearly horizontal drag counts, so lists still scroll up and down and long-press / drag-select
 * keep working; off while [enabled] is false (multi-select). A swipe past the first / last tab calls
 * [onPastStart] / [onPastEnd] when given (e.g. from the first type chip back to the Folders tab).
 */
fun Modifier.swipeTabs(
    index: Int,
    count: Int,
    enabled: Boolean = true,
    onPastStart: (() -> Unit)? = null,
    onPastEnd: (() -> Unit)? = null,
    onChange: (Int) -> Unit,
): Modifier = composed {
    val change by rememberUpdatedState(onChange)
    val start by rememberUpdatedState(onPastStart)
    val end by rememberUpdatedState(onPastEnd)
    if (!enabled || (count < 2 && onPastStart == null && onPastEnd == null)) return@composed this
    this.pointerInput(index, count) {
        val threshold = 64.dp.toPx()
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragCancel = { total = 0f },
            onDragEnd = {
                val t = SwipeTabsLogic.target(index, count, total, threshold)
                when {
                    t == index -> Unit
                    t < 0 -> start?.invoke()
                    t >= count -> end?.invoke()
                    else -> change(t)
                }
            },
        ) { c, dx -> total += dx; c.consume() }
    }
}
