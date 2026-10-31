package com.clpstudio.filesmanager.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A file another app moved to Android's trash (MediaStore IS_TRASHED, Android 11+), or a file
 * sitting in a readable public trash folder (".Trash", "DCIM/.trash").
 */
data class TrashItem(
    /** Stable key: "ms:<volume>:<id>" for MediaStore rows, "fs:<path>" for folder files. */
    val key: String,
    /** The original name, without the ".trashed-<time>-" prefix. */
    val name: String,
    /** Where the bytes are now (readable with All files access), for thumbnails. */
    val path: String?,
    /** The MediaStore item, null for folder files. */
    val uri: Uri?,
    val size: Long,
    val isDir: Boolean,
    /** Best guess of when it was trashed (expiry − 30 days, else the file's date). */
    val deletedAt: Long,
    /** When Android deletes it for good, if known. */
    val expiresAt: Long?,
    val mimeType: String? = null,
    /** True when MediaStore still flags it as trashed (restore through MediaStore), false for a loose file. */
    val mediaStoreTrashed: Boolean = uri != null,
    /** The app that put it there, when MediaStore knows (OWNER_PACKAGE_NAME). */
    val ownerPackage: String? = null,
) {
    fun daysLeft(now: Long = System.currentTimeMillis()): Int? =
        expiresAt?.let { SystemTrash.daysUntil(it, now) }
}

/** What happened after a restore/delete request. */
data class TrashOutcome(
    val done: Int,
    /** Android wants the user to confirm the rest: launch this with an IntentSender launcher. */
    val confirm: IntentSender? = null,
    val failed: Int = 0,
)

class SystemTrash(private val context: Context) {

    companion object {
        private val DAY = TimeUnit.DAYS.toMillis(1)
        val RETENTION_MS: Long = TimeUnit.DAYS.toMillis(30)
        private val TRASHED = Regex("""^\.trashed-(\d{9,13})-(.+)$""")

        /** True where MediaStore has a trash (Android 11+). */
        val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        /**
         * Parses Android's trashed file name ".trashed-<expiry seconds>-<original name>".
         * Returns the original name and the expiry in millis, or null if it isn't one.
         */
        fun parseTrashedName(fileName: String): Pair<String, Long>? {
            val m = TRASHED.matchEntire(fileName) ?: return null
            val raw = m.groupValues[1].toLongOrNull() ?: return null
            val millis = if (m.groupValues[1].length >= 13) raw else raw * 1000L
            val name = m.groupValues[2].ifBlank { return null }
            return name to millis
        }

        /** The name with any ".trashed-<time>-" prefix removed. */
        fun originalName(fileName: String): String = parseTrashedName(fileName)?.first ?: fileName

        /** Whole days left until [expiresAt], rounded up (same rule as our own Bin), never below 0. */
        fun daysUntil(expiresAt: Long, now: Long = System.currentTimeMillis()): Int {
            val left = expiresAt - now
            return if (left <= 0) 0 else ((left + DAY - 1) / DAY).toInt()
        }

        /** Public trash folders some apps use. Only the readable ones are listed. */
        fun publicTrashDirs(root: File = Environment.getExternalStorageDirectory()): List<File> = listOf(
            File(root, ".Trash"),
            File(root, ".trash"),
            File(root, "DCIM/.trash"),
            File(root, "DCIM/.Trash"),
            File(root, "Pictures/.trash"),
        )
    }

    /** Everything other apps trashed. Empty below Android 11. */
    suspend fun list(): List<TrashItem> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, TrashItem>()
        val seenPaths = HashSet<String>()
        if (supported) {
            runCatching { queryMediaStore(onlyTrashed = true) }.getOrNull()?.forEach { add(out, seenPaths, it) }
            // Files renamed ".trashed-…" whose flag got lost (e.g. after a restore from a backup).
            runCatching { queryMediaStore(onlyTrashed = false) }.getOrNull()?.forEach { add(out, seenPaths, it) }
        }
        if (supported) {
            for (dir in publicTrashDirs()) {
                val kids = runCatching { dir.listFiles() }.getOrNull() ?: continue
                for (f in kids) {
                    if (f.name == ".nomedia" || !seenPaths.add(f.absolutePath)) continue
                    val parsed = parseTrashedName(f.name)
                    val expires = parsed?.second
                    out["fs:" + f.absolutePath] = TrashItem(
                        key = "fs:" + f.absolutePath,
                        name = parsed?.first ?: f.name,
                        path = f.absolutePath,
                        uri = null,
                        size = if (f.isDirectory) FileOps.sizeOf(f) else f.length(),
                        isDir = f.isDirectory,
                        deletedAt = expires?.minus(RETENTION_MS) ?: f.lastModified(),
                        expiresAt = expires,
                        mediaStoreTrashed = false,
                    )
                }
            }
        }
        out.values.sortedByDescending { it.deletedAt }
    }

    private fun add(out: MutableMap<String, TrashItem>, seen: MutableSet<String>, item: TrashItem) {
        if (out.containsKey(item.key)) return
        if (item.path != null && !seen.add(item.path)) return
        out[item.key] = item
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun queryMediaStore(onlyTrashed: Boolean): List<TrashItem> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_EXPIRES,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.IS_TRASHED,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, if (onlyTrashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
            if (!onlyTrashed) {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(".trashed-%"))
            }
        }
        val list = ArrayList<TrashItem>()
        context.contentResolver.query(collection, projection, args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val iData = c.getColumnIndex(MediaStore.MediaColumns.DATA)
            val iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val iMod = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val iExp = c.getColumnIndex(MediaStore.MediaColumns.DATE_EXPIRES)
            val iMime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val iVol = c.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
            val iTr = c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
            val iOwner = c.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            val iType = c.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
            while (c.moveToNext() && list.size < 5000) {
                val id = c.getLong(iId)
                val data = if (iData >= 0) c.getString(iData) else null
                val display = (if (iName >= 0) c.getString(iName) else null) ?: data?.substringAfterLast('/') ?: continue
                val fileName = data?.substringAfterLast('/') ?: display
                val parsed = parseTrashedName(fileName) ?: parseTrashedName(display)
                if (!onlyTrashed && parsed == null) continue
                val volume = (if (iVol >= 0) c.getString(iVol) else null) ?: MediaStore.VOLUME_EXTERNAL
                val mime = if (iMime >= 0) c.getString(iMime) else null
                val type = if (iType >= 0) c.getInt(iType) else 0
                val expSec = if (iExp >= 0 && !c.isNull(iExp)) c.getLong(iExp) else 0L
                val expires = when {
                    expSec > 0 -> expSec * 1000L
                    parsed != null -> parsed.second
                    else -> null
                }
                val modified = if (iMod >= 0) c.getLong(iMod) * 1000L else 0L
                val trashedFlag = onlyTrashed || (iTr >= 0 && c.getInt(iTr) == 1)
                list += TrashItem(
                    key = "ms:$volume:$id",
                    name = parsed?.first ?: display,
                    path = data,
                    uri = itemUri(volume, id, type),
                    size = if (iSize >= 0) c.getLong(iSize) else 0L,
                    isDir = mime == null && type == 0 && data != null && File(data).isDirectory,
                    deletedAt = expires?.minus(RETENTION_MS) ?: modified,
                    expiresAt = expires,
                    mimeType = mime,
                    mediaStoreTrashed = trashedFlag,
                    ownerPackage = if (iOwner >= 0) c.getString(iOwner) else null,
                )
            }
        }
        return list
    }

    /** Media items get their media collection URI (what createTrashRequest/createDeleteRequest expect). */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun itemUri(volume: String, id: Long, mediaType: Int): Uri = when (mediaType) {
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.getContentUri(volume, id)
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.getContentUri(volume, id)
        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.getContentUri(volume, id)
        else -> ContentUris.withAppendedId(MediaStore.Files.getContentUri(volume), id)
    }

    /** Puts items back where they were. Some may need the user's OK: see [TrashOutcome.confirm]. */
    suspend fun restore(items: List<TrashItem>): TrashOutcome = withContext(Dispatchers.IO) {
        var done = 0
        var failed = 0
        val needConfirm = ArrayList<Uri>()
        val scan = ArrayList<String>()
        for (item in items) {
            val uri = item.uri
            if (uri != null && item.mediaStoreTrashed && supported) {
                val ok = runCatching { untrash(uri) > 0 }.getOrDefault(false)
                if (ok) done++ else needConfirm += uri
                continue
            }
            // A loose ".trashed-…" file or a public trash folder file: move it back by hand.
            val path = item.path
            val target = path?.let { restoreTarget(File(it)) }
            if (path != null && target != null && File(path).renameTo(target)) {
                done++; scan += target.absolutePath; scan += path
            } else failed++
        }
        if (scan.isNotEmpty()) FileOps.scan(context, scan)
        val sender = if (needConfirm.isNotEmpty() && supported) {
            runCatching { MediaStore.createTrashRequest(context.contentResolver, needConfirm, false).intentSender }.getOrNull()
        } else null
        TrashOutcome(done, sender, failed + if (sender == null) needConfirm.size else 0)
    }

    /** Deletes items for good. Some may need the user's OK: see [TrashOutcome.confirm]. */
    suspend fun deleteForever(items: List<TrashItem>): TrashOutcome = withContext(Dispatchers.IO) {
        var done = 0
        var failed = 0
        val needConfirm = ArrayList<Uri>()
        for (item in items) {
            val uri = item.uri
            if (uri != null && supported) {
                val ok = runCatching {
                    val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
                    context.contentResolver.delete(uri, args) > 0
                }.getOrDefault(false) || item.path?.let { p -> File(p).let { it.exists() && it.delete() } } == true
                if (ok) done++ else needConfirm += uri
                continue
            }
            val f = item.path?.let(::File)
            if (f != null && f.exists() && f.deleteRecursively()) done++ else failed++
        }
        val sender = if (needConfirm.isNotEmpty() && supported) {
            runCatching { MediaStore.createDeleteRequest(context.contentResolver, needConfirm).intentSender }.getOrNull()
        } else null
        TrashOutcome(done, sender, failed + if (sender == null) needConfirm.size else 0)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun untrash(uri: Uri): Int {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
        return context.contentResolver.update(uri, values, args)
    }

    /**
     * Where a loose trashed file goes back to: same folder without the ".trashed-…" prefix, or, for a
     * file inside a public trash folder, the folder that holds the trash folder.
     */
    private fun restoreTarget(file: File): File? {
        val parent = file.parentFile ?: return null
        val inTrashDir = parent.name.equals(".trash", ignoreCase = true)
        val dir = if (inTrashDir) parent.parentFile ?: return null else parent
        val name = uniqueName(originalName(file.name), file.isDirectory) { File(dir, it).exists() }
        return File(dir, name)
    }
}
