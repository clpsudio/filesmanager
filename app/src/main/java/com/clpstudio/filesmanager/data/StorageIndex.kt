package com.clpstudio.filesmanager.data

import android.content.Context
import com.clpstudio.filesmanager.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A direct walk of all storage for documents, APKs and archives (MediaStore often misses or
 * mislabels them). One shared walk feeds Documents, Recent, Apps and the Home counts. Results
 * stream in while the walk runs; a rescan keeps the previous list on screen until it finishes.
 */
class StorageIndex(private val context: Context) {
    data class Snapshot(val items: List<FileItem>, val complete: Boolean, val generation: Int)

    private val states = ConcurrentHashMap<Boolean, MutableStateFlow<Snapshot?>>()
    private val jobs = ConcurrentHashMap<Boolean, Job>()
    @Volatile private var generation = 0

    /** Storage changed: the next observer triggers a fresh walk. */
    fun invalidate() {
        generation++
    }

    private fun state(showHidden: Boolean) = states.getOrPut(showHidden) { MutableStateFlow(null) }

    fun cached(showHidden: Boolean): List<FileItem>? = state(showHidden).value?.items

    @Synchronized
    fun ensure(showHidden: Boolean) {
        val flow = state(showHidden)
        val current = flow.value
        if (jobs[showHidden]?.isActive == true) return
        if (current != null && current.complete && current.generation == generation) return
        val gen = generation
        jobs[showHidden] = Graph.appScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val hadList = current != null
            var result: List<FileItem>? = null
            try {
                val roots = StoragePaths.volumes(context).map { File(it.path) }
                result = StorageWalker.walk(
                    roots = roots,
                    showHidden = showHidden,
                    privateRoots = StoragePaths.privateRoots(context),
                    accept = ::accept,
                    onBatch = { partial -> if (!hadList) flow.value = Snapshot(partial, false, gen) },
                    isCancelled = { !isActive },
                )
                Diagnostics.log(
                    "index",
                    "storage walk (${roots.joinToString { it.path }}): ${result.size} documents/APKs/archives in ${System.currentTimeMillis() - t0} ms",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Permission revoked, a volume unmounted mid-walk, OOM on a huge tree...: never leave
                // waiters (Home counts, Documents, Recent) hanging on a walk that will never finish.
                Diagnostics.log("index", "storage walk failed, keeping the previous list", e)
            } finally {
                // Always end with a complete snapshot: the new list, or the last good one.
                val keep = result ?: current?.items ?: flow.value?.items.orEmpty()
                flow.value = Snapshot(keep, true, gen)
            }
            if (generation != gen) Graph.appScope.launch {
                // Let this job finish first, otherwise ensure() sees it still active and skips.
                jobs[showHidden]?.join()
                ensure(showHidden)
            }
        }
    }

    fun observe(showHidden: Boolean): Flow<Snapshot> {
        ensure(showHidden)
        return state(showHidden).filterNotNull()
    }

    /** Waits for a complete walk (returns at once if one is already cached). */
    suspend fun complete(showHidden: Boolean): List<FileItem> = observe(showHidden).first { it.complete }.items

    companion object {
        fun accept(name: String): Boolean = FileTypes.isDocument(name) || FileTypes.isApk(name) || FileTypes.isArchive(name)
    }
}
