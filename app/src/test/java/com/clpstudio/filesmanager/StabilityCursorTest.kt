package com.clpstudio.filesmanager

import android.app.Application
import android.database.MatrixCursor
import com.clpstudio.filesmanager.data.CursorRows
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.columnOrNull
import com.clpstudio.filesmanager.data.longAt
import com.clpstudio.filesmanager.data.stringAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cursor rows with missing columns / NULLs must never throw (they used to fail the whole query). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StabilityCursorTest {
    private val cols = arrayOf("_data", "_display_name", "_size", "date_modified")

    @Test fun nullValuesAreSkippedOrDefaulted() {
        val c = MatrixCursor(cols).apply {
            addRow(arrayOf<Any?>("/s/a.jpg", "a.jpg", 10L, 100L))
            addRow(arrayOf<Any?>(null, "nopath.jpg", 5L, 1L)) // no path: skipped
            addRow(arrayOf<Any?>("/s/b.jpg", null, null, null)) // name from path, size/date 0
            addRow(arrayOf<Any?>("", "empty.jpg", 1L, 1L)) // empty path: skipped
        }
        val out = LinkedHashMap<String, FileItem>()
        val added = CursorRows.readFiles(c, out)
        assertEquals(2, added)
        assertEquals(listOf("/s/a.jpg", "/s/b.jpg"), out.keys.toList())
        assertEquals(100_000L, out["/s/a.jpg"]!!.modified)
        val b = out["/s/b.jpg"]!!
        assertEquals("b.jpg", b.name)
        assertEquals(0L, b.size)
        assertEquals(0L, b.modified)
    }

    @Test fun missingColumnsDoNotThrow() {
        // Only DATA present.
        val c = MatrixCursor(arrayOf("_data")).apply { addRow(arrayOf<Any?>("/s/x.pdf")) }
        val out = LinkedHashMap<String, FileItem>()
        assertEquals(1, CursorRows.readFiles(c, out))
        assertEquals("x.pdf", out["/s/x.pdf"]!!.name)
        // No DATA column at all: nothing added, no exception.
        val noData = MatrixCursor(arrayOf("_display_name")).apply { addRow(arrayOf<Any?>("y.pdf")) }
        assertEquals(0, CursorRows.readFiles(noData, LinkedHashMap()))
    }

    @Test fun duplicatesLimitAndFilter() {
        val c = MatrixCursor(cols).apply {
            addRow(arrayOf<Any?>("/s/a.jpg", "a.jpg", 1L, 3L))
            addRow(arrayOf<Any?>("/s/a.jpg", "a.jpg", 1L, 3L)) // same file twice (two volumes)
            addRow(arrayOf<Any?>("/s/.hidden/c.jpg", "c.jpg", 1L, 2L))
            addRow(arrayOf<Any?>("/s/d.jpg", "d.jpg", 1L, 1L))
            addRow(arrayOf<Any?>("/s/e.jpg", "e.jpg", 1L, 1L))
        }
        val out = LinkedHashMap<String, FileItem>()
        CursorRows.readFiles(c, out, limit = 2, accept = { !it.contains("/.") })
        assertEquals(listOf("/s/a.jpg", "/s/d.jpg"), out.keys.toList())
    }

    @Test fun safeAccessors() {
        val c = MatrixCursor(arrayOf("a", "b")).apply { addRow(arrayOf<Any?>(null, "7")) }
        c.moveToFirst()
        assertNull(c.columnOrNull("missing"))
        assertNull(c.stringAt(c.columnOrNull("a")))
        assertNull(c.stringAt(null))
        assertEquals(7L, c.longAt(c.columnOrNull("b")))
        assertEquals(-1L, c.longAt(null, -1L))
        // A cursor whose reads throw (closed under us, a broken row) returns defaults instead.
        val broken = object : MatrixCursor(arrayOf("a")) {
            override fun getString(column: Int): String = throw IllegalStateException("closed")
            override fun getLong(column: Int): Long = throw IllegalStateException("closed")
        }.apply { addRow(arrayOf<Any?>("x")); moveToFirst() }
        assertNull(broken.stringAt(0))
        assertEquals(3L, broken.longAt(0, 3L))
    }
}
