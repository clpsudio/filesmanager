package com.clpstudio.filesmanager.data

import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.crash.CrashLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * One list loader per category (and hidden-files setting), shared by every screen. Loads run on
 * Dispatchers.IO in a scope of their own, so leaving a screen never cancels them; a refresh keeps
 * the last good list on screen until the new one is ready (see [KeepLastGoodLoader]).
 */
object CategoryRepository {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashLog.coroutineHandler)
    private val loaders = ConcurrentHashMap<Pair<Category, Boolean>, KeepLastGoodLoader<List<FileItem>>>()

    init {
        // One deletion bus for every cached list: a deleted / moved file leaves every category,
        // Recent, Media and the Home counts at once; the reload that follows confirms it.
        FileEvents.addListener { change ->
            if (change.removed.isNotEmpty()) prune()
        }
    }

    /** Drops removed files from every loaded list and from the per-category cache. */
    fun prune() {
        loaders.values.forEach { it.prune(FileEvents::filter) }
        runCatching { com.clpstudio.filesmanager.ui.screens.CategoryCache.prune() }
    }

    fun loader(cat: Category, showHidden: Boolean): KeepLastGoodLoader<List<FileItem>> =
        loaders.getOrPut(cat to showHidden) {
            KeepLastGoodLoader(
                scope = scope,
                onLoaded = { list -> if (list.isNotEmpty()) runCatching { com.clpstudio.filesmanager.ui.screens.CategoryCache[cat] = list } },
            ) {
                FileEvents.filter(Graph.media.category(cat, Graph.hiddenFilter(showHidden)).distinctByPath())
            }.also { l ->
                // Seed with last run's list (read off the main thread) so counts show at once.
                if (!showHidden) scope.launch {
                    runCatching { com.clpstudio.filesmanager.ui.screens.CategoryCache[cat] }.getOrNull()
                        ?.distinctByPath()?.let { l.seed(FileEvents.filter(it)) }
                }
            }
        }

    fun state(cat: Category, showHidden: Boolean): StateFlow<Loadable<List<FileItem>>> = loader(cat, showHidden).state

    fun refresh(cat: Category, showHidden: Boolean) = loader(cat, showHidden).refresh()
}

/** What Home shows. A null / pending field means "still loading" (shown as "…", never as 0). */
data class HomeSnapshot(
    val storage: Loadable<Pair<Long, Long>> = Loadable(),
    val counts: Map<Category, Loadable<Int>> = emptyMap(),
    val recent: Loadable<List<FileItem>> = Loadable(),
    val bin: Loadable<Int> = Loadable(),
)

/** Home counts, category sizes, recent files and the Bin count, each loaded and kept independently. */
object HomeRepository {
    /** Categories whose own list Home counts; Media is Photos + Videos. */
    val counted = listOf(Category.PHOTOS, Category.VIDEOS, Category.AUDIO, Category.DOCUMENTS, Category.DOWNLOADS)

    private class Holder(val showHidden: Boolean) {
        val storage = KeepLastGoodLoader(CategoryRepository.scope) {
            val vol = StoragePaths.volumes(Graph.app).firstOrNull() ?: error("no storage volume")
            (vol.total - vol.free).coerceAtLeast(0) to vol.total
        }
        val bin = KeepLastGoodLoader(CategoryRepository.scope) { Graph.bin.list().size }
        val recent = CategoryRepository.loader(Category.RECENT, showHidden)
        val cats = counted.associateWith { CategoryRepository.loader(it, showHidden) }
        @Volatile var lastTick = Int.MIN_VALUE

        val snapshot: StateFlow<HomeSnapshot> = combine(
            listOf(storage.state, bin.state, recent.state) + cats.values.map { it.state },
        ) { arr ->
            @Suppress("UNCHECKED_CAST")
            val counts = counted.mapIndexed { i, c -> c to (arr[3 + i] as Loadable<List<FileItem>>).map { it.size } }.toMap()
            @Suppress("UNCHECKED_CAST")
            HomeSnapshot(
                storage = arr[0] as Loadable<Pair<Long, Long>>,
                bin = arr[1] as Loadable<Int>,
                recent = arr[2] as Loadable<List<FileItem>>,
                counts = counts + (Category.MEDIA to mediaCount(counts[Category.PHOTOS], counts[Category.VIDEOS])),
            )
        }.stateIn(CategoryRepository.scope, SharingStarted.Eagerly, HomeSnapshot())

        fun refreshAll() {
            storage.refresh(); bin.refresh(); recent.refresh()
            cats.values.forEach { it.refresh() }
        }
    }

    private val holders = ConcurrentHashMap<Boolean, Holder>()
    private fun holder(showHidden: Boolean) = holders.getOrPut(showHidden) { Holder(showHidden) }

    fun state(showHidden: Boolean): StateFlow<HomeSnapshot> = holder(showHidden).snapshot

    /**
     * Call on every Home show with the current refresh tick (bumped by storage changes, coming back
     * to the app, a permission grant). Loads the first time, reloads only when the tick moved, so
     * returning to Home is instant and never blank.
     */
    fun onShow(showHidden: Boolean, tick: Int) {
        val h = holder(showHidden)
        if (h.lastTick != tick) {
            h.lastTick = tick
            h.refreshAll()
        }
    }

    fun mediaCount(photos: Loadable<Int>?, videos: Loadable<Int>?): Loadable<Int> {
        val p = photos ?: return Loadable()
        val v = videos ?: return Loadable()
        val value = if (p.value != null && v.value != null) p.value + v.value else null
        return Loadable(
            value = value,
            loading = p.loading || v.loading,
            loaded = p.loaded && v.loaded,
            error = if (value == null) p.error ?: v.error else null,
        )
    }

    /** "1,240 items" / "1 item"; "…" while still loading; "—" if it couldn't be counted. */
    fun countLabel(l: Loadable<Int>?): String {
        val n = l?.value ?: return if (l?.error != null) "—" else "…"
        return NumberFormat.getIntegerInstance().format(n) + if (n == 1) " item" else " items"
    }
}

fun <T, R> Loadable<T>.map(f: (T) -> R): Loadable<R> = Loadable(value?.let(f), loading, loaded, error)
