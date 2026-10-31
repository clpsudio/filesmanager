package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.viewer.Axis
import com.clpstudio.filesmanager.viewer.SwipeAction
import com.clpstudio.filesmanager.viewer.ViewerGestureMath
import com.clpstudio.filesmanager.viewer.ViewerKind
import com.clpstudio.filesmanager.viewer.Zone
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerGesturesTest {
    private val m = ViewerGestureMath

    @Test fun zonesAreThirds() {
        val w = 900f
        assertEquals(Zone.LEFT, m.zoneOf(0f, w))
        assertEquals(Zone.LEFT, m.zoneOf(299f, w))
        assertEquals(Zone.MIDDLE, m.zoneOf(301f, w))
        assertEquals(Zone.MIDDLE, m.zoneOf(450f, w))
        assertEquals(Zone.MIDDLE, m.zoneOf(599f, w))
        assertEquals(Zone.RIGHT, m.zoneOf(601f, w))
        assertEquals(Zone.RIGHT, m.zoneOf(900f, w))
        assertEquals(Zone.MIDDLE, m.zoneOf(10f, 0f)) // not laid out yet
    }

    @Test fun directionLockWaitsForLockDistance() {
        val lock = 48f // 16dp at xxhdpi
        assertEquals(Axis.NONE, m.lockDirection(10f, 20f, lock))
        assertEquals(Axis.NONE, m.lockDirection(-30f, -30f, lock)) // 42 px < 48
        assertEquals(Axis.VERTICAL, m.lockDirection(5f, -60f, lock))
        assertEquals(Axis.VERTICAL, m.lockDirection(-20f, 50f, lock))
        assertEquals(Axis.HORIZONTAL, m.lockDirection(60f, 5f, lock))
        assertEquals(Axis.HORIZONTAL, m.lockDirection(-50f, 20f, lock))
        // A diagonal exactly 45° is horizontal (the pager keeps ties).
        assertEquals(Axis.HORIZONTAL, m.lockDirection(40f, 40f, lock))
    }

    @Test fun pagerKeepsDragsThatAlreadyWentSideways() {
        // 25 px sideways is past the pager's 24 px touch slop: horizontal even though it is mostly vertical.
        assertEquals(Axis.HORIZONTAL, m.lockDirection(25f, 60f, 48f, crossSlopPx = 24f))
        assertEquals(Axis.VERTICAL, m.lockDirection(10f, 60f, 48f, crossSlopPx = 24f))
    }

    @Test fun videoZones() {
        assertEquals(SwipeAction.BRIGHTNESS, m.actionFor(ViewerKind.VIDEO, Zone.LEFT, -1f))
        assertEquals(SwipeAction.BRIGHTNESS, m.actionFor(ViewerKind.VIDEO, Zone.LEFT, 1f))
        assertEquals(SwipeAction.VOLUME, m.actionFor(ViewerKind.VIDEO, Zone.RIGHT, -1f))
        assertEquals(SwipeAction.VOLUME, m.actionFor(ViewerKind.VIDEO, Zone.RIGHT, 1f))
        assertEquals(SwipeAction.INFO, m.actionFor(ViewerKind.VIDEO, Zone.MIDDLE, -1f))
        assertEquals(SwipeAction.DISMISS, m.actionFor(ViewerKind.VIDEO, Zone.MIDDLE, 1f))
    }

    @Test fun photoZones() {
        assertEquals(SwipeAction.BRIGHTNESS, m.actionFor(ViewerKind.PHOTO, Zone.LEFT, -1f))
        assertEquals(SwipeAction.BRIGHTNESS, m.actionFor(ViewerKind.PHOTO, Zone.LEFT, 1f))
        assertEquals(SwipeAction.INFO, m.actionFor(ViewerKind.PHOTO, Zone.MIDDLE, -1f))
        assertEquals(SwipeAction.INFO, m.actionFor(ViewerKind.PHOTO, Zone.RIGHT, -1f))
        assertEquals(SwipeAction.DISMISS, m.actionFor(ViewerKind.PHOTO, Zone.MIDDLE, 1f))
    }

    @Test fun levelGoesBothWaysWhileDragging() {
        val h = 1000f // full range = 600 px
        assertEquals(0.5f, m.levelFromDrag(0.5f, 0f, h), 1e-4f)
        assertEquals(0.75f, m.levelFromDrag(0.5f, -150f, h), 1e-4f) // up = brighter / louder
        assertEquals(0.25f, m.levelFromDrag(0.5f, 150f, h), 1e-4f) // down = darker / quieter
        assertEquals(1f, m.levelFromDrag(0.5f, -900f, h), 1e-4f)
        assertEquals(0f, m.levelFromDrag(0.5f, 900f, h), 1e-4f)
        assertEquals(0.01f, m.levelFromDrag(0.5f, 900f, h, min = 0.01f), 1e-4f) // never a black screen
        assertEquals(0.4f, m.levelFromDrag(0.4f, 50f, 0f), 1e-4f)
    }

    @Test fun volumeSteps() {
        assertEquals(0, m.volumeStep(0f, 15))
        assertEquals(15, m.volumeStep(1f, 15))
        assertEquals(8, m.volumeStep(0.5f, 15)) // 7.5 rounds up
        assertEquals(7, m.volumeStep(0.45f, 15))
        assertEquals(15, m.volumeStep(1.4f, 15))
        assertEquals(0, m.volumeStep(-0.2f, 15))
        assertEquals(0, m.volumeStep(0.7f, 0))
        assertEquals(1f, m.volumeLevel(15, 15), 1e-4f)
        assertEquals(0.2f, m.volumeLevel(3, 15), 1e-4f)
        assertEquals(0f, m.volumeLevel(3, 0), 1e-4f)
        // Every step is reachable and maps back to itself.
        for (s in 0..15) assertEquals(s, m.volumeStep(m.volumeLevel(s, 15), 15))
    }

    @Test fun brightnessAndPercent() {
        assertEquals(1f, m.brightnessFromSystem(255), 1e-4f)
        assertEquals(0.01f, m.brightnessFromSystem(0), 1e-4f)
        assertEquals(0.5f, m.brightnessFromSystem(128, 256), 1e-4f)
        assertEquals(1f, m.brightnessFromSystem(4000), 1e-4f)
        assertEquals(0, m.percent(0f))
        assertEquals(50, m.percent(0.5f))
        assertEquals(100, m.percent(1.2f))
        assertEquals(67, m.percent(0.666f))
    }

    @Test fun dismissProgress() {
        assertEquals(0f, m.dismissProgress(0f, 300f), 1e-4f)
        assertEquals(0.4f, m.dismissProgress(300f, 300f), 1e-4f)
        assertEquals(1f, m.dismissProgress(5000f, 300f), 1e-4f)
        assertEquals(0f, m.dismissProgress(100f, 0f), 1e-4f)
    }
}
