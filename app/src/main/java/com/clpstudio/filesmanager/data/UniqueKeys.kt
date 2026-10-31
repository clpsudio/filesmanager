package com.clpstudio.filesmanager.data

/*
 * Lazy lists, grids and pagers crash ("Key ... was already used") when two items share a key.
 * Our keys are file paths and section labels, so lists are made unique before they are drawn.
 */

/** Unique by path, first one wins (lazy lists and pagers crash on duplicate keys). */
fun List<FileItem>.distinctByPath(): List<FileItem> {
    if (size < 2) return this
    val seen = HashSet<String>(size * 2)
    var dup = false
    for (f in this) if (!seen.add(f.path)) { dup = true; break }
    if (!dup) return this
    seen.clear()
    return filter { seen.add(it.path) }
}

/** Same for plain paths. */
fun List<String>.distinctPaths(): List<String> = if (size < 2) this else distinct()

/**
 * Section groups safe to draw with "§label" header keys and path item keys: groups with the same
 * label are merged (in first-seen order; e.g. folders-first + date sort gave "Today" twice), and a
 * path that appears again (in any group) is dropped.
 */
fun uniqueSections(groups: List<Pair<String, List<FileItem>>>): List<Pair<String, List<FileItem>>> {
    val seen = HashSet<String>()
    val merged = LinkedHashMap<String, MutableList<FileItem>>()
    for ((label, list) in groups) {
        val target = merged.getOrPut(label) { ArrayList() }
        for (f in list) if (seen.add(f.path)) target.add(f)
    }
    return merged.entries.filter { it.value.isNotEmpty() || it.key.isEmpty() }.map { it.key to it.value }
}
