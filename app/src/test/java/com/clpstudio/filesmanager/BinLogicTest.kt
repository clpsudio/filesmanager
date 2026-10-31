package com.clpstudio.filesmanager

import android.app.Application
import com.clpstudio.filesmanager.data.BinEntry
import com.clpstudio.filesmanager.data.SystemTrash
import com.clpstudio.filesmanager.data.daysLeftLabel
import com.clpstudio.filesmanager.data.groupByDaysLeft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BinLogicTest {
    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = 1_790_000_000_000L

    private fun entry(id: String, deletedAgo: Long) =
        BinEntry(id, "$id.jpg", "/storage/emulated/0/DCIM/$id.jpg", now - deletedAgo, false, 10, File("/tmp/$id"))

    @Test fun daysLeftRoundsUp() {
        assertEquals(30, entry("a", 0).daysLeft(now))
        assertEquals(30, entry("a", day / 2).daysLeft(now))
        assertEquals(29, entry("a", day + 1).daysLeft(now))
        assertEquals(1, entry("a", 30 * day - 1).daysLeft(now))
        assertEquals(0, entry("a", 31 * day).daysLeft(now))
    }

    @Test fun groupsByDaysLeftMostFirstKeepingOrder() {
        val items = listOf(entry("a", 0), entry("b", 2 * day + 5), entry("c", 10), entry("d", day + 5), entry("e", 2 * day + 9))
        val groups = groupByDaysLeft(items) { it.daysLeft(now) }
        assertEquals(listOf(30, 29, 28), groups.map { it.daysLeft })
        assertEquals(listOf("a", "c"), groups[0].items.map { it.id })
        assertEquals(listOf("d"), groups[1].items.map { it.id })
        assertEquals(listOf("b", "e"), groups[2].items.map { it.id })
        assertEquals("30 days until deletion", groups[0].label)
    }

    @Test fun labels() {
        assertEquals("1 day until deletion", daysLeftLabel(1))
        assertEquals("29 days until deletion", daysLeftLabel(29))
        assertEquals("Deleting today", daysLeftLabel(0))
        assertEquals(listOf(0), groupByDaysLeft(listOf(-3)) { it }.map { it.daysLeft })
    }

    @Test fun parsesTrashedNames() {
        val (name, expires) = SystemTrash.parseTrashedName(".trashed-1730000000-IMG_1.jpg")!!
        assertEquals("IMG_1.jpg", name)
        assertEquals(1_730_000_000_000L, expires)
        assertEquals("my-file-2.final.pdf", SystemTrash.parseTrashedName(".trashed-1730000000-my-file-2.final.pdf")!!.first)
        assertEquals(1_730_000_000_123L, SystemTrash.parseTrashedName(".trashed-1730000000123-a.png")!!.second)
        assertNull(SystemTrash.parseTrashedName("IMG_1.jpg"))
        assertNull(SystemTrash.parseTrashedName(".trashed-abc-IMG_1.jpg"))
        assertNull(SystemTrash.parseTrashedName(".trashed-1730000000-"))
        assertEquals("IMG_1.jpg", SystemTrash.originalName(".trashed-1730000000-IMG_1.jpg"))
        assertEquals(".nomedia", SystemTrash.originalName(".nomedia"))
    }

    @Test fun daysUntilExpiry() {
        assertEquals(12, SystemTrash.daysUntil(now + 11 * day + 1, now))
        assertEquals(0, SystemTrash.daysUntil(now - 5, now))
    }
}
