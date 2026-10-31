package com.clpstudio.filesmanager.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.OutputStream

/**
 * Item 1 (F11): writing to an SD card on Android 8, 9 and 10.
 *
 * On those versions apps can read a removable card but plain file writes there fail (EACCES); Android 11+ lets
 * "All files access" write directly. So on 8–10 Files asks once for the card through the system folder picker
 * (the person picks the card's top level), keeps that permission, and does every write there — new folder,
 * copy / move in and out, rename, delete, the Bin — through the system's documents provider.
 */
object SafAccess {
    const val PROVIDER = "com.android.externalstorage.documents"
    private const val PREFS = "saf_trees"

    /** Set when an operation needs the card's permission; the app shows the "Allow access" dialog for this root. */
    private val _needed = MutableStateFlow<String?>(null)
    val needed: StateFlow<String?> = _needed.asStateFlow()
    fun ask(root: String) { _needed.value = root }
    fun dismiss() { _needed.value = null }

    /** Android 8–10 only, and only on a removable card. */
    fun applies(path: String, removableRoots: List<String>, sdk: Int = Build.VERSION.SDK_INT): Boolean =
        sdk in 26..29 && VolumeRules.removableRootOf(path, removableRoots) != null

    /** "XXXX-XXXX" for /storage/XXXX-XXXX. */
    fun volumeId(root: String): String = root.trimEnd('/').substringAfterLast('/')

    /** The provider's document id of [path] on the card at [root]: "XXXX-XXXX:DCIM/Camera/a.jpg". */
    fun documentId(root: String, path: String): String {
        val rel = path.removePrefix(root.trimEnd('/')).trim('/')
        return volumeId(root) + ":" + rel
    }

    /** The tree the person picked must be the card's top level ("XXXX-XXXX:"), not a folder inside it. */
    fun isCardRoot(treeDocId: String, root: String): Boolean = treeDocId == volumeId(root) + ":"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The kept permission for the card at [root], if Android still has it. */
    fun tree(context: Context, root: String): Uri? {
        val s = prefs(context).getString(volumeId(root), null) ?: return null
        val uri = Uri.parse(s)
        val ok = runCatching { context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission } }.getOrDefault(false)
        return if (ok) uri else null
    }

    fun hasAccess(context: Context, root: String) = tree(context, root) != null

    /** The picker, opened at the card. */
    fun requestIntent(context: Context, root: String): Intent {
        if (Build.VERSION.SDK_INT >= 29) {
            val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
            sm?.getStorageVolume(File(root))?.createOpenDocumentTreeIntent()?.let { return it }
        }
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(PROVIDER, volumeId(root) + ":"))
            putExtra("android.content.extra.SHOW_ADVANCED", true)
        }
    }

    /** The picker's answer. Null = kept; else what went wrong in plain words. */
    fun onPicked(context: Context, root: String, tree: Uri?): String? {
        if (tree == null) return "Not allowed. Files can't write to the SD card without it."
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
        if (tree.authority != PROVIDER || id == null || !isCardRoot(id, root)) {
            return "Please pick the SD card itself (its top level), not a folder on it."
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(tree, flags) }
            .onFailure { return "Android didn't keep the permission: ${it.message}" }
        prefs(context).edit().putString(volumeId(root), tree.toString()).apply()
        dismiss()
        return null
    }

    // ---------------------------------------------------------------- operations

    class NeedsAccess(val root: String) : FileOpException("Allow Files to write to the SD card first")

    private fun docUri(context: Context, root: String, path: String): Uri {
        val tree = tree(context, root) ?: run { ask(root); throw NeedsAccess(root) }
        return DocumentsContract.buildDocumentUriUsingTree(tree, documentId(root, path))
    }

    private fun rootOf(context: Context, path: String): String =
        VolumeRules.removableRootOf(path, SdCards.removableRoots(context)) ?: throw FileOpException("Not on an SD card")

    fun mkdirs(context: Context, dir: File): Boolean {
        if (dir.isDirectory) return true
        val parent = dir.parentFile ?: return false
        if (!parent.isDirectory && !mkdirs(context, parent)) return false
        val root = rootOf(context, dir.path)
        val made = DocumentsContract.createDocument(context.contentResolver, docUri(context, root, parent.path), DocumentsContract.Document.MIME_TYPE_DIR, dir.name)
        return made != null
    }

    /** A new (or emptied) file at [file] to write into. */
    fun openOutput(context: Context, file: File): OutputStream {
        val root = rootOf(context, file.path)
        val parent = file.parentFile ?: throw FileOpException("No folder")
        if (!parent.isDirectory) mkdirs(context, parent)
        val uri = if (file.exists()) docUri(context, root, file.path) else {
            DocumentsContract.createDocument(context.contentResolver, docUri(context, root, parent.path), "application/octet-stream", file.name)
                ?: throw FileOpException("Couldn't create ${file.name} on the SD card")
        }
        return context.contentResolver.openOutputStream(uri, "w") ?: throw FileOpException("Couldn't write ${file.name} on the SD card")
    }

    fun delete(context: Context, file: File): Boolean {
        if (!file.exists()) return true
        val root = rootOf(context, file.path)
        return runCatching { DocumentsContract.deleteDocument(context.contentResolver, docUri(context, root, file.path)) }
            .getOrElse { if (it is NeedsAccess) throw it else false } || !file.exists()
    }

    fun rename(context: Context, file: File, newName: String): Boolean {
        val root = rootOf(context, file.path)
        return DocumentsContract.renameDocument(context.contentResolver, docUri(context, root, file.path), newName) != null
    }

    /** Move within one card (no copy). */
    fun move(context: Context, file: File, targetDir: File): Boolean {
        val root = rootOf(context, file.path)
        val src = docUri(context, root, file.path)
        val parent = docUri(context, root, file.parentFile!!.path)
        val target = docUri(context, root, targetDir.path)
        return runCatching { DocumentsContract.moveDocument(context.contentResolver, src, parent, target) != null }.getOrElse { if (it is NeedsAccess) throw it else false }
    }

    // ---------------------------------------------------------------- helpers for the file operations

    fun appContext(): Context? = runCatching { com.clpstudio.filesmanager.Graph.app }.getOrNull()

    /** Plain File first; on an SD card on Android 8–10, the documents provider. */
    fun deleteRecursively(context: Context?, f: File): Boolean {
        if (f.deleteRecursively() || !f.exists()) return true
        if (context == null || !applies(f.path, SdCards.removableRoots(context))) return false
        return delete(context, f)
    }

    fun mkdirsAny(context: Context?, dir: File): Boolean {
        if (dir.isDirectory || dir.mkdirs()) return true
        if (context == null || !applies(dir.path, SdCards.removableRoots(context))) return false
        return mkdirs(context, dir)
    }

    fun outputAny(context: Context?, f: File): OutputStream {
        try {
            return f.outputStream()
        } catch (e: java.io.IOException) {
            if (context == null || !applies(f.path, SdCards.removableRoots(context))) throw e
            return openOutput(context, f)
        }
    }

    fun renameAny(context: Context?, f: File, target: File): Boolean {
        if (f.renameTo(target)) return true
        if (context == null || !applies(f.path, SdCards.removableRoots(context))) return false
        if (f.parentFile?.path == target.parentFile?.path) return rename(context, f, target.name)
        return false
    }

    /** True when writing under [dir] works, directly or through the kept permission. */
    fun canWrite(context: Context, dir: File): Boolean {
        if (dir.canWrite()) return true
        val roots = SdCards.removableRoots(context)
        if (!applies(dir.path, roots)) return false
        val root = VolumeRules.removableRootOf(dir.path, roots)!!
        if (hasAccess(context, root)) return true
        ask(root)
        throw NeedsAccess(root)
    }

    /** Copies a file or folder tree, writing through the card permission where needed. */
    fun copyTree(context: Context?, src: File, dest: File): Boolean = runCatching {
        if (src.isDirectory) {
            if (!mkdirsAny(context, dest)) return false
            src.listFiles()?.forEach { if (!copyTree(context, it, File(dest, it.name))) return false }
        } else {
            dest.parentFile?.let { mkdirsAny(context, it) }
            src.inputStream().use { i -> outputAny(context, dest).use { o -> i.copyTo(o, 256 * 1024) } }
            dest.setLastModified(src.lastModified())
        }
        true
    }.getOrElse { if (it is NeedsAccess) throw it else false }
}
