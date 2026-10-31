package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.DateSections
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.SortBy
import com.clpstudio.filesmanager.data.distinctByPath
import com.clpstudio.filesmanager.data.sortItems
import com.clpstudio.filesmanager.data.uniqueSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Lazy grids crash on duplicate keys; these lists must be unique before drawing. */
class UniqueKeysTest {
    private val now = 1_790_000_000_000L
    private val day = 24L * 3600 * 1000
    private fun f(path: String, modified: Long = now, dir: Boolean = false) =
        FileItem(path, path.substringAfterLast('/'), dir, 1, modified)

    @Test fun distinctByPathKeepsFirstAndOrder() {
        val list = listOf(f("/a"), f("/b"), f("/a", 5), f("/c"), f("/b"))
        val out = list.distinctByPath()
        assertEquals(listOf("/a", "/b", "/c"), out.map { it.path })
        assertEquals(now, out[0].modified)
    }

    @Test fun distinctByPathReturnsSameListWhenUnique() {
        val list = listOf(f("/a"), f("/b"))
        assertSame(list, list.distinctByPath())
    }

    @Test fun folderFirstDateSortNoLongerRepeatsSectionLabels() {
        // Folders first + date sort: folders Today..Older, then files Today..Older again.
        val items = sortItems(
            listOf(f("/d1", now, dir = true), f("/d2", now - 90 * day, dir = true), f("/x.jpg", now), f("/y.jpg", now - 90 * day)),
            SortBy.DATE, ascending = false,
        )
        val raw = DateSections.group(items, now)
        assertEquals(2, raw.count { it.first == "Today" }) // the crash: key "§Today" twice
        val safe = uniqueSections(raw)
        assertEquals(safe.map { it.first }.distinct(), safe.map { it.first })
        assertEquals(listOf("/d1", "/x.jpg"), safe.first { it.first == "Today" }.second.map { it.path })
        assertEquals(4, safe.sumOf { it.second.size })
    }

    @Test fun samePathInTwoSectionsAppearsOnce() {
        val safe = uniqueSections(listOf("Today" to listOf(f("/a")), "Older" to listOf(f("/a"), f("/b"))))
        assertEquals(listOf("/a", "/b"), safe.flatMap { g -> g.second.map { it.path } })
        assertEquals(setOf("Today", "Older"), safe.map { it.first }.toSet())
    }
}
