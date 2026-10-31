package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.DateGrouping
import com.clpstudio.filesmanager.data.Category
import com.clpstudio.filesmanager.data.DateSections
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileView
import com.clpstudio.filesmanager.data.TypeFilters
import com.clpstudio.filesmanager.data.decodeFilters
import com.clpstudio.filesmanager.data.encodeFilters
import com.clpstudio.filesmanager.ui.components.DragSelectLogic
import com.clpstudio.filesmanager.ui.components.columnsFor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class ListLogicTest {
    private val keys = (0 until 10).map { "f$it" }

    @Test fun dragSelectsRangeAndUnselectsOnDragBack() {
        val initial = setOf("f8")
        assertEquals(setOf("f8", "f2", "f3", "f4", "f5"), DragSelectLogic.apply(keys, initial, 2, 5, select = true))
        // Dragging back towards the anchor unselects what the finger left.
        assertEquals(setOf("f8", "f2", "f3"), DragSelectLogic.apply(keys, initial, 2, 3, select = true))
        // Past the anchor the range flips upward.
        assertEquals(setOf("f8", "f0", "f1", "f2"), DragSelectLogic.apply(keys, initial, 2, 0, select = true))
        // Starting on a selected item unselects the range.
        assertEquals(setOf("f1"), DragSelectLogic.apply(keys, setOf("f1", "f4", "f5"), 4, 6, select = false))
    }

    private fun item(name: String, path: String = "/storage/emulated/0/Download/$name", modified: Long = 0) =
        FileItem(path, name, false, 10, modified)

    @Test fun typeChipsCountAndFilter() {
        val items = listOf(item("a.pdf"), item("b.docx"), item("c.PDF"), item("d.xlsx"), item("e.bin"), item("f.txt"))
        val chips = TypeFilters.counted(Category.DOCUMENTS, items, TypeFilters.ALL).associate { it.first.id to it.second }
        assertEquals(6, chips["ALL"]); assertEquals(2, chips["PDF"]); assertEquals(1, chips["WORD"])
        assertEquals(1, chips["EXCEL"]); assertEquals(1, chips["TXT"]); assertEquals(1, chips["OTHER"])
        assertEquals(false, "PPT" in chips) // no PowerPoint files: chip hidden
        assertEquals(listOf("a.pdf", "c.PDF"), TypeFilters.apply(Category.DOCUMENTS, items, "PDF").map { it.name })
        val shots = listOf(item("Screenshot_1.png", "/storage/emulated/0/Pictures/Screenshots/Screenshot_1.png"), item("IMG_1.jpg", "/storage/emulated/0/DCIM/Camera/IMG_1.jpg"))
        assertEquals(listOf("Screenshot_1.png"), TypeFilters.apply(Category.MEDIA, shots, "SCREENSHOTS").map { it.name })
        assertEquals(listOf("IMG_1.jpg"), TypeFilters.apply(Category.MEDIA, shots, "CAMERA").map { it.name })
    }

    @Test fun chosenFilterIsRemembered() {
        val m = mapOf("PHOTOS" to "PNG", "DOCUMENTS" to "PDF")
        assertEquals(m, decodeFilters(encodeFilters(m)))
        assertEquals(emptyMap<String, String>(), decodeFilters(null))
    }

    @Test fun dateSections() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val now = Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.OCTOBER, 31, 11, 0, 0) }.timeInMillis
        val h = 3600_000L
        assertEquals("October 2026", DateSections.label(now - h, DateGrouping.MONTH, utc))
        assertEquals("August 2026", DateSections.label(now - 90 * 24 * h, DateGrouping.MONTH, utc))
        val groups = DateSections.group(listOf(item("a", modified = now - h), item("b", modified = now - 2 * h), item("c", modified = now - 24 * 40 * h)), DateGrouping.MONTH, utc)
        assertEquals(listOf("October 2026" to 2, "September 2026" to 1), groups.map { it.first to it.second.size })
    }

    @Test fun columnsAdaptToWidth() {
        assertEquals(3, columnsFor(FileView.GRID, 393f))
        assertEquals(6, columnsFor(FileView.GRID, 800f))
        assertEquals(2, columnsFor(FileView.LARGE, 393f))
        assertEquals(3, columnsFor(FileView.LARGE, 800f))
    }
}
