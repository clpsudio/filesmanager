package com.clpstudio.filesmanager.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Pure path logic for [Favorites] (no Android, unit-tested). */
object FavoritePaths {
    fun normalize(path: String): String = if (path.length > 1) path.trimEnd('/') else path

    /** True when [path] is [root] itself or lies inside it ("/a/b/c" is under "/a/b", "/a/bc" is not). */
    fun isUnder(path: String, root: String): Boolean {
        val r = normalize(root)
        val p = normalize(path)
        return p == r || p.startsWith(if (r == "/") "/" else "$r/")
    }

    /** [old] became [new]: the file itself and, for a folder, everything inside it follow. */
    fun remap(paths: Set<String>, old: String, new: String): Set<String> {
        val o = normalize(old)
        val n = normalize(new)
        if (o == n) return paths
        var changed = false
        val out = LinkedHashSet<String>(paths.size)
        for (p in paths) {
            if (isUnder(p, o)) {
                out += n + normalize(p).substring(o.length)
                changed = true
            } else {
                out += p
            }
        }
        return if (changed) out else paths
    }

    /** Drops [removed] paths and anything inside removed folders. */
    fun removeUnder(paths: Set<String>, removed: Collection<String>): Set<String> {
        if (removed.isEmpty() || paths.isEmpty()) return paths
        val roots = removed.map { normalize(it) }
        val out = paths.filterTo(LinkedHashSet()) { p -> roots.none { isUnder(p, it) } }
        return if (out.size == paths.size) paths else out
    }

    fun encode(paths: Set<String>): String = paths.joinToString("\n")

    fun decode(text: String): Set<String> =
        text.lineSequence().map { it.trim() }.filter { it.startsWith("/") }.mapTo(LinkedHashSet()) { normalize(it) }
}

/**
 * Starred files and folders, kept on this device only (a small text file in filesDir, one path per line).
 * [init] is cheap and idempotent: the file is read on a background thread; changes made before it is read
 * are applied after it, so nothing is lost. Every change is saved in the background (temp file + rename).
 */
object Favorites {
    private const val FILE_NAME = "favorites.txt"
    private val state = MutableStateFlow<Set<String>>(emptySet())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "favorites").apply { isDaemon = true } }
    @Volatile private var file: File? = null
    @Volatile private var loaded = false
    private val lock = Any()

    /** Every starred path (absolute). */
    val paths: StateFlow<Set<String>> = state.asStateFlow()

    fun init(context: Context) {
        if (file != null) return
        init(File(context.applicationContext.filesDir, FILE_NAME))
    }

    /** Points the store at [store] (tests use a temp file). Re-pointing resets the in-memory set. */
    internal fun init(store: File) {
        synchronized(lock) {
            if (file == store) return
            val first = file == null
            file = store
            loaded = false
            // Stars set before the very first init are kept and merged with the file.
            if (!first) state.value = emptySet()
        }
        io.execute {
            val read = runCatching { if (store.isFile) FavoritePaths.decode(store.readText()) else emptySet() }.getOrDefault(emptySet())
            synchronized(lock) {
                if (file != store) return@execute
                state.update { read + it }
                loaded = true
            }
        }
    }

    fun isFavorite(path: String): Boolean = FavoritePaths.normalize(path) in state.value

    fun toggle(path: String) {
        val p = FavoritePaths.normalize(path)
        change { if (p in it) it - p else it + p }
    }

    fun add(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val ps = paths.map { FavoritePaths.normalize(it) }
        change { it + ps }
    }

    fun remove(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val ps = paths.map { FavoritePaths.normalize(it) }.toSet()
        change { it - ps }
    }

    /** A file or folder was renamed or moved; a folder's starred children follow it. */
    fun onMoved(oldPath: String, newPath: String) = change { FavoritePaths.remap(it, oldPath, newPath) }

    /** Files or folders were deleted (or moved to the Bin): they and anything inside them are unstarred. */
    fun onDeleted(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val ps = paths.toList()
        change { FavoritePaths.removeUnder(it, ps) }
    }

    /** The starred files that still exist, newest first (for Home's Favorites). */
    suspend fun existingItems(): List<FileItem> = withContext(Dispatchers.IO) {
        state.value.map { File(it) }.filter { it.exists() }.map { FileItem.of(it, countChildren = true) }
            .sortedByDescending { it.modified }
    }

    /** Waits until queued loads and saves are done (tests). */
    internal fun flush() {
        io.submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun change(op: (Set<String>) -> Set<String>) {
        val applyNow = synchronized(lock) {
            if (loaded || file == null) {
                state.update(op)
                true
            } else {
                false
            }
        }
        if (applyNow) {
            save()
        } else {
            io.execute {
                synchronized(lock) { state.update(op) }
                save()
            }
        }
    }

    private fun save() {
        val target = file ?: return
        io.execute {
            if (file != target) return@execute
            runCatching {
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(FavoritePaths.encode(state.value))
                if (!tmp.renameTo(target)) {
                    target.writeText(FavoritePaths.encode(state.value))
                    tmp.delete()
                }
            }
        }
    }
}
