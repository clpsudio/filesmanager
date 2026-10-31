package com.clpstudio.filesmanager.data

import android.database.Cursor

/**
 * Null- and column-safe cursor reading. Some devices (and some volumes, e.g. a USB stick or a
 * half-mounted SD card) return cursors that lack a column, hold NULLs, or are closed under us;
 * getColumnIndexOrThrow / getLong on those threw and took the whole query (or the app) down.
 */
fun Cursor.columnOrNull(name: String): Int? = runCatching { getColumnIndex(name) }.getOrNull()?.takeIf { it >= 0 }

fun Cursor.stringAt(index: Int?): String? =
    if (index == null) null else runCatching { if (isNull(index)) null else getString(index) }.getOrNull()

fun Cursor.longAt(index: Int?, default: Long = 0L): Long =
    if (index == null) default else runCatching { if (isNull(index)) default else getLong(index) }.getOrDefault(default)

object CursorRows {
    /**
     * Reads file rows (DATA, DISPLAY_NAME, SIZE, DATE_MODIFIED in seconds) into [out], keyed by path
     * so the same file never appears twice. Rows without a path are skipped; a missing name falls
     * back to the path's last segment; missing size/date read as 0. Returns how many rows were added.
     * A cursor with no DATA column adds nothing (and doesn't throw).
     */
    fun readFiles(
        cursor: Cursor,
        out: MutableMap<String, FileItem>,
        limit: Int = Int.MAX_VALUE,
        accept: (String) -> Boolean = { true },
        dataColumn: String = "_data",
        nameColumn: String = "_display_name",
        sizeColumn: String = "_size",
        dateColumn: String = "date_modified",
    ): Int {
        val iData = cursor.columnOrNull(dataColumn) ?: return 0
        val iName = cursor.columnOrNull(nameColumn)
        val iSize = cursor.columnOrNull(sizeColumn)
        val iDate = cursor.columnOrNull(dateColumn)
        var added = 0
        while (out.size < limit && runCatching { cursor.moveToNext() }.getOrDefault(false)) {
            val path = cursor.stringAt(iData)?.takeIf { it.isNotEmpty() } ?: continue
            if (out.containsKey(path) || !accept(path)) continue
            val name = cursor.stringAt(iName)?.takeIf { it.isNotEmpty() } ?: path.substringAfterLast('/')
            out[path] = FileItem(
                path = path,
                name = name,
                isDir = false,
                size = cursor.longAt(iSize).coerceAtLeast(0),
                modified = cursor.longAt(iDate) * 1000,
            )
            added++
        }
        return added
    }
}
