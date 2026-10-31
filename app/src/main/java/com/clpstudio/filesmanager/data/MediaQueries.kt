package com.clpstudio.filesmanager.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.clpstudio.filesmanager.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

enum class Category(val title: String) {
    RECENT("Recent"),
    PHOTOS("Photos"),
    MEDIA("Media"),
    VIDEOS("Videos"),
    AUDIO("Audios"),
    DOCUMENTS("Documents"),
    DOWNLOADS("Downloads"),
}

/** One step of a category load: [done] = false while results are still streaming in. */
data class CategoryLoad(val items: List<FileItem>, val done: Boolean)

/**
 * Category lists. Photos / videos / audio come from the MediaStore index (fast), with a direct
 * file walk if the index fails or is empty. Documents, Downloads and APKs never rely on the index
 * alone: it often misses non-media files or has no MIME type for them, so a direct walk is merged
 * in (streamed while it runs). Errors are logged to Diagnostics, never shown as an empty list.
 */
class MediaQueries(private val context: Context) {

    @Suppress("DEPRECATION")
    private val projection = arrayOf(
        MediaStore.Files.FileColumns.DATA,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
        MediaStore.Files.FileColumns.MIME_TYPE,
    )

    /** Every external volume (internal storage, SD card, USB) has its own index on Android 10+. */
    private fun volumeUris(): List<Uri> {
        if (Build.VERSION.SDK_INT >= 29) {
            val names = runCatching { MediaStore.getExternalVolumeNames(context).toList() }
                .onFailure { Diagnostics.log("media", "getExternalVolumeNames failed", it) }
                .getOrNull().orEmpty()
            if (names.isNotEmpty()) return names.map { MediaStore.Files.getContentUri(it) }
        }
        return listOf(MediaStore.Files.getContentUri("external"))
    }

    /** Streams the category: first the fast results, then the complete list. */
    fun categoryFlow(cat: Category, filter: HiddenFilter): Flow<CategoryLoad> = flow<CategoryLoad> {
        val t0 = System.currentTimeMillis()
        when (cat) {
            Category.PHOTOS, Category.VIDEOS, Category.AUDIO -> {
                val (prefix, exts) = when (cat) {
                    Category.PHOTOS -> "image/" to FileTypes.imageExt + FileTypes.rawExt
                    Category.VIDEOS -> "video/" to FileTypes.videoExt
                    else -> "audio/" to FileTypes.audioExt
                }
                val indexed = query(kindSelection(exts), kindArgs(prefix, exts), filter)
                val list = indexed.getOrNull()
                if (list != null && list.isNotEmpty()) {
                    emit(CategoryLoad(list, true))
                } else {
                    // Index failed or empty: look on storage directly.
                    if (list != null) emit(CategoryLoad(emptyList(), false))
                    val walked = walkVolumes(filter) { FileTypes.extension(it) in exts }
                    Diagnostics.log("media", "${cat.title}: index ${if (list == null) "failed" else "empty"}, direct walk found ${walked.size}")
                    emit(CategoryLoad(walked.sortedByDescending { it.modified }, true))
                }
            }
            Category.MEDIA -> {
                // All photos and videos together, newest first (both queries run at the same time).
                val (photos, videos) = kotlinx.coroutines.coroutineScope {
                    val p = async { categoryFlow(Category.PHOTOS, filter).first { it.done }.items }
                    val v = async { categoryFlow(Category.VIDEOS, filter).first { it.done }.items }
                    p.await() to v.await()
                }
                emit(CategoryLoad((photos + videos).sortedByDescending { it.modified }, true))
            }
            Category.DOCUMENTS -> {
                val docFilter = filter.withoutNoMedia()
                val indexed = query(extSelection(FileTypes.documentExt), extArgs(FileTypes.documentExt), docFilter).getOrNull().orEmpty()
                if (indexed.isNotEmpty()) emit(CategoryLoad(indexed, false))
                Graph.index.observe(filter.showHidden).collect { snap ->
                    val walked = snap.items.filter { FileTypes.isDocument(it.name) && docFilter.visiblePath(it.path) }
                    emit(CategoryLoad(StorageWalker.merge(walked, indexed), snap.complete))
                }
            }
            Category.DOWNLOADS -> {
                val dl = StoragePaths.downloads()
                val list = walk(dl, filter.withoutNoMedia(), maxDepth = 8, limit = 20_000)
                if (list.isEmpty() && !dl.isDirectory) Diagnostics.log("media", "Downloads folder not found: ${dl.path}")
                // An SD card's own Download folder joins in (only while "Show SD card" is on).
                val cardLists = StoragePaths.volumes(context).filter { it.removable }.flatMap { v ->
                    val cardDl = File(v.path, Environment.DIRECTORY_DOWNLOADS)
                    if (cardDl.isDirectory) runCatching { walk(cardDl, filter.withoutNoMedia(), maxDepth = 8, limit = 20_000) }.getOrDefault(emptyList()) else emptyList()
                }
                emit(CategoryLoad(if (cardLists.isEmpty()) list else (list + cardLists).sortedByDescending { it.modified }, true))
            }
            Category.RECENT -> {
                val cutoffSec = System.currentTimeMillis() / 1000 - 30L * 24 * 3600
                val (sel, args) = mediaOrDocsSelection()
                val indexed = query("($sel) AND ${MediaStore.Files.FileColumns.DATE_MODIFIED} > ?", args + cutoffSec.toString(), filter, limit = 400)
                    .getOrNull().orEmpty()
                emit(CategoryLoad(indexed, false))
                val recentDocs = Graph.index.complete(filter.showHidden)
                    .filter { it.modified / 1000 > cutoffSec && filter.withoutNoMedia().visiblePath(it.path) }
                emit(CategoryLoad(StorageWalker.merge(indexed, recentDocs).take(400), true))
            }
        }
        Diagnostics.log("media", "${cat.title} loaded in ${System.currentTimeMillis() - t0} ms")
    }.let { upstream ->
        // Time to the first non-empty items (shown in Settings > Diagnostics).
        var t0 = 0L
        var first = true
        upstream.map { load -> load.copy(items = FileEvents.filter(load.items)) }.onStart { t0 = System.currentTimeMillis() }.onEach { load ->
            if (first && (load.items.isNotEmpty() || load.done)) {
                first = false
                Diagnostics.timing(cat.title, System.currentTimeMillis() - t0, load.items.size)
            }
        }
    }.flowOn(Dispatchers.IO)

    /** The complete list (Home counts and other one-shot uses). */
    suspend fun category(cat: Category, filter: HiddenFilter): List<FileItem> =
        categoryFlow(cat, filter).first { it.done }.items

    /** All .apk (and split-bundle) files on storage: the index merged with a direct walk. */
    suspend fun apkFiles(filter: HiddenFilter): List<FileItem> = withContext(Dispatchers.IO) {
        val f = filter.withoutNoMedia()
        val indexed = query(extSelection(FileTypes.apkExt), extArgs(FileTypes.apkExt), f).getOrNull().orEmpty()
        // Never throws (the Apps screen loads this without a guard): a failed walk keeps the index results.
        val walked = try {
            Graph.index.complete(filter.showHidden).filter { FileTypes.isApk(it.name) && f.visiblePath(it.path) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Diagnostics.log("media", "APK walk failed", e)
            emptyList()
        }
        StorageWalker.merge(walked, indexed)
    }

    private fun walkVolumes(filter: HiddenFilter, accept: (String) -> Boolean): List<FileItem> =
        StorageWalker.walk(
            roots = StoragePaths.volumes(context).map { File(it.path) },
            showHidden = filter.showHidden,
            privateRoots = filter.privateRoots,
            accept = accept,
        ).filter { filter.visiblePath(it.path) }

    /** Queries every volume; fails only if every volume failed (then it's logged, never "empty"). */
    @Suppress("DEPRECATION")
    private fun query(selection: String, args: Array<String>, filter: HiddenFilter, limit: Int = Int.MAX_VALUE): Result<List<FileItem>> {
        val out = LinkedHashMap<String, FileItem>()
        var anyOk = false
        var lastError: Throwable? = null
        for (uri in volumeUris()) {
            for (attempt in 0..1) {
                try {
                    val c: Cursor? = context.contentResolver.query(
                        uri, projection, selection, args, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
                    )
                    if (c == null) throw IllegalStateException("MediaStore returned no cursor for $uri")
                    c.use { cur ->
                        // Column-safe: a missing column or NULL value no longer fails the whole volume.
                        CursorRows.readFiles(
                            cur, out, limit = limit, accept = { filter.visiblePath(it) },
                            dataColumn = MediaStore.Files.FileColumns.DATA,
                            nameColumn = MediaStore.Files.FileColumns.DISPLAY_NAME,
                            sizeColumn = MediaStore.Files.FileColumns.SIZE,
                            dateColumn = MediaStore.Files.FileColumns.DATE_MODIFIED,
                        )
                    }
                    anyOk = true
                    break
                } catch (e: SecurityException) {
                    // All files access / media permission revoked: retrying won't help.
                    lastError = e
                    Diagnostics.log("media", "no permission to query $uri", e)
                    break
                } catch (e: Exception) {
                    lastError = e
                    Diagnostics.log("media", "query failed on $uri (attempt ${attempt + 1})", e)
                    if (attempt == 0) Thread.sleep(250)
                }
            }
        }
        return if (anyOk) Result.success(out.values.sortedByDescending { it.modified })
        else Result.failure(lastError ?: IllegalStateException("no volumes"))
    }

    @Suppress("DEPRECATION")
    private fun kindSelection(exts: Set<String>): String =
        "(${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR " + extSelection(exts) + ")"

    private fun kindArgs(mimePrefix: String, exts: Set<String>): Array<String> =
        arrayOf("$mimePrefix%") + extArgs(exts)

    /** Matches by extension on both the path and the display name (MIME_TYPE is often null). */
    @Suppress("DEPRECATION")
    private fun extSelection(exts: Set<String>): String =
        "(" + exts.joinToString(" OR ") {
            "${MediaStore.Files.FileColumns.DATA} LIKE ? OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        } + ")"

    private fun extArgs(exts: Set<String>): Array<String> = exts.flatMap { listOf("%.$it", "%.$it") }.toTypedArray()

    private fun mediaOrDocsSelection(): Pair<String, Array<String>> {
        val exts = FileTypes.documentExt + FileTypes.apkExt + FileTypes.archiveExt
        val sel = "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR " +
            "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR " + extSelection(exts)
        return sel to (arrayOf("image/%", "video/%", "audio/%") + extArgs(exts))
    }

    companion object {
        /** Recursive file walk (files only), skipping what [filter] hides. */
        fun walk(root: File, filter: HiddenFilter, maxDepth: Int, limit: Int): List<FileItem> {
            val out = ArrayList<FileItem>()
            if (!root.exists()) return out
            root.walkTopDown()
                .maxDepth(maxDepth)
                .onEnter { dir -> dir == root || filter.visibleEntry(dir) }
                .forEach { f ->
                    if (out.size >= limit) return out
                    if (f.isFile && filter.visibleEntry(f)) out += FileItem.of(f)
                }
            return out.sortedByDescending { it.modified }
        }
    }
}
