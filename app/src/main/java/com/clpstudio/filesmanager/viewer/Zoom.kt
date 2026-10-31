package com.clpstudio.filesmanager.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs

@Stable
class ZoomState(private val maxScale: Float = 6f) {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    val zoomed: Boolean get() = scale > 1.01f

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    internal fun apply(zoom: Float, pan: Offset, width: Float, height: Float, panX: Boolean = true, panY: Boolean = true) {
        val s = (scale * zoom).coerceIn(1f, maxScale)
        scale = s
        val maxX = width * (s - 1) / 2
        val maxY = height * (s - 1) / 2
        offset = if (s <= 1f) Offset.Zero else Offset(
            if (panX) (offset.x + pan.x).coerceIn(-maxX, maxX) else offset.x.coerceIn(-maxX, maxX),
            if (panY) (offset.y + pan.y).coerceIn(-maxY, maxY) else offset.y.coerceIn(-maxY, maxY),
        )
    }
}

/**
 * Pinch to zoom, pan while zoomed, double-tap to zoom in/out. When not zoomed, one-finger drags
 * are left alone so a pager / list can scroll. [verticalScrollOwner] = the content scrolls
 * vertically itself (PDF), so vertical pans are left to it.
 */
fun Modifier.zoomable(state: ZoomState, onTap: () -> Unit = {}, verticalScrollOwner: Boolean = false): Modifier = this
    .pointerInput(state) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { p ->
                if (state.zoomed) {
                    state.reset()
                } else {
                    val target = 2.5f
                    state.scale = target
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxX = size.width * (target - 1) / 2
                    val maxY = size.height * (target - 1) / 2
                    state.offset = Offset(((center.x - p.x) * (target - 1)).coerceIn(-maxX, maxX), if (verticalScrollOwner) 0f else ((center.y - p.y) * (target - 1)).coerceIn(-maxY, maxY))
                }
            },
        )
    }
    .pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val fingers = event.changes.count { it.pressed }
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (fingers > 1 || state.zoomed) {
                    val horizontalPan = abs(pan.x) >= abs(pan.y)
                    if (verticalScrollOwner && fingers == 1) {
                        // Let the list scroll vertically; move sideways ourselves.
                        state.apply(1f, pan, size.width.toFloat(), size.height.toFloat(), panX = true, panY = false)
                        if (horizontalPan) event.changes.forEach { if (it.positionChanged()) it.consume() }
                    } else {
                        state.apply(zoom, pan, size.width.toFloat(), size.height.toFloat(), panY = !verticalScrollOwner)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    .graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        translationX = state.offset.x
        translationY = state.offset.y
    }
