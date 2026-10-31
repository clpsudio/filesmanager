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

    private fun ownPicker(): Boolean = ownPickerOverride ?: runCatching { Graph.settings.value.ownPickerInOtherApps }.getOrDefault(true)

    private fun showHidden(): Boolean = showHiddenOverride ?: runCatching { Graph.settings.value.showHidden }.getOrDefault(false)

    private fun privateRoots(): List<String> = StoragePaths.privateRoots(ctx())

    override fun queryRoots(projection: Array<out String>?): Cursor {
        ensureGraph()
        val c = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        // App lock on and locked: Files doesn't show up in other apps' pickers until it's unlocked.
        if (runCatching { com.clpstudio.filesmanager.security.AppLock.isLocked }.getOrDefault(false)) return c
        // "Use Files' picker in other apps" (default on): no root here, so Android's picker lists Files as an
        // app and opens OUR picker. With a root, Android merges the two and a tap opens the root in its own
        // screen (DocumentsUI RootAndAppItem: only the small "open app" arrow reaches our picker).
        if (ownPicker()) return c
        // Phone storage, plus a root per mounted SD card while "Show SD card" is on.
        StoragePaths.volumes(ctx()).forEachIndexed { i, v ->
            c.newRow().apply {
                add(Root.COLUMN_ROOT_ID, rootId(i, v))
                add(Root.COLUMN_DOCUMENT_ID, v.path)
                add(Root.COLUMN_TITLE, "Files")
                add(Root.COLUMN_SUMMARY, v.label)
                add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_RECENTS)
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
        // Stays in sync: file changes in the app notify this folder (see [notifyChanged]).
        runCatching { c.setNotificationUri(ctx().contentResolver, android.provider.DocumentsContract.buildChildDocumentsUri(authority(ctx()), parentDocumentId)) }
        return c
    }

    /** Recent: the newest files on this volume (MediaStore's index, so it's quick), visible ones only. */
    override fun queryRecentDocuments(rootId: String, projection: Array<out String>?): Cursor {
        ensureGraph()
        val c = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val vols = StoragePaths.volumes(ctx())
        val root = vols.withIndex().firstOrNull { (i, v) -> rootId(i, v) == rootId }?.value ?: return c
        recentPaths(root.path).forEach { p -> File(p).takeIf { it.isFile && visible(it) }?.let { addRow(c, it) } }
        return c
    }

    private fun recentPaths(root: String): List<String> = runCatching {
        val uri = android.provider.MediaStore.Files.getContentUri("external")
        val out = ArrayList<String>()
        @Suppress("DEPRECATION")
        ctx().contentResolver.query(
            uri, arrayOf(android.provider.MediaStore.Files.FileColumns.DATA),
            "${android.provider.MediaStore.Files.FileColumns.DATA} LIKE ? AND ${android.provider.MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL",
            arrayOf("$root/%"), "${android.provider.MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
        )?.use { cur -> while (cur.moveToNext() && out.size < RECENT_LIMIT) cur.getString(0)?.let(out::add) }
        out
    }.getOrDefault(emptyList())

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        ensureGraph()
        val c = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val vols = StoragePaths.volumes(ctx())
        val root = vols.withIndex().firstOrNull { (i, v) -> rootId(i, v) == rootId }?.value ?: return c
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
            // PDFs: the first page; songs: the album art.
            com.clpstudio.filesmanager.data.FileKind.PDF -> ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                android.graphics.pdf.PdfRenderer(fd).use { r ->
                    r.openPage(0).use { page ->
                        val scale = px.toFloat() / maxOf(page.width, page.height)
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
            com.clpstudio.filesmanager.data.FileKind.AUDIO -> android.media.MediaMetadataRetriever().let { m ->
                try { m.setDataSource(f.path); m.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } } finally { runCatching { m.release() } }
            }
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
        // Hidden = the app's own rule: dot-names, inside dot-folders, .nomedia folders, the Hide folder.
        if (!showHidden() && (!Graph.hiddenFilter(false).visibleEntry(f) ||
                com.clpstudio.filesmanager.data.DeleteRules.isHidden(path, StoragePaths.volumes(ctx()).map { it.path } + StoragePaths.primary))) return false
        return true
    }

    private fun addRow(c: MatrixCursor, f: File) {
        val dir = f.isDirectory
        val mime = if (dir) Document.MIME_TYPE_DIR else FileTypes.mimeOf(f.name)
        val kind = FileTypes.kindOf(f.name, dir)
        var flags = 0
        if (kind in THUMB_KINDS) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
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
        /** Stable root ids: "primary", "sd:<card id>" (e.g. sd:1A2B-3C4D), else "volume<i>". */
        fun rootId(index: Int, v: com.clpstudio.filesmanager.data.StorageVolume): String = when {
            index == 0 && !v.removable -> "primary"
            v.removable -> "sd:" + v.path.trimEnd('/').substringAfterLast('/')
            else -> "volume$index"
        }

        private val THUMB_KINDS = setOf(
            com.clpstudio.filesmanager.data.FileKind.IMAGE, com.clpstudio.filesmanager.data.FileKind.VIDEO,
            com.clpstudio.filesmanager.data.FileKind.PDF, com.clpstudio.filesmanager.data.FileKind.AUDIO,
        )
        private const val RECENT_LIMIT = 64
        /** Tests only. */
        @androidx.annotation.VisibleForTesting var ownPickerOverride: Boolean? = null
        @androidx.annotation.VisibleForTesting var showHiddenOverride: Boolean? = null

        fun authority(context: android.content.Context) = context.packageName + ".documents"

        /** Files changed in the app: the system picker re-reads the folders they're in. */
        fun notifyChanged(context: android.content.Context, paths: Collection<String>) {
            val a = authority(context)
            paths.mapNotNull { File(it).parent }.distinct().forEach { dir ->
                runCatching { context.contentResolver.notifyChange(android.provider.DocumentsContract.buildChildDocumentsUri(a, dir), null) }
            }
        }

        /** Tells the system picker the roots changed (app lock locked / unlocked). */
        fun notifyRootsChanged(context: android.content.Context) {
            val uri = android.provider.DocumentsContract.buildRootsUri(context.packageName + ".documents")
            context.contentResolver.notifyChange(uri, null)
        }

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
