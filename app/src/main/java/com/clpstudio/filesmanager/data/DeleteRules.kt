package com.clpstudio.filesmanager.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What Delete does with a selection: ordinary files go to the Bin (30 days, restorable); hidden
 * files — dot-files, anything inside a dot-folder, media in a folder holding `.nomedia`, and the
 * app's own Hide folder (`.hidden`) — are deleted permanently (they skip the Bin), after a confirm
 * that says it can't be undone. A mixed selection does both, in one confirm.
 */
data class DeletePlan(val toBin: List<String>, val permanent: List<String>, val folders: Int = 0) {
    val isEmpty: Boolean get() = toBin.isEmpty() && permanent.isEmpty()
    val allPermanent: Boolean get() = toBin.isEmpty() && permanent.isNotEmpty()
    val mixed: Boolean get() = toBin.isNotEmpty() && permanent.isNotEmpty()
    val total: Int get() = toBin.size + permanent.size

    /** Dialog title; [singleName] is the file's name when exactly one item is selected. */
    fun title(singleName: String?): String = when {
        allPermanent && total == 1 && singleName != null -> "Delete $singleName permanently?"
        allPermanent -> "Delete ${count(total)} permanently?"
        total == 1 && singleName != null -> "Delete $singleName?"
        else -> "Delete ${count(total)}?"
    }

    fun message(): String = when {
        allPermanent -> (if (total == 1) "This is a hidden file. It skips the Bin and is deleted for good." else "These are hidden files. They skip the Bin and are deleted for good.") +
            (if (folders > 0) " Everything inside ${if (folders == 1) "the folder" else "the folders"} is deleted too." else "") +
            " This can't be undone."
        mixed -> "${count(permanent.size, "hidden item")} will be deleted permanently (hidden files skip the Bin; this can't be undone). " +
            "The other ${toBin.size} go${if (toBin.size == 1) "es" else ""} to the Bin and can be restored for 30 days."
        else -> "They go to the Bin and are deleted for good after 30 days. You can restore them from the Bin until then."
    }

    fun confirmLabel(): String = when {
        allPermanent -> "Delete permanently"
        mixed -> "Delete"
        else -> "Move to Bin"
    }

    companion object {
        private fun count(n: Int, word: String = "item") = "$n $word" + if (n == 1) "" else "s"
    }
}

object DeleteRules {
    /**
     * Hidden for Delete: a dot-name anywhere from the storage root down (".notes.txt", ".stuff/a.jpg"),
     * the app's own Hide folder (".hidden/x"), or media under a folder holding `.nomedia`.
     * [hasNoMedia] is injectable for tests.
     */
    fun isHidden(path: String, roots: List<String>, hasNoMedia: (String) -> Boolean = { File(it, ".nomedia").exists() }): Boolean =
        HiddenNames.isHidden(path) || HiddenMedia.isHiddenPath(path, roots, hasNoMedia)

    fun plan(paths: List<String>, isDir: (String) -> Boolean = { false }, isHidden: (String) -> Boolean): DeletePlan {
        val unique = paths.distinct()
        val (hidden, normal) = unique.partition(isHidden)
        return DeletePlan(toBin = normal, permanent = hidden, folders = hidden.count(isDir))
    }

    /** Whether [path] is hidden on this phone (every storage volume, SD card included). */
    fun isHiddenOnPhone(context: Context, path: String): Boolean {
        val roots = runCatching { StoragePaths.volumes(context).map { it.path } }.getOrDefault(emptyList()) + StoragePaths.primary
        return isHidden(path, roots.distinct())
    }

    /** The plan for real files on this phone (roots = every storage volume, SD card included). */
    fun planFor(context: Context, items: List<FileItem>): DeletePlan {
        val roots = runCatching { StoragePaths.volumes(context).map { it.path } }.getOrDefault(emptyList()) + StoragePaths.primary
        val dirs = items.filter { it.isDir }.mapTo(HashSet()) { it.path }
        return plan(items.map { it.path }, { it in dirs }) { isHidden(it, roots.distinct()) }
    }
}

/** Deletes files for good, skipping the Bin (hidden files). Every list and the media index forget them. */
object PermanentDelete {
    suspend fun delete(context: Context?, files: List<File>, onEach: (Int) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        var done = 0
        val gone = ArrayList<String>()
        val scan = ArrayList<String>()
        files.forEachIndexed { i, f ->
            if (f.exists()) {
                if (f.isDirectory) f.walkTopDown().take(2000).filter { it.isFile }.forEach { scan += it.absolutePath }
                val parent = f.parentFile
                // Item 1 (F11): SD card on Android 8–10 → through the card permission.
                if (SafAccess.deleteRecursively(context, f) || !f.exists()) {
                    done++
                    gone += f.absolutePath
                    HiddenScan.forget(f.absolutePath)
                    // Our own Hide folder: drop it (and its .nomedia) once nothing is left in it.
                    if (parent != null && parent.name == HiddenNames.HIDDEN_DIR) {
                        val left = parent.listFiles()?.filter { it.name != ".nomedia" }.orEmpty()
                        if (left.isEmpty()) { File(parent, ".nomedia").delete(); parent.delete() }
                    }
                }
            }
            onEach(i + 1)
        }
        if (gone.isNotEmpty()) {
            FileEvents.removed(gone)
            runCatching { Favorites.onDeleted(gone) }
            if (context != null) runCatching { FileOps.scan(context, gone + scan) }
        }
        done
    }
}
