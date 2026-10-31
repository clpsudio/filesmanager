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

    /**
     * Where Unhide puts [file]: out of our ".hidden" folder back next to it (same name), or the
     * same folder with the leading dots dropped (".notes.txt" → "notes.txt"). Clashes get " (1)".
     */
    fun unhideTarget(file: File, isDir: Boolean, exists: (File) -> Boolean): File? {
        val parent = file.parentFile ?: return null
        return if (parent.name == HIDDEN_DIR) {
            val dest = parent.parentFile ?: return null
            unique(dest, file.name.trimStart('.').ifEmpty { file.name }, isDir, exists)
        } else {
            val name = file.name.trimStart('.')
            if (name.isEmpty() || name == file.name) null else unique(parent, name, isDir, exists)
        }
    }
}

/** Which of Hide / Unhide a menu shows for the selected paths. */
data class HideChoice(val hide: Boolean, val unhide: Boolean)

object HideMenu {
    /** All hidden → only Unhide; none hidden → only Hide; a mix → both. Nothing selected → neither. */
    fun decide(paths: List<String>): HideChoice {
        if (paths.isEmpty()) return HideChoice(hide = false, unhide = false)
        val hidden = paths.count { HiddenNames.isHidden(it) }
        return HideChoice(hide = hidden < paths.size, unhide = hidden > 0)
    }
}

object HiddenOps {
    /** Reverse of Hide. Returns the new file, or null when it couldn't move. */
    fun unhide(context: Context, file: File): File? {
        val target = HiddenNames.unhideTarget(file, file.isDirectory) { it.exists() } ?: return null
        val ok = file.renameTo(target) || (!file.isDirectory && runCatching { file.copyTo(target); file.delete() }.getOrDefault(false))
        if (!ok) return null
        // Our ".hidden" folder: drop it (and the .nomedia we added) once it is empty.
        val parent = file.parentFile
        if (parent != null && parent.name == HiddenNames.HIDDEN_DIR) {
            val left = parent.listFiles()?.filter { it.name != ".nomedia" }.orEmpty()
            if (left.isEmpty()) { File(parent, ".nomedia").delete(); parent.delete() }
        }
        Favorites.init(context)
        Favorites.onMoved(file.absolutePath, target.absolutePath)
        FileEvents.moved(file.absolutePath, target.absolutePath)
        HiddenScan.forget(file.absolutePath)
        FileOps.scan(context, listOf(file.absolutePath, target.absolutePath))
        return target
    }

    fun unhideAll(context: Context, files: List<File>): Int = files.count { unhide(context, it) != null }
}

/** Every hidden file and folder on storage, found by a bounded walk (MediaStore can't see dot-files). */
object HiddenScan {
    data class State(val items: List<FileItem>?, val scanning: Boolean, val scannedFolders: Int = 0)

    private val _state = MutableStateFlow(State(null, false))
    val state: StateFlow<State> = _state

    private const val MAX_RESULTS = 20_000
    private const val MAX_DEPTH = 14
    private val SKIP_NAMES = setOf(".nomedia", ".thumbnails", ".trashed", ".pending")

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
