package com.clpstudio.filesmanager.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Hidden = a name starting with "." or a file our Hide put in a ".hidden" folder. Pure rules, unit-tested. */
object HiddenNames {
    const val HIDDEN_DIR = ".hidden"

    fun isHidden(name: String, parentName: String?): Boolean =
        name.startsWith(".") || parentName == HIDDEN_DIR

    fun isHidden(path: String): Boolean {
        val f = File(path)
        return isHidden(f.name, f.parentFile?.name)
    }

    /** "name (1).ext", "name (2).ext", ... until [exists] says no; a folder keeps its whole name. */
    fun unique(dir: File, name: String, isDir: Boolean, exists: (File) -> Boolean): File {
        var target = File(dir, name)
        if (!exists(target)) return target
        val dot = if (isDir) -1 else name.lastIndexOf('.').takeIf { it > 0 } ?: -1
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (exists(target)) target = File(dir, "$base (${n++})$ext")
        return target
    }
}

/**
 * Which hidden-file actions a menu shows for the selected paths. Files has no Unhide: hidden items
 * get only "Delete permanently"; ordinary items get Hide.
 */
data class HideChoice(val hide: Boolean, val deletePermanently: Boolean)

object HideMenu {
    /** All hidden → only Delete permanently; none hidden → only Hide; a mix → both. Nothing selected → neither. */
    fun decide(paths: List<String>, isHidden: (String) -> Boolean = HiddenNames::isHidden): HideChoice {
        if (paths.isEmpty()) return HideChoice(hide = false, deletePermanently = false)
        val hidden = paths.count(isHidden)
        return HideChoice(hide = hidden < paths.size, deletePermanently = hidden > 0)
    }
}

/** Every hidden file and folder on storage, found by a bounded walk (MediaStore can't see dot-files). */
object HiddenScan {
    data class State(val items: List<FileItem>?, val scanning: Boolean, val scannedFolders: Int = 0)

    private val _state = MutableStateFlow(State(null, false))
    val state: StateFlow<State> = _state

    private const val MAX_RESULTS = 20_000
    private const val MAX_DEPTH = 14
    private val SKIP_NAMES = setOf(".nomedia", ".thumbnails", ".trashed", ".pending")

    /** Tests only. */
    internal fun setForTest(items: List<FileItem>?) {
        _state.value = State(items, scanning = false)
    }

    fun forget(path: String) {
        _state.value.items?.let { l -> _state.value = _state.value.copy(items = l.filterNot { it.path == path || it.path.startsWith("$path/") }) }
    }

    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        if (_state.value.scanning) return@withContext
        _state.value = _state.value.copy(scanning = true, scannedFolders = 0)
        val roots = runCatching { StoragePaths.volumes(context).map { it.path } }.getOrDefault(emptyList())
        val privateRoots = runCatching { StoragePaths.privateRoots(context) }.getOrDefault(emptyList())
        val out = ArrayList<FileItem>()
        var folders = 0
        fun private(p: String) = privateRoots.any { p == it || p.startsWith("$it/") }
        fun walk(dir: File, depth: Int, root: String) {
            if (out.size >= MAX_RESULTS || depth > MAX_DEPTH) return
            val rel = dir.absolutePath.removePrefix(root)
            if (rel == "/Android/data" || rel == "/Android/obb") return
            val kids = dir.listFiles() ?: return
            folders++
            if (folders % 200 == 0) _state.value = _state.value.copy(scannedFolders = folders)
            for (k in kids) {
                val p = k.absolutePath
                if (private(p) || k.name in SKIP_NAMES) continue
                if (k.name == HiddenNames.HIDDEN_DIR && k.isDirectory) {
                    // Our Hide: list what's inside, not the folder.
                    k.listFiles()?.filter { it.name != ".nomedia" }?.forEach { out += FileItem.of(it) }
                } else if (k.name.startsWith(".")) {
                    out += FileItem.of(k)
                } else if (k.isDirectory) {
                    walk(k, depth + 1, root)
                }
                if (out.size >= MAX_RESULTS) return
            }
        }
        for (r in roots) runCatching { walk(File(r), 0, r) }
        _state.value = State(out.sortedByDescending { it.modified }, scanning = false, scannedFolders = folders)
    }
}
