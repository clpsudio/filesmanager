package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.viewer.SlideshowOptions
import com.clpstudio.filesmanager.viewer.SlideshowPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SlideshowPlanTest {
    private fun item(name: String) = FileItem("/storage/emulated/0/DCIM/$name", name, isDir = false, size = 1L, modified = 0L)
    private val photos = listOf("a.jpg", "b.png", "c.mp4", "d.jpg", "e.webp").map(::item)

    private fun play(plan: SlideshowPlan, count: Int): List<String> {
        val out = mutableListOf(plan.current!!.name)
        repeat(count - 1) { plan.next(); out += plan.current!!.name }
        return out
    }

    @Test fun keepsSelectionOrder_andStopsAtEnd() {
        val plan = SlideshowPlan(photos, SlideshowOptions())
        assertEquals(listOf("a.jpg", "b.png", "c.mp4", "d.jpg", "e.webp"), play(plan, 5))
        assertFalse(plan.hasNext)
        assertFalse(plan.next())
        assertEquals("e.webp", plan.current!!.name)
        assertNull(plan.peekNext())
    }

    @Test fun previousStopsAtStartWithoutRepeat() {
        val plan = SlideshowPlan(photos, SlideshowOptions())
        assertFalse(plan.previous())
        assertEquals("a.jpg", plan.current!!.name)
        plan.next(); plan.next()
        assertTrue(plan.previous())
        assertEquals("b.png", plan.current!!.name)
    }

    @Test fun filtersOutNonMedia() {
        val mixed = photos + item("notes.pdf") + FileItem("/x/Folder", "Folder", isDir = true, size = 0, modified = 0) + item("song.mp3")
        val plan = SlideshowPlan(mixed)
        assertEquals(6, plan.size)
        assertTrue(plan.items.none { it.name == "notes.pdf" || it.isDir })
        assertTrue(SlideshowPlan(listOf(item("a.pdf"))).isEmpty)
        assertNull(SlideshowPlan(emptyList()).current)
    }

    @Test fun repeatWrapsAroundBothWays() {
        val plan = SlideshowPlan(photos, SlideshowOptions(repeat = true))
        repeat(4) { plan.next() }
        assertEquals("e.webp", plan.current!!.name)
        assertEquals("a.jpg", plan.peekNext()!!.name)
        assertTrue(plan.next())
        assertEquals("a.jpg", plan.current!!.name)
        assertEquals(1, plan.round)
        assertTrue(plan.previous())
        assertEquals("e.webp", plan.current!!.name)
    }

    @Test fun shuffleCoversEveryItemOncePerRound() {
        val plan = SlideshowPlan(photos, SlideshowOptions(shuffle = true, repeat = true), Random(42))
        var lastOfRound: String? = null
        repeat(6) { round ->
            val seen = mutableListOf(plan.current!!.name)
            repeat(photos.size - 1) { assertTrue(plan.next()); seen += plan.current!!.name }
            assertEquals("round $round", photos.map { it.name }.sorted(), seen.sorted())
            if (lastOfRound != null) assertNotEquals("no repeat across rounds", lastOfRound, seen.first())
            lastOfRound = seen.last()
            assertTrue(plan.next())
            assertEquals(round + 1, plan.round)
        }
    }

    @Test fun shuffleIsDeterministicWithSeed_andDiffersFromSelection() {
        val a = play(SlideshowPlan(photos, SlideshowOptions(shuffle = true), Random(7)), 5)
        val b = play(SlideshowPlan(photos, SlideshowOptions(shuffle = true), Random(7)), 5)
        assertEquals(a, b)
        val orders = (1..20).map { play(SlideshowPlan(photos, SlideshowOptions(shuffle = true), Random(it)), 5) }.toSet()
        assertTrue(orders.size > 1)
    }

    @Test fun shuffleWithoutRepeatEndsAfterOneRound() {
        val plan = SlideshowPlan(photos, SlideshowOptions(shuffle = true), Random(3))
        val seen = play(plan, 5)
        assertEquals(photos.map { it.name }.sorted(), seen.sorted())
        assertFalse(plan.next())
    }

    @Test fun startAtKeepsCurrentItem() {
        val plan = SlideshowPlan(photos, SlideshowOptions(), startAt = photos[3])
        assertEquals("d.jpg", plan.current!!.name)
        val shuffled = SlideshowPlan(photos, SlideshowOptions(shuffle = true), Random(1), startAt = photos[2])
        assertEquals("c.mp4", shuffled.current!!.name)
        assertEquals(photos.map { it.name }.sorted(), play(shuffled, 5).sorted())
    }

    @Test fun kenBurnsAlternatesZoomInAndOut() {
        for (i in 0 until 10) {
            val kb = SlideshowPlan.kenBurns(i)
            if (i % 2 == 0) {
                assertEquals(1.00f, kb.startScale, 0f); assertEquals(1.08f, kb.endScale, 1e-6f); assertTrue(kb.zoomsIn)
            } else {
                assertEquals(1.08f, kb.startScale, 1e-6f); assertEquals(1.00f, kb.endScale, 0f); assertFalse(kb.zoomsIn)
            }
            assertTrue(kotlin.math.abs(kb.driftXDp) <= 8f && kotlin.math.abs(kb.driftYDp) <= 8f)
            assertNotEquals(SlideshowPlan.kenBurns(i).driftXDp to SlideshowPlan.kenBurns(i).driftYDp,
                SlideshowPlan.kenBurns(i + 1).driftXDp to SlideshowPlan.kenBurns(i + 1).driftYDp)
        }
    }

    @Test fun optionsDefaultsAndRange() {
        val o = SlideshowOptions()
        assertEquals(5, o.seconds); assertFalse(o.repeat); assertFalse(o.shuffle)
        assertEquals(listOf(2, 3, 5, 8, 10, 15), SlideshowOptions.PRESETS)
        try { SlideshowOptions(seconds = 0); throw AssertionError("expected failure") } catch (_: IllegalArgumentException) {}
        try { SlideshowOptions(seconds = 31); throw AssertionError("expected failure") } catch (_: IllegalArgumentException) {}
    }
}
