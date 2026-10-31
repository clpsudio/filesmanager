package com.clpstudio.filesmanager.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Finds the item under a point of a lazy list or grid, and scrolls it. */
interface LazyHitTester {
    /** Key of the item at [pos] (pointer position inside the list), or null. */
    fun keyAt(pos: Offset): Any?
    /** Size of the list along its scroll direction. */
    val viewportSize: Int
    suspend fun scroll(px: Float)
}

fun LazyGridState.hitTester(): LazyHitTester = object : LazyHitTester {
    override fun keyAt(pos: Offset): Any? {
        val info = layoutInfo
        val y = pos.y + info.viewportStartOffset
        val x = pos.x
        return info.visibleItemsInfo.firstOrNull {
            y >= it.offset.y && y < it.offset.y + it.size.height && x >= it.offset.x && x < it.offset.x + it.size.width
        }?.key
    }
    override val viewportSize: Int get() = layoutInfo.viewportSize.height
    override suspend fun scroll(px: Float) { scrollBy(px) }
}

fun androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState.hitTester(): LazyHitTester = object : LazyHitTester {
    override fun keyAt(pos: Offset): Any? {
        val info = layoutInfo
        val y = pos.y + info.viewportStartOffset
        val x = pos.x
        return info.visibleItemsInfo.firstOrNull {
            y >= it.offset.y && y < it.offset.y + it.size.height && x >= it.offset.x && x < it.offset.x + it.size.width
        }?.key
    }
    override val viewportSize: Int get() = layoutInfo.viewportSize.height
    override suspend fun scroll(px: Float) { scrollBy(px) }
}

fun LazyListState.hitTester(): LazyHitTester = object : LazyHitTester {
    override fun keyAt(pos: Offset): Any? {
        val info = layoutInfo
        val y = pos.y + info.viewportStartOffset
        return info.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.key
    }
    override val viewportSize: Int get() = layoutInfo.viewportSize.height
    override suspend fun scroll(px: Float) { scrollBy(px) }
}

/** Range selection while dragging: everything between the anchor and the item under the finger. */
object DragSelectLogic {
    /**
     * [initial] is the selection before the drag; items between [anchor] and [current] (inclusive)
     * become selected (or unselected when [select] is false); every other item keeps its initial state,
     * so dragging back unselects again.
     */
    fun apply(keys: List<String>, initial: Set<String>, anchor: Int, current: Int, select: Boolean): LinkedHashSet<String> {
        val out = LinkedHashSet(initial)
        if (anchor !in keys.indices) return out
        val c = current.coerceIn(keys.indices)
        val range = if (anchor <= c) anchor..c else c..anchor
        // Keep the drag order (anchor first) so the selection order follows the finger.
        val ordered = if (anchor <= c) range.toList() else range.reversed().toList()
        for (i in ordered) if (select) out.add(keys[i]) else out.remove(keys[i])
        return out
    }
}

/**
 * Long-press, then drag across a grid or list to select a range (Samsung / Google Photos style):
 * haptic on start, unselects again when dragging back past the anchor, auto-scrolls near the top
 * and bottom edges. Items should pass a no-op long click (this handles it), taps still go to items.
 */
@Composable
fun Modifier.dragToSelect(
    hit: LazyHitTester,
    keys: List<String>,
    selection: SelectionState,
    enabled: Boolean = true,
): Modifier {
    val haptic = LocalHapticFeedback.current
    val currentKeys by rememberUpdatedState(keys)
    val edge = with(LocalDensity.current) { 72.dp.toPx() }
    val maxSpeed = with(LocalDensity.current) { 22.dp.toPx() }
    if (!enabled) return this
    return this.pointerInput(hit, selection) {
        coroutineScope {
        val cs = this
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val startKey = hit.keyAt(down.position) as? String ?: return@awaitEachGesture
            // Wait for the long press; a move past the touch slop (scrolling) or lifting the finger cancels.
            val cancelled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val c = e.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                    if (!c.pressed || c.isConsumed) return@withTimeoutOrNull true
                    if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
                }
                @Suppress("UNREACHABLE_CODE") true
            }
            if (cancelled != null) return@awaitEachGesture
            val keysNow = currentKeys
            val anchor = keysNow.indexOf(startKey)
            if (anchor < 0) return@awaitEachGesture
            val initial = selection.paths.toSet()
            val select = startKey !in initial
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            var lastIndex = anchor
            fun update(idx: Int) {
                lastIndex = idx
                val next = DragSelectLogic.apply(currentKeys, initial, anchor, idx, select)
                if (next.toList() != selection.paths.toList()) selection.selectAll(next.toList())
            }
            update(anchor)
            var pos = down.position
            fun indexAt(p: Offset): Int {
                val k = hit.keyAt(p) as? String ?: return lastIndex
                return currentKeys.indexOf(k).takeIf { it >= 0 } ?: lastIndex
            }
            var dragging = true
            // Auto-scroll runs beside the pointer loop.
            val job = cs.launch {
                while (isActive && dragging) {
                    val h = hit.viewportSize.toFloat()
                    val speed = when {
                        pos.y < edge -> -maxSpeed * (1f - (pos.y / edge).coerceIn(0f, 1f))
                        pos.y > h - edge -> maxSpeed * (1f - ((h - pos.y) / edge).coerceIn(0f, 1f))
                        else -> 0f
                    }
                    if (speed != 0f) {
                        hit.scroll(speed)
                        update(indexAt(pos))
                    }
                    delay(16)
                }
            }
            try {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) { c.consume(); break }
                    c.consume()
                    pos = c.position
                    update(indexAt(pos))
                }
            } finally {
                dragging = false
                job.cancel()
            }
        }
        }
    }
}
