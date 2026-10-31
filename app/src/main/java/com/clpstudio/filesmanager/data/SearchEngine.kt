package com.clpstudio.filesmanager.data

import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.coroutineContext

enum class TypeFilter(val label: String, val kinds: Set<FileKind>) {
    PHOTOS("Photos", setOf(FileKind.IMAGE)),
    VIDEOS("Videos", setOf(FileKind.VIDEO)),
    AUDIO("Audio", setOf(FileKind.AUDIO)),
    DOCS("Docs", setOf(FileKind.PDF, FileKind.TEXT, FileKind.DOCUMENT)),
    APK("APK", setOf(FileKind.APK)),
    ARCHIVES("Archives", setOf(FileKind.ARCHIVE)),
}

data class SearchQuery(
    val name: String,
    val range: DateRange?,
    val types: Set<TypeFilter> = emptySet(),
    /** Search only under this folder (null = all storage). */
    val root: String? = null,
) {
    val isEmpty: Boolean get() = name.isBlank() && range == null && types.isEmpty()

    fun matches(item: FileItem, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (!DateQueryParser.nameMatches(item.name, name)) return false
        if (types.isNotEmpty() && types.none { item.kind in it.kinds }) return false
        if (range != null) {
            val d = Instant.ofEpochMilli(item.modified).atZone(zone).toLocalDate()
            if (!range.contains(d)) return false
        }
        if (root != null && !(item.path == root || item.path.startsWith("$root/"))) return false
        return true
    }
}

/**
 * Fast search: the MediaStore index answers first (milliseconds), then a background walk of the
 * storage adds what the index doesn't know (hidden files when allowed, folders, unindexed files).
 * Results are emitted incrementally, newest first.
 */
class SearchEngine(private val context: Context) {

    fun search(query: SearchQuery, filter: HiddenFilter, roots: List<String>): Flow<List<FileItem>> = channelFlow {
        if (query.isEmpty) {
            send(emptyList())
            return@channelFlow
        }
        val found = LinkedHashMap<String, FileItem>()
        fun snapshot() = found.values.sortedByDescending { it.modified }

        // 1) MediaStore index.
        indexed(query, filter).forEach { found[it.path] = it }
        send(snapshot())

        // 2) Walk (incremental).
        var lastEmit = System.currentTimeMillis()
        var dirty = false
        val walkRoots = query.root?.let { listOf(it) } ?: roots
        for (root in walkRoots) {
            File(root).walkTopDown()
                .onEnter { dir ->
                    dir.absolutePath == root || (filter.visibleEntry(dir) && !isAppData(dir.absolutePath))
                }
                .forEach { f ->
                    coroutineContext.ensureActive()
                    if (f.absolutePath == root) return@forEach
                    if (!filter.visibleEntry(f)) return@forEach
                    if (!DateQueryParser.nameMatches(f.name, query.name)) return@forEach
                    if (f.isDirectory && (query.types.isNotEmpty() || query.name.isBlank())) return@forEach
                    val item = FileItem.of(f)
                    if (query.matches(item) && found.put(item.path, item) == null) dirty = true
                    val now = System.currentTimeMillis()
                    if (dirty && now - lastEmit > 250) {
                        send(snapshot())
                        lastEmit = now
                        dirty = false
                    }
                }
        }
        send(snapshot())
    }.flowOn(Dispatchers.IO)

    private fun isAppData(path: String) = path.contains("/Android/data") || path.contains("/Android/obb")

    @Suppress("DEPRECATION")
    private fun indexed(query: SearchQuery, filter: HiddenFilter): List<FileItem> {
        val sel = StringBuilder("1=1")
        val args = ArrayList<String>()
        query.name.split(' ').filter { it.isNotBlank() }.forEach { word ->
            sel.append(" AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'")
            args += "%" + word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        query.range?.let { r ->
            val zone = ZoneId.systemDefault()
            val from = r.start.atStartOfDay(zone).toEpochSecond()
            val to = r.endInclusive.plusDays(1).atStartOfDay(zone).toEpochSecond()
            sel.append(" AND ${MediaStore.Files.FileColumns.DATE_MODIFIED} >= ? AND ${MediaStore.Files.FileColumns.DATE_MODIFIED} < ?")
            args += from.toString()
            args += to.toString()
        }
        query.root?.let {
            sel.append(" AND ${MediaStore.Files.FileColumns.DATA} LIKE ?")
            args += "$it/%"
        }
        val out = ArrayList<FileItem>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(
                    MediaStore.Files.FileColumns.DATA,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATE_MODIFIED,
                    MediaStore.Files.FileColumns.MIME_TYPE,
                ),
                sel.toString(), args.toTypedArray(),
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 5000) {
                    val path = c.getString(0) ?: continue
                    if (!filter.visiblePath(path)) continue
                    val name = c.getString(1) ?: path.substringAfterLast('/')
                    val isDir = c.getString(4) == null && File(path).isDirectory
                    val item = FileItem(path, name, isDir, c.getLong(2), c.getLong(3) * 1000)
                    if (query.matches(item)) out += item
                }
            }
        }
        return out
    }

    companion object {
        fun today(): LocalDate = LocalDate.now()
    }
}
