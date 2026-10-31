package com.clpstudio.filesmanager.data

import android.content.Context
import com.clpstudio.filesmanager.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Photos, videos and audio that MediaStore never indexes: dot-files, anything inside a dot-folder,
 * and media in a folder holding a `.nomedia` file (WhatsApp's private folders, our own Hide's
 * ".hidden" folders, apps' caches). With "Show hidden files" on, these join every grid
 * (Photos, Videos, Audios, Media, Recent, the Folders tab, Home counts, pickers); with it off
 * they appear nowhere. Pure rules here, unit-tested; [HiddenMediaIndex] caches the walk.
 */
object HiddenMedia {
    /** System bookkeeping, never "your" files: thumbnail caches and MediaStore's own trash. */
    private val SKIP_DIRS = setOf(".thumbnails", ".Thumbnails", ".trashed", ".pending", ".Trash", ".trash", ".Trash-1000")
    private val SKIP_RELATIVE = setOf("Android/data", "Android/obb")

    fun isMedia(name: String): Boolean {
        val ext = FileTypes.extension(name)
        return ext.isNotEmpty() && (ext in FileTypes.imageExt || ext in FileTypes.rawExt || ext in FileTypes.videoExt || ext in FileTypes.audioExt)
    }

    /** MediaStore's trash (".trashed-<time>-name.jpg") and half-written files (".pending-..."). */
    fun isSystemTemp(name: String): Boolean = name.startsWith(".trashed-") || name.startsWith(".pending-")

    /** Which media category a file belongs to (Media = Photos + Videos). */
    fun inCategory(item: FileItem, cat: Category): Boolean = when (cat) {
        Category.PHOTOS -> item.kind == FileKind.IMAGE
        Category.VIDEOS -> item.kind == FileKind.VIDEO
        Category.AUDIO -> item.kind == FileKind.AUDIO
        Category.MEDIA -> item.kind == FileKind.IMAGE || item.kind == FileKind.VIDEO
        Category.RECENT -> item.kind == FileKind.IMAGE || item.kind == FileKind.VIDEO || item.kind == FileKind.AUDIO
        else -> false
    }

    /**
     * Whether [path] sits in a hidden place (a dot-name between the storage root and the file, or a
     * folder above it holding `.nomedia`). [hasNoMedia] is injectable for tests.
     */
    fun isHiddenPath(path: String, roots: List<String>, hasNoMedia: (String) -> Boolean = { File(it, ".nomedia").exists() }): Boolean {
        val root = roots.firstOrNull { path.startsWith("$it/") } ?: return false
        val rel = path.removePrefix(root).trim('/')
        if (rel.split('/').any { it.startsWith(".") }) return true
        var dir = path.substringBeforeLast('/', "")
        while (dir.length > root.length) {
            if (hasNoMedia(dir)) return true
            dir = dir.substringBeforeLast('/', "")
        }
        return false
    }

    /**
     * One bounded walk of [roots] that keeps only hidden media files. Skips Android/data and
     * Android/obb (unreadable on 11+), app-private roots (vault, Bins), thumbnail caches and
     * MediaStore's trash. One stat per entry plus one `.nomedia` check per folder.
     */
    fun walk(
        roots: List<File>,
        privateRoots: List<String>,
        maxDepth: Int = 16,
        maxResults: Int = 20_000,
        maxFolders: Int = 150_000,
        isCancelled: () -> Boolean = { false },
    ): List<FileItem> {
        val out = ArrayList<FileItem>()
        var folders = 0
        for (root in roots) {
            if (!root.isDirectory) continue
            val rootPath = root.absolutePath.trimEnd('/')
            val skip = SKIP_RELATIVE.map { "$rootPath/$it" }.toSet()
            // Hidden state of each open folder (the walk is depth-first).
            val stack = ArrayDeque<Boolean>()
            try {
                Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (isCancelled() || out.size >= maxResults || folders >= maxFolders) return FileVisitResult.TERMINATE
                        val p = dir.toString()
                        if (p == rootPath) { stack.addLast(false); folders++; return FileVisitResult.CONTINUE }
                        val name = dir.fileName?.toString().orEmpty()
                        if (p in skip || name in SKIP_DIRS) return FileVisitResult.SKIP_SUBTREE
                        if (privateRoots.any { p == it || p.startsWith("$it/") }) return FileVisitResult.SKIP_SUBTREE
                        if (stack.size > maxDepth) return FileVisitResult.SKIP_SUBTREE
                        val parentHidden = stack.lastOrNull() ?: false
                        val hidden = parentHidden || name.startsWith(".") || File(p, ".nomedia").exists()
                        stack.addLast(hidden)
                        folders++
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        stack.removeLastOrNull()
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                        val name = file.fileName?.toString() ?: return FileVisitResult.CONTINUE
                        val inHidden = stack.lastOrNull() ?: false
                        if (!inHidden && !name.startsWith(".")) return FileVisitResult.CONTINUE
                        if (isSystemTemp(name) || !isMedia(name)) return FileVisitResult.CONTINUE
                        out += FileItem(file.toString(), name, false, attrs.size(), attrs.lastModifiedTime().toMillis())
                        return if (out.size >= maxResults) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                })
            } catch (e: Exception) {
                Diagnostics.log("hidden", "hidden-media walk of $rootPath stopped early", e)
            }
        }
        return out
    }

    /**
     * What a grid shows: with "Show hidden files" ON, the index rows plus the hidden media that
     * [accept] takes, de-duplicated by path (index row wins), newest first; vault, Bins and
     * switched-off SD cards never. OFF: only visible index rows, hidden ones dropped.
     */
    fun merge(indexed: List<FileItem>, hidden: List<FileItem>, filter: HiddenFilter, accept: (FileItem) -> Boolean = { true }): List<FileItem> {
        if (!filter.showHidden) return indexed.filter { filter.visiblePath(it.path) }
        val seen = LinkedHashMap<String, FileItem>(indexed.size + hidden.size)
        for (f in indexed) if (!filter.isPrivate(f.path)) seen.putIfAbsent(f.path, f)
        for (f in hidden) if (accept(f) && !filter.isPrivate(f.path)) seen.putIfAbsent(f.path, f)
        return seen.values.sortedByDescending { it.modified }
    }
}

/**
 * The cached hidden-media list. Walked once, in the background, only while something with
 * "Show hidden files" on asks for it; kept until storage changes ([invalidate]: coming back to the
 * app, a MediaStore change, an SD card in/out). Files the app deletes, moves, hides or unhides are
 * patched in place from [FileEvents], with no new walk.
 */
class HiddenMediaIndex(private val context: Context) {
    data class Snapshot(val items: List<FileItem>, val complete: Boolean, val generation: Int)

    private val state = MutableStateFlow<Snapshot?>(null)
    private val _version = MutableStateFlow(0L)
    /** Bumped whenever the list changes (walk finished, file events patched in). */
    val version: StateFlow<Long> = _version
    @Volatile private var job: Job? = null
    @Volatile private var generation = 0

    init {
        FileEvents.addListener { change -> onChange(change) }
    }

    private fun onChange(change: FileEvents.Change) {
        val snap = state.value ?: return
        var items = if (change.removed.isEmpty()) snap.items else FileEvents.filter(snap.items).let { kept ->
            // A moved/renamed path is not "newer" than its removal, so FileEvents drops it; also drop by prefix.
            kept.filterNot { f -> change.removed.any { r -> f.path == r || f.path.startsWith("$r/") } }
        }
        var needWalk = false
        if (change.added.isNotEmpty()) {
            val roots = runCatching { StoragePaths.volumes(context).map { it.path } }.getOrDefault(emptyList())
            val privateRoots = runCatching { StoragePaths.privateRoots(context) }.getOrDefault(emptyList())
            val known = items.mapTo(HashSet()) { it.path }
            val extra = ArrayList<FileItem>()
            for (p in change.added) {
                val f = File(p)
                if (f.isDirectory) { needWalk = true; continue }
                if (p in known || !f.isFile || !HiddenMedia.isMedia(f.name) || HiddenMedia.isSystemTemp(f.name)) continue
                if (privateRoots.any { p == it || p.startsWith("$it/") }) continue
                if (HiddenMedia.isHiddenPath(p, roots)) extra += FileItem.of(f)
            }
            if (extra.isNotEmpty()) items = (items + extra).sortedByDescending { it.modified }
        }
        if (items !== snap.items) { state.value = snap.copy(items = items); _version.value++ }
        if (needWalk) invalidate()
    }

    /** Storage changed: the next observer walks again (the old list stays on screen meanwhile). */
    fun invalidate() {
        generation++
    }

    fun cached(): List<FileItem>? = state.value?.items?.let(FileEvents::filter)

    @Synchronized
    fun ensure() {
        val current = state.value
        if (job?.isActive == true) return
        if (current != null && current.complete && current.generation == generation) return
        val gen = generation
        job = Graph.appScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            var result: List<FileItem>? = null
            try {
                val roots = StoragePaths.volumes(context).map { File(it.path) }
                result = HiddenMedia.walk(roots, StoragePaths.privateRoots(context), isCancelled = { !isActive })
                Diagnostics.log("hidden", "hidden-media walk: ${result.size} files in ${System.currentTimeMillis() - t0} ms")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Diagnostics.log("hidden", "hidden-media walk failed, keeping the previous list", e)
            } finally {
                val keep = FileEvents.filter((result ?: current?.items.orEmpty()).sortedByDescending { it.modified })
                val changed = keep.map { it.path to it.modified } != current?.items?.map { it.path to it.modified }
                state.value = Snapshot(keep, true, gen)
                if (changed) {
                    _version.value++
                    // Lists loaded once (Home counts, pickers) pick up the new hidden files.
                    if (current != null) runCatching { CategoryRepository.refreshHiddenMedia() }
                }
            }
            if (generation != gen) Graph.appScope.launch {
                job?.join()
                ensure()
            }
        }
    }

    fun observe(): Flow<Snapshot> {
        ensure()
        return state.filterNotNull()
    }

    /** The current list, waiting for the first walk only if none ever finished. */
    suspend fun complete(): List<FileItem> = FileEvents.filter(observe().first { it.complete }.items)

    /** Tests only. */
    internal fun setForTest(items: List<FileItem>) {
        state.value = Snapshot(items, true, generation)
    }
}
