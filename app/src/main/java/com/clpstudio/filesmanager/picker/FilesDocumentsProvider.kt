package com.clpstudio.filesmanager.picker

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.media.ThumbnailUtils
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Size
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.R
import com.clpstudio.filesmanager.data.FileTypes
import com.clpstudio.filesmanager.data.StoragePaths
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * "Files" as a source inside Android's own file picker (ACTION_OPEN_DOCUMENT and friends):
 * one root per volume (Internal storage, SD card), read-only. Document ids are absolute paths.
 * The vault and the Bin are never listed; hidden files only with "Show hidden files" on.
 * Works fully offline.
 */
class FilesDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    private fun ctx() = context ?: throw IllegalStateException("no context")

    private fun ensureGraph() {
        runCatching { Graph.init(ctx()) }
    }

    private fun showHidden(): Boolean = runCatching { Graph.settings.value.showHidden }.getOrDefault(false)

    private fun privateRoots(): List<String> = StoragePaths.privateRoots(ctx())

    override fun queryRoots(projection: Array<out String>?): Cursor {
        ensureGraph()
        val c = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        StoragePaths.volumes(ctx()).forEachIndexed { i, v ->
            c.newRow().apply {
                add(Root.COLUMN_ROOT_ID, if (i == 0) "primary" else "volume$i")
                add(Root.COLUMN_DOCUMENT_ID, v.path)
                add(Root.COLUMN_TITLE, "Files")
                add(Root.COLUMN_SUMMARY, v.label)
                add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_IS_CHILD)
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(Root.COLUMN_AVAILABLE_BYTES, v.free)
                add(Root.COLUMN_MIME_TYPES, "*/*")
            }
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        ensureGraph()
        val c = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        addRow(c, fileFor(documentId))
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        ensureGraph()
        val c = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val dir = fileFor(parentDocumentId)
        dir.listFiles().orEmpty().filter { visible(it) }.forEach { addRow(c, it) }
        return c
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        ensureGraph()
        val c = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val vols = StoragePaths.volumes(ctx())
        val root = vols.getOrNull(if (rootId == "primary") 0 else rootId.removePrefix("volume").toIntOrNull() ?: 0) ?: return c
        val q = query.trim().lowercase()
        if (q.isEmpty()) return c
        val deadline = System.currentTimeMillis() + SEARCH_BUDGET_MS
        var found = 0
        File(root.path).walkTopDown()
            .onEnter { d -> visible(d) && System.currentTimeMillis() < deadline && found < SEARCH_LIMIT }
            .maxDepth(12)
            .forEach { f ->
                if (found >= SEARCH_LIMIT) return@forEach
                if (f.path != root.path && f.name.lowercase().contains(q) && visible(f)) {
                    addRow(c, f); found++
                }
            }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId || documentId.startsWith(parentDocumentId.trimEnd('/') + "/")

    override fun getDocumentType(documentId: String): String {
        val f = File(documentId)
        return if (f.isDirectory) Document.MIME_TYPE_DIR else FileTypes.mimeOf(f.name)
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        ensureGraph()
        if (mode.contains('w') || mode.contains('a') || mode.contains('t')) throw FileNotFoundException("Files shares read-only")
        val f = fileFor(documentId)
        if (!f.isFile) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point?, signal: CancellationSignal?): AssetFileDescriptor {
        ensureGraph()
        val f = fileFor(documentId)
        val px = (sizeHint?.let { maxOf(it.x, it.y) } ?: 256).coerceIn(64, 512)
        val out = File(File(ctx().cacheDir, "doc_thumbs").apply { mkdirs() }, "${(f.path + f.lastModified()).hashCode()}_$px.jpg")
        if (!out.isFile || out.length() == 0L) {
            val bmp = thumbnail(f, px) ?: throw FileNotFoundException("no thumbnail")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bmp.recycle()
        }
        val pfd = ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, out.length())
    }

    private fun thumbnail(f: File, px: Int): Bitmap? = runCatching {
        when (FileTypes.kindOf(f.name)) {
            com.clpstudio.filesmanager.data.FileKind.IMAGE -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            com.clpstudio.filesmanager.data.FileKind.VIDEO ->
                if (Build.VERSION.SDK_INT >= 29) ThumbnailUtils.createVideoThumbnail(f, Size(px, px), null)
                else @Suppress("DEPRECATION") ThumbnailUtils.createVideoThumbnail(f.path, android.provider.MediaStore.Images.Thumbnails.MINI_KIND)
            else -> null
        }
    }.getOrNull()

    /** A path inside a storage volume, never the vault / Bin, and hidden only when allowed. */
    private fun fileFor(documentId: String): File {
        val f = File(documentId)
        val path = f.absolutePath
        val roots = StoragePaths.volumes(ctx()).map { it.path }
        if (roots.none { path == it || path.startsWith("$it/") }) throw FileNotFoundException(documentId)
        if (privateRoots().any { path == it || path.startsWith("$it/") }) throw FileNotFoundException(documentId)
        if (!f.exists()) throw FileNotFoundException(documentId)
        return f
    }

    private fun visible(f: File): Boolean {
        val path = f.absolutePath
        if (privateRoots().any { path == it || path.startsWith("$it/") }) return false
        if (!showHidden() && f.name.startsWith(".")) return false
        return true
    }

    private fun addRow(c: MatrixCursor, f: File) {
        val dir = f.isDirectory
        val mime = if (dir) Document.MIME_TYPE_DIR else FileTypes.mimeOf(f.name)
        val kind = FileTypes.kindOf(f.name, dir)
        var flags = 0
        if (kind == com.clpstudio.filesmanager.data.FileKind.IMAGE || kind == com.clpstudio.filesmanager.data.FileKind.VIDEO) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        val name = if (StoragePaths.volumes(ctx()).any { it.path == f.absolutePath }) {
            StoragePaths.volumes(ctx()).first { it.path == f.absolutePath }.label
        } else f.name
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, f.absolutePath)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_SIZE, if (dir) null else f.length())
            add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    companion object {
        private const val SEARCH_LIMIT = 200
        private const val SEARCH_BUDGET_MS = 4_000L

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_MIME_TYPES,
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
