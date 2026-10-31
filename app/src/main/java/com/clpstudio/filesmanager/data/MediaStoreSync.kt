package com.clpstudio.filesmanager.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * Keeps the system media index in step with what the app removed. A scan of a path that no longer
 * exists is slow and asynchronous (and a moved folder's files are never rescanned), so stale rows
 * kept deleted photos in Photos / Videos / Recent. Here the rows of removed paths are deleted
 * directly, only when the path is really gone (MediaStore would otherwise delete the file too).
 */
object MediaStoreSync {

    /** Escapes LIKE wildcards in a path (used with ESCAPE '\\'). */
    fun likePrefix(path: String): String =
        path.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "/%"

    private fun volumeUris(context: Context): List<Uri> =
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { MediaStore.getExternalVolumeNames(context).map { MediaStore.Files.getContentUri(it) } }
                .getOrNull().orEmpty().ifEmpty { listOf(MediaStore.Files.getContentUri("external")) }
        } else {
            listOf(MediaStore.Files.getContentUri("external"))
        }

    /** Deletes index rows for [paths] (and everything under them) that are gone from storage. Returns rows removed. */
    fun forget(context: Context, paths: Collection<String>): Int {
        val gone = paths.filter { it.isNotEmpty() && !File(it).exists() }.distinct()
        if (gone.isEmpty()) return 0
        val resolver = context.applicationContext.contentResolver
        val uris = volumeUris(context)
        var rows = 0
        val col = MediaStore.MediaColumns.DATA
        for (p in gone) {
            for (uri in uris) {
                rows += runCatching {
                    resolver.delete(uri, "$col = ? OR $col LIKE ? ESCAPE '\\'", arrayOf(p, likePrefix(p)))
                }.onFailure { Diagnostics.log("media", "couldn't drop index rows for $p", it) }.getOrDefault(0)
            }
        }
        return rows
    }
}
