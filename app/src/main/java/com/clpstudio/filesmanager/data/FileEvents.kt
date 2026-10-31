package com.clpstudio.filesmanager.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one place that hears about files leaving or arriving through the app (Delete to Bin, Move,
 * Rename, Hide, Restore...). Every list (categories, Recent, Media, Favorites, search, folder
 * views, Home counts, the viewers) drops a removed path at once, without waiting for MediaStore
 * or a storage walk to catch up: stale index rows and kept-last-good lists can't bring it back.
 *
 * A removed path also hides everything under it (a deleted folder). A file that shows up again
 * later at the same path with a newer date (re-created, downloaded again) is shown, and [added]
 * clears a path the app itself put back (Restore, Move back, Rename back).
 */
object FileEvents {
    /** What changed, in one event. [moved] pairs old path -> new path. */
    data class Change(val removed: List<String>, val added: List<String>)

    /** Removed path -> when it was removed (ms). */
    private val removedAt = ConcurrentHashMap<String, Long>()
    private val listeners = CopyOnWriteArrayList<(Change) -> Unit>()
    private val _version = MutableStateFlow(0L)

    /** Bumped on every change: screens that collect it re-filter their lists. */
    val version: StateFlow<Long> = _version

    /** Slack for file systems that keep dates in whole seconds (FAT on SD cards). */
    private const val CLOCK_SLACK_MS = 2_000L

    fun addListener(l: (Change) -> Unit) { listeners += l }
    fun removeListener(l: (Change) -> Unit) { listeners -= l }

    fun removed(paths: Collection<String>, now: Long = System.currentTimeMillis()) =
        change(paths.map(::norm), emptyList(), now)

    fun added(paths: Collection<String>) = change(emptyList(), paths.map(::norm), System.currentTimeMillis())

    fun moved(from: String, to: String) = change(listOf(norm(from)), listOf(norm(to)), System.currentTimeMillis())

    fun moved(pairs: List<Pair<String, String>>) =
        change(pairs.map { norm(it.first) }, pairs.map { norm(it.second) }, System.currentTimeMillis())

    private fun change(removed: List<String>, added: List<String>, now: Long) {
        if (removed.isEmpty() && added.isEmpty()) return
        removed.forEach { removedAt[it] = now }
        // A path the app put back (restore / move back) is visible again, with everything under it.
        added.forEach { a ->
            removedAt.remove(a)
            val prefix = "$a/"
            removedAt.keys.removeAll { it.startsWith(prefix) }
        }
        _version.value = _version.value + 1
        val c = Change(removed, added)
        listeners.forEach { runCatching { it(c) } }
    }

    /**
     * True when [path] (or a folder above it) was removed and the file is not newer than the removal.
     * [modified] = the file's own date in ms (0 = unknown: then removed wins).
     */
    fun isGone(path: String, modified: Long = 0L): Boolean {
        if (removedAt.isEmpty()) return false
        var p = norm(path)
        while (p.isNotEmpty()) {
            val at = removedAt[p]
            if (at != null) return modified <= 0L || modified <= at + CLOCK_SLACK_MS
            val i = p.lastIndexOf('/')
            if (i <= 0) break
            p = p.substring(0, i)
        }
        return false
    }

    /** [items] without the removed ones; the same list instance when nothing was removed. */
    fun filter(items: List<FileItem>): List<FileItem> {
        if (removedAt.isEmpty() || items.isEmpty()) return items
        val out = items.filterNot { isGone(it.path, it.modified) }
        return if (out.size == items.size) items else out
    }

    fun filterPaths(paths: List<String>): List<String> {
        if (removedAt.isEmpty()) return paths
        return paths.filterNot { isGone(it) }
    }

    private fun norm(path: String): String {
        val p = if (path.startsWith("file://")) path.removePrefix("file://") else path
        return if (p.length > 1) p.trimEnd('/') else p
    }

    /** Tests only. */
    internal fun reset() {
        removedAt.clear()
        listeners.clear()
        _version.value = 0L
    }
}
