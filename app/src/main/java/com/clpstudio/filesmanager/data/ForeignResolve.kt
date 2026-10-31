package com.clpstudio.filesmanager.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream

/** What a foreign content:// uri says about itself (any column may be missing). */
data class ForeignMeta(
    val name: String? = null,
    val size: Long = -1L,
    /** Real path (MediaStore's _data, or volume + relative_path + name). */
    val path: String? = null,
    val mime: String? = null,
    val pending: Boolean = false,
    val title: String? = null,
)

/**
 * Finds the real file behind another app's content:// uri (item 28). Samsung Camera's preview hands over
 * content://secmedia/media/<id>, a provider that answers no display name; the same photo is a normal MediaStore row
 * (same id, or the id minus 2^31 on some Samsung builds), which we can read with All files access.
 * A guessed row is only used when its first bytes are the uri's first bytes, so a wrong row never opens.
 */
object ForeignResolve {
    const val HEAD_BYTES = 4096
    /** Samsung's secure-media ids are MediaStore ids plus 2^31 on some builds. */
    const val SAMSUNG_ID_OFFSET = 2_147_483_648L
    private const val RECENT_ROWS = 40

    fun isOwnAuthority(context: Context, uri: Uri): Boolean = uri.authority?.startsWith(context.packageName) == true

    /** The MediaStore ids worth trying for [uri]'s last path segment, best first (pure, unit tested). */
    fun candidateIds(uri: Uri): List<Long> {
        val id = uri.lastPathSegment?.toLongOrNull() ?: return emptyList()
        if (id <= 0) return emptyList()
        return if (id >= SAMSUNG_ID_OFFSET) listOf(id, id - SAMSUNG_ID_OFFSET) else listOf(id)
    }

    /** Same leading bytes (at least 16, so a few header bytes alone never match). */
    fun headsMatch(a: ByteArray?, b: ByteArray?): Boolean {
        if (a == null || b == null) return false
        val n = minOf(a.size, b.size)
        if (n < 16) return false
        for (i in 0 until n) if (a[i] != b[i]) return false
        return true
    }

    /** "2147494522" + image/jpeg -> "IMG_2147494522.jpg"; a provider title wins when there is one. */
    fun nameFor(segment: String, title: String?, mime: String): String {
        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: when (mime) {
            "image/jpeg" -> "jpg"; "image/heic" -> "heic"; "image/png" -> "png"; "video/mp4" -> "mp4"; else -> null
        }
        val base = title?.takeIf { it.isNotBlank() }?.substringBeforeLast('.')
            ?: when {
                mime.startsWith("image/") -> "IMG_$segment"
                mime.startsWith("video/") -> "VID_$segment"
                else -> segment
            }
        return if (ext != null) "$base.$ext" else base
    }

    fun headOf(input: InputStream): ByteArray? {
        val buf = ByteArray(HEAD_BYTES)
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return if (n > 0) buf.copyOf(n) else null
    }

    fun headOf(file: File): ByteArray? = runCatching { if (file.isFile) file.inputStream().use { headOf(it) } else null }.getOrNull()

    /** Every column we know how to read, from whatever projection the provider accepts. Never throws. */
    fun queryMeta(cr: ContentResolver, uri: Uri): ForeignMeta {
        val projections = listOf<Array<String>?>(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null)
        var best = ForeignMeta()
        for (proj in projections) {
            val m = runCatching { cr.query(uri, proj, null, null, null)?.use { c -> if (c.moveToFirst()) readRow(c) else null } }.getOrNull() ?: continue
            best = best.copy(
                name = best.name ?: m.name, size = if (best.size >= 0) best.size else m.size, path = best.path ?: m.path,
                mime = best.mime ?: m.mime, pending = best.pending || m.pending, title = best.title ?: m.title,
            )
            if (best.name != null && best.path != null) break
        }
        return best
    }

    private fun Cursor.str(vararg cols: String): String? {
        for (c in cols) {
            val i = getColumnIndex(c)
            if (i >= 0 && !isNull(i)) getString(i)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun Cursor.long(col: String): Long? {
        val i = getColumnIndex(col)
        return if (i >= 0 && !isNull(i)) runCatching { getLong(i) }.getOrNull() else null
    }

    fun readRow(c: Cursor): ForeignMeta {
        val name = c.str(OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.DISPLAY_NAME)
        val data = c.str(MediaStore.MediaColumns.DATA)
        val rel = c.str(MediaStore.MediaColumns.RELATIVE_PATH)
        val volume = c.str(MediaStore.MediaColumns.VOLUME_NAME)
        val path = data ?: if (rel != null && name != null) volumeRoot(volume)?.let { File(File(it, rel), name).absolutePath } else null
        return ForeignMeta(
            name = name ?: data?.substringAfterLast('/'),
            size = c.long(OpenableColumns.SIZE) ?: c.long(MediaStore.MediaColumns.SIZE) ?: -1L,
            path = path,
            mime = c.str(MediaStore.MediaColumns.MIME_TYPE),
            pending = (c.long(MediaStore.MediaColumns.IS_PENDING) ?: 0L) != 0L,
            title = c.str(MediaStore.MediaColumns.TITLE),
        )
    }

    private fun volumeRoot(volume: String?): String? = when (volume) {
        null, MediaStore.VOLUME_EXTERNAL, MediaStore.VOLUME_EXTERNAL_PRIMARY -> Environment.getExternalStorageDirectory().absolutePath
        else -> File("/storage", volume.uppercase()).takeIf { it.isDirectory }?.absolutePath
            ?: File("/storage", volume).takeIf { it.isDirectory }?.absolutePath
    }

    private val filesUri: Uri get() = MediaStore.Files.getContentUri("external")

    /** The MediaStore row with this id, if any. */
    fun mediaRow(cr: ContentResolver, id: Long): ForeignMeta? = runCatching {
        cr.query(ContentUris.withAppendedId(filesUri, id), null, null, null, null)?.use { c -> if (c.moveToFirst()) readRow(c) else null }
            ?: cr.query(filesUri, null, "${MediaStore.MediaColumns._ID}=?", arrayOf(id.toString()), null)?.use { c -> if (c.moveToFirst()) readRow(c) else null }
    }.getOrNull()

    /**
     * The file on storage behind [uri], or null. [head] = the uri's first bytes when it can be read. Order:
     * the provider's own path, the MediaStore row with the same id (or id - 2^31), then the newest photos and videos
     * whose bytes start the same.
     */
    fun findRealFile(cr: ContentResolver, uri: Uri, meta: ForeignMeta, head: ByteArray?): File? {
        meta.path?.let { File(it) }?.takeIf { it.isFile && it.canRead() }?.let { return it }
        val ids = candidateIds(uri)
        ids.forEachIndexed { i, id ->
            val f = mediaRow(cr, id)?.path?.let { File(it) }?.takeIf { it.isFile && it.canRead() } ?: return@forEachIndexed
            // Same id as the uri: trust it unless the bytes disagree. A shifted id must prove it with the bytes.
            val ok = if (head == null) i == 0 else headsMatch(head, headOf(f))
            if (ok) return f
        }
        if (head == null) return null
        return recentRows(cr).firstOrNull { m ->
            val f = m.path?.let { File(it) } ?: return@firstOrNull false
            f.isFile && (meta.size <= 0 || f.length() == meta.size) && headsMatch(head, headOf(f))
        }?.path?.let { File(it) }
    }

    private fun recentRows(cr: ContentResolver): List<ForeignMeta> = runCatching {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?,?)")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            ))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, RECENT_ROWS)
        }
        cr.query(filesUri, null, args, null)?.use { c ->
            val out = ArrayList<ForeignMeta>()
            while (c.moveToNext() && out.size < RECENT_ROWS) out += readRow(c)
            out
        } ?: emptyList()
    }.getOrDefault(emptyList())
}
