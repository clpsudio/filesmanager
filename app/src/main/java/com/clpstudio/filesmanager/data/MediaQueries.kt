package com.clpstudio.filesmanager.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class Category(val title: String) {
    RECENT("Recent"),
    PHOTOS("Photos"),
    VIDEOS("Videos"),
    AUDIO("Audios"),
    DOCUMENTS("Documents"),
    DOWNLOADS("Downloads"),
}

/** Category lists from the MediaStore index (fast) plus direct folder walks where needed. */
class MediaQueries(private val context: Context) {

    private val filesUri: Uri = MediaStore.Files.getContentUri("external")

    @Suppress("DEPRECATION")
    private val projection = arrayOf(
        MediaStore.Files.FileColumns.DATA,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
        MediaStore.Files.FileColumns.MIME_TYPE,
    )

    suspend fun category(cat: Category, filter: HiddenFilter): List<FileItem> = withContext(Dispatchers.IO) {
        when (cat) {
            Category.DOWNLOADS -> walk(StoragePaths.downloads(), filter, maxDepth = 8, limit = 20_000)
            Category.RECENT -> {
                val cutoff = System.currentTimeMillis() / 1000 - 30L * 24 * 3600
                val (sel, args) = mediaOrDocsSelection()
                query("($sel) AND ${MediaStore.Files.FileColumns.DATE_MODIFIED} > ?", args + cutoff.toString(), filter, limit = 400)
            }
            Category.PHOTOS -> query(kindSelection("image/", FileTypes.imageExt), kindArgs("image/", FileTypes.imageExt), filter)
            Category.VIDEOS -> query(kindSelection("video/", FileTypes.videoExt), kindArgs("video/", FileTypes.videoExt), filter)
            Category.AUDIO -> query(kindSelection("audio/", FileTypes.audioExt), kindArgs("audio/", FileTypes.audioExt), filter)
            Category.DOCUMENTS -> {
                val exts = FileTypes.documentExt.toList()
                query(extSelection(exts), extArgs(exts), filter)
            }
        }
    }

    /** All .apk (and split-bundle) files on storage. */
    suspend fun apkFiles(filter: HiddenFilter): List<FileItem> = withContext(Dispatchers.IO) {
        val exts = FileTypes.apkExt.toList()
        query(extSelection(exts), extArgs(exts), filter)
    }

    @Suppress("DEPRECATION")
    private fun query(selection: String, args: Array<String>, filter: HiddenFilter, limit: Int = Int.MAX_VALUE): List<FileItem> {
        val out = ArrayList<FileItem>()
        val c: Cursor? = runCatching {
            context.contentResolver.query(
                filesUri, projection, selection, args,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )
        }.getOrNull()
        c?.use { cur ->
            val iData = cur.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val iName = cur.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val iSize = cur.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val iDate = cur.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            while (cur.moveToNext() && out.size < limit) {
                val path = cur.getString(iData) ?: continue
                if (!filter.visiblePath(path)) continue
                val name = cur.getString(iName) ?: path.substringAfterLast('/')
                out += FileItem(
                    path = path,
                    name = name,
                    isDir = false,
                    size = cur.getLong(iSize),
                    modified = cur.getLong(iDate) * 1000,
                )
            }
        }
        return out
    }

    private fun kindSelection(mimePrefix: String, exts: Set<String>): String =
        "(${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR " + extSelection(exts.toList()) + ")"

    private fun kindArgs(mimePrefix: String, exts: Set<String>): Array<String> =
        arrayOf("$mimePrefix%") + extArgs(exts.toList())

    @Suppress("DEPRECATION")
    private fun extSelection(exts: List<String>): String =
        "(" + exts.joinToString(" OR ") { "${MediaStore.Files.FileColumns.DATA} LIKE ?" } + ")"

    private fun extArgs(exts: List<String>): Array<String> = exts.map { "%.$it" }.toTypedArray()

    private fun mediaOrDocsSelection(): Pair<String, Array<String>> {
        val exts = (FileTypes.documentExt + FileTypes.apkExt + FileTypes.archiveExt).toList()
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
