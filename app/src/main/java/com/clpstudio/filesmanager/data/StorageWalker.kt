package com.clpstudio.filesmanager.data

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Walks storage directly (no MediaStore), for files the media index often misses: documents,
 * APKs, archives. One stat per entry (NIO), skips Android/data and Android/obb (not readable on
 * Android 11+), app-private roots, and hidden folders unless "Show hidden files" is on.
 * The `.nomedia` rule is NOT applied here: it's a hint for galleries, and WhatsApp keeps
 * documents in folders that have one.
 */
object StorageWalker {
    private val skipRelative = setOf("Android/data", "Android/obb")

    fun walk(
        roots: List<File>,
        showHidden: Boolean,
        privateRoots: List<String>,
        accept: (name: String) -> Boolean,
        maxResults: Int = 50_000,
        batch: Int = 400,
        onBatch: (List<FileItem>) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): List<FileItem> {
        val out = ArrayList<FileItem>()
        var lastEmit = 0
        for (root in roots) {
            if (!root.isDirectory) continue
            val rootPath = root.absolutePath.trimEnd('/')
            val skip = skipRelative.map { "$rootPath/$it" }.toSet()
            try {
                Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (isCancelled() || out.size >= maxResults) return FileVisitResult.TERMINATE
                        val p = dir.toString()
                        if (p == rootPath) return FileVisitResult.CONTINUE
                        if (p in skip) return FileVisitResult.SKIP_SUBTREE
                        if (privateRoots.any { p == it || p.startsWith("$it/") }) return FileVisitResult.SKIP_SUBTREE
                        if (!showHidden && dir.fileName?.toString()?.startsWith(".") == true) return FileVisitResult.SKIP_SUBTREE
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                        val name = file.fileName?.toString() ?: return FileVisitResult.CONTINUE
                        if (!showHidden && name.startsWith(".")) return FileVisitResult.CONTINUE
                        if (!accept(name)) return FileVisitResult.CONTINUE
                        out += FileItem(file.toString(), name, false, attrs.size(), attrs.lastModifiedTime().toMillis())
                        if (out.size - lastEmit >= batch) {
                            lastEmit = out.size
                            onBatch(out.toList())
                        }
                        return if (out.size >= maxResults) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                    }

                    // Unreadable folders are skipped, never fatal.
                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                })
            } catch (e: Exception) {
                Diagnostics.log("walk", "walk of $rootPath stopped early", e)
            }
        }
        return out
    }

    /** Merges lists by path (first one wins), newest first. */
    fun merge(vararg lists: List<FileItem>): List<FileItem> {
        val seen = LinkedHashMap<String, FileItem>()
        for (l in lists) for (f in l) seen.putIfAbsent(f.path, f)
        return seen.values.sortedByDescending { it.modified }
    }
}
