package com.clpstudio.filesmanager.data

import androidx.compose.runtime.Immutable
import java.io.File

@Immutable
data class FileItem(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val kind: FileKind = FileTypes.kindOf(name, isDir),
    /** Number of entries inside a folder, -1 when not counted. */
    val childCount: Int = -1,
) {
    val file: File get() = File(path)
    val parentPath: String get() = path.substringBeforeLast('/', "")
    val parentName: String get() = parentPath.substringAfterLast('/')
    val extension: String get() = if (isDir) "" else FileTypes.extension(name)
    val isHidden: Boolean get() = name.startsWith(".")

    companion object {
        fun of(file: File, countChildren: Boolean = false): FileItem {
            val dir = file.isDirectory
            return FileItem(
                path = file.absolutePath,
                name = file.name,
                isDir = dir,
                size = if (dir) 0L else file.length(),
                modified = file.lastModified(),
                childCount = if (dir && countChildren) (file.list()?.size ?: 0) else -1,
            )
        }
    }
}

enum class SortBy { NAME, DATE, SIZE, TYPE }

/** Natural, case-insensitive name order ("file2" before "file10"). */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

fun sortItems(items: List<FileItem>, by: SortBy, ascending: Boolean, foldersFirst: Boolean = true): List<FileItem> {
    val base: Comparator<FileItem> = when (by) {
        SortBy.NAME -> Comparator { x, y -> NaturalOrder.compare(x.name, y.name) }
        SortBy.DATE -> compareBy<FileItem> { it.modified }.thenComparator { x, y -> NaturalOrder.compare(x.name, y.name) }
        SortBy.SIZE -> compareBy<FileItem> { it.size }.thenComparator { x, y -> NaturalOrder.compare(x.name, y.name) }
        SortBy.TYPE -> compareBy<FileItem> { it.extension }.thenComparator { x, y -> NaturalOrder.compare(x.name, y.name) }
    }
    val ordered = if (ascending) base else base.reversed()
    val cmp = if (foldersFirst) compareBy<FileItem> { !it.isDir }.then(ordered) else ordered
    return items.sortedWith(cmp)
}

/** One folder inside a category (Photos, Videos, ...). */
@Immutable
data class FolderGroup(
    val path: String,
    val name: String,
    val items: List<FileItem>,
) {
    val count: Int get() = items.size
    val totalSize: Long get() = items.sumOf { it.size }
    val latest: Long get() = items.maxOfOrNull { it.modified } ?: 0L
}

fun groupByFolder(items: List<FileItem>): List<FolderGroup> =
    items.groupBy { it.parentPath }
        .map { (path, list) ->
            FolderGroup(path, path.substringAfterLast('/').ifEmpty { "/" }, list.sortedByDescending { it.modified })
        }
        .sortedByDescending { it.latest }

/** "name (1).ext", "name (2).ext"... until [exists] says the name is free. */
fun uniqueName(name: String, isDir: Boolean, exists: (String) -> Boolean): String {
    if (!exists(name)) return name
    val dot = if (isDir) -1 else name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var n = 1
    while (true) {
        val candidate = "$base ($n)$ext"
        if (!exists(candidate)) return candidate
        n++
    }
}

fun isValidFileName(name: String): Boolean {
    val t = name.trim()
    if (t.isEmpty() || t == "." || t == "..") return false
    if (t.length > 255) return false
    return t.none { it == '/' || it == '\u0000' }
}
