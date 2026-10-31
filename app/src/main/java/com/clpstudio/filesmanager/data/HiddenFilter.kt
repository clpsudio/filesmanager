package com.clpstudio.filesmanager.data

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Decides what the "Show hidden files" setting reveals.
 *
 * Hidden = a dot-file, anything inside a dot-folder, or anything inside a folder that holds a
 * `.nomedia` file. App-private folders (the vault in phase 2, the Bin) are never listed, whatever
 * the setting: [privateRoots] are excluded always.
 */
class HiddenFilter(
    val showHidden: Boolean,
    val privateRoots: List<String> = emptyList(),
    private val storageRoots: List<String> = emptyList(),
    /** `.nomedia` hides a folder's files (galleries). Off for documents/APKs/downloads. */
    private val respectNoMedia: Boolean = true,
) {
    /** The same rules without the `.nomedia` one (WhatsApp keeps documents in such folders). */
    fun withoutNoMedia(): HiddenFilter = HiddenFilter(showHidden, privateRoots, storageRoots, respectNoMedia = false)

    private val noMediaCache = ConcurrentHashMap<String, Boolean>()

    fun isPrivate(path: String): Boolean =
        privateRoots.any { root -> path == root || path.startsWith("$root/") }

    /** Whether a single entry (already inside a visible folder) can be listed. */
    fun visibleEntry(file: File): Boolean {
        val path = file.absolutePath
        if (isPrivate(path)) return false
        if (showHidden) return true
        if (file.name.startsWith(".")) return false
        if (respectNoMedia && file.isDirectory && hasNoMedia(path)) return false
        return true
    }

    /** Whether a path found by an index (MediaStore) or a deep walk can be shown. */
    fun visiblePath(path: String): Boolean {
        if (isPrivate(path)) return false
        if (showHidden) return true
        val root = storageRoots.firstOrNull { path.startsWith("$it/") } ?: ""
        val rel = path.removePrefix(root).trim('/')
        if (rel.split('/').any { it.startsWith(".") }) return false
        if (!respectNoMedia) return true
        // Any folder between the storage root and the file holding .nomedia hides it.
        var dir = path.substringBeforeLast('/', "")
        while (dir.length > root.length && dir.isNotEmpty()) {
            if (hasNoMedia(dir)) return false
            dir = dir.substringBeforeLast('/', "")
        }
        return true
    }

    private fun hasNoMedia(dir: String): Boolean =
        noMediaCache.getOrPut(dir) { File(dir, ".nomedia").exists() }
}
