package com.clpstudio.filesmanager.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The parts of "Manage storage" that load on their own and fill in when ready. */
enum class StoragePart { FILES, APPS, BIN, UNUSED }

/** A part's result where null is a real answer ("can't be known"), not "not loaded yet". */
data class Part<T>(val v: T)

/**
 * App code + data of every installed app ([total]) and of this app alone ([own]): our own Bin lives
 * in our data, so it's taken out of the Apps number (it's shown as Bin).
 */
data class AppsSize(val total: Long, val own: Long) {
    fun excludingBin(bin: Long?): Long = (total - minOf(own, (bin ?: 0L).coerceAtLeast(0L))).coerceAtLeast(0L)
}

data class UnusedPart(val access: Boolean, val count: Int?)

/** The files part: one report per volume, without apps / bin (those come in on their own). */
data class FilesPart(val volumes: List<VolumeReport>)

/** Where the numbers come from; the Android one is in [ManageStorageRepository], tests use fakes. */
interface StorageSource {
    fun volumes(): List<StorageVolume>
    /** Every indexed file with a size on these volumes (hidden-file rules applied). */
    suspend fun scanFiles(volumes: List<StorageVolume>): List<FileItem>
    suspend fun binBytes(): Long?
    fun hasUsageAccess(): Boolean
    suspend fun appsSize(): AppsSize?
    suspend fun unusedApps(): Int?
    /** Drops files deleted / moved since they were read. */
    fun live(files: List<FileItem>): List<FileItem> = files
    /** Quick content hash for the duplicate pass (null = unreadable). */
    fun hash(file: FileItem): String?
    fun now(): Long = System.currentTimeMillis()
}

/** The last numbers, kept in app-private storage so Manage storage opens with them at once. */
data class StorageSnapshot(
    val savedAt: Long,
    val usageAccess: Boolean,
    val unused: Int?,
    val bin: Long?,
    val apps: AppsSize?,
    val volumes: List<VolumeSnapshot>,
    val duplicateBytes: Map<String, Long> = emptyMap(),
)

data class VolumeSnapshot(val path: String, val sizes: Map<StorageKind, Long>, val otherBytes: Long, val largeSize: Long)

/** Reads / writes [StorageSnapshot] as a small JSON file. Never throws. */
class StorageCache(private val fileProvider: () -> File) {
    constructor(file: File) : this({ file })

    private val file: File get() = fileProvider()

    fun read(): StorageSnapshot? = runCatching {
        if (!file.isFile) return null
        val j = JSONObject(file.readText())
        if (j.optInt("v") != VERSION) return null
        val vols = j.getJSONArray("volumes")
        StorageSnapshot(
            savedAt = j.getLong("savedAt"),
            usageAccess = j.optBoolean("usageAccess"),
            unused = if (j.has("unused")) j.getInt("unused") else null,
            bin = if (j.has("bin")) j.getLong("bin") else null,
            apps = j.optJSONObject("apps")?.let { AppsSize(it.getLong("total"), it.getLong("own")) },
            volumes = List(vols.length()) { i ->
                val v = vols.getJSONObject(i)
                val s = v.getJSONObject("sizes")
                VolumeSnapshot(
                    path = v.getString("path"),
                    sizes = StorageKind.entries.filter { s.has(it.name) }.associateWith { s.getLong(it.name) },
                    otherBytes = v.optLong("other"),
                    largeSize = v.optLong("large"),
                )
            },
            duplicateBytes = j.optJSONObject("dups")?.let { d -> d.keys().asSequence().associateWith { d.getLong(it) } }.orEmpty(),
        )
    }.getOrNull()

    fun write(s: StorageSnapshot) {
        runCatching {
            val j = JSONObject()
            j.put("v", VERSION)
            j.put("savedAt", s.savedAt)
            j.put("usageAccess", s.usageAccess)
            s.unused?.let { j.put("unused", it) }
            s.bin?.let { j.put("bin", it) }
            s.apps?.let { j.put("apps", JSONObject().put("total", it.total).put("own", it.own)) }
            val arr = JSONArray()
            s.volumes.forEach { v ->
                val sizes = JSONObject()
                v.sizes.forEach { (k, b) -> sizes.put(k.name, b) }
                arr.put(JSONObject().put("path", v.path).put("sizes", sizes).put("other", v.otherBytes).put("large", v.largeSize))
            }
            j.put("volumes", arr)
            val d = JSONObject()
            s.duplicateBytes.forEach { (p, b) -> d.put(p, b) }
            j.put("dups", d)
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(j.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.onFailure { runCatching { Diagnostics.log("storage", "could not save the storage cache", it) } }
    }

    companion object { const val VERSION = 1 }
}

/** Pure: puts the parts (or the cached numbers for parts still loading) together for the screen. */
object StorageCombine {
    fun combine(
        volumesNow: List<StorageVolume>,
        files: Loadable<FilesPart>,
        apps: Loadable<Part<AppsSize?>>,
        bin: Loadable<Part<Long?>>,
        unused: Loadable<UnusedPart>,
        snap: StorageSnapshot?,
        accessNow: Boolean?,
    ): Loadable<ManageStorageData> {
        val pending = LinkedHashSet<StoragePart>()
        val binV: Long? = when {
            bin.value != null -> bin.value.v
            snap != null -> snap.bin
            else -> { if (bin.error == null) pending += StoragePart.BIN; null }
        }
        val appsKnown: Part<AppsSize?>? = apps.value ?: snap?.let { Part(it.apps) }
        if (appsKnown == null && apps.error == null) pending += StoragePart.APPS
        val unusedKnown: UnusedPart? = unused.value ?: snap?.let { UnusedPart(it.usageAccess, it.unused) }
        if (unusedKnown == null && unused.error == null) pending += StoragePart.UNUSED
        val access = accessNow ?: unusedKnown?.access ?: false
        val appsBytes = if (!access) null else appsKnown?.v?.excludingBin(binV)
        val reports: List<VolumeReport> = when {
            files.value != null -> files.value.volumes
            snap != null -> volumesNow.mapIndexedNotNull { i, vol ->
                snap.volumes.firstOrNull { it.path == vol.path }?.let { s ->
                    VolumeReport(vol, i == 0, emptyMap(), s.otherBytes, null, null, emptyList(), emptyList(), emptyList(), cachedSizes = s.sizes, cachedLargeSize = s.largeSize)
                }
            }
            else -> { if (files.error == null) pending += StoragePart.FILES; emptyList() }
        }
        if (files.value == null && snap != null && reports.isEmpty() && files.error == null) pending += StoragePart.FILES
        val withExtras = reports.map { r -> if (r.isPrimary) r.copy(appsBytes = appsBytes, binBytes = binV) else r }
        val anything = files.value != null || snap != null || bin.value != null || apps.value != null || unused.value != null
        val data = if (!anything) null else ManageStorageData(
            volumes = withExtras,
            usageAccess = access,
            unusedApps = if (access) unusedKnown?.count else null,
            pending = pending,
            binBytes = binV,
            duplicateBytes = snap?.duplicateBytes.orEmpty(),
            savedAt = if (files.value == null) snap?.savedAt else null,
        )
        return Loadable(
            value = data,
            loading = files.loading || apps.loading || bin.loading || unused.loading,
            loaded = files.loaded,
            error = if (files.value == null) files.error else null,
        )
    }

    fun snapshot(data: ManageStorageData, apps: AppsSize?, duplicates: Map<String, List<DuplicateSet>>?, now: Long): StorageSnapshot =
        StorageSnapshot(
            savedAt = now,
            usageAccess = data.usageAccess,
            unused = data.unusedApps,
            bin = data.binBytes,
            apps = apps,
            volumes = data.volumes.map { r ->
                VolumeSnapshot(r.volume.path, r.fileSizes, r.otherBytes, r.largeSize)
            },
            duplicateBytes = duplicates?.mapValues { (_, sets) -> sets.sumOf { it.reclaimable } } ?: data.duplicateBytes,
        )
}

/**
 * Manage storage, loaded in parts. Files (per-volume breakdown, large files, suggestions), app sizes,
 * the Bin and the unused-apps count each load on their own in parallel on [io] and fill in when ready:
 * the volume breakdown never waits for app sizes. The last numbers are saved with a timestamp and
 * shown at once on the next open (even after a restart) while a refresh runs. Duplicates (the slow
 * part: reads 128 KB of each same-size file) run after the rest, only while a storage screen is
 * open (or at once when the Duplicates screen asks), remember hashes of unchanged files, and stop
 * when the screens close (picked up again on the next open). The part loaders run in the app
 * scope, so leaving the screen never loses a refresh.
 */
class StorageEngine(
    private val scope: CoroutineScope,
    private val source: StorageSource,
    private val cache: StorageCache? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val snap = MutableStateFlow<StorageSnapshot?>(null)
    private val cacheRead = AtomicBoolean(false)
    @Volatile private var lastRefreshAt = 0L
    @Volatile private var lastSaved: StorageSnapshot? = null

    val files: KeepLastGoodLoader<FilesPart> = KeepLastGoodLoader(scope, onLoaded = { onFilesLoaded() }) {
        withContext(io) { computeFiles() }
    }
    val apps: KeepLastGoodLoader<Part<AppsSize?>> = KeepLastGoodLoader(scope) {
        withContext(io) { Part(if (source.hasUsageAccess()) runCatching { source.appsSize() }.getOrNull() else null) }
    }
    val bin: KeepLastGoodLoader<Part<Long?>> = KeepLastGoodLoader(scope) {
        withContext(io) { Part(runCatching { source.binBytes() }.getOrNull()) }
    }
    val unused: KeepLastGoodLoader<UnusedPart> = KeepLastGoodLoader(scope) {
        withContext(io) {
            val a = source.hasUsageAccess()
            UnusedPart(a, if (a) runCatching { source.unusedApps() }.getOrNull() else null)
        }
    }

    val state: StateFlow<Loadable<ManageStorageData>> =
        combine(combine(files.state, apps.state, bin.state, ::Triple), unused.state, snap) { (f, a, b), u, s ->
            StorageCombine.combine(runCatching { source.volumes() }.getOrDefault(emptyList()), f, a, b, u, s, runCatching { source.hasUsageAccess() }.getOrNull())
        }.stateIn(scope, SharingStarted.Eagerly, Loadable())

    // ---- duplicates ----
    private val _dups = MutableStateFlow(Loadable<Map<String, List<DuplicateSet>>>())
    val duplicates: StateFlow<Loadable<Map<String, List<DuplicateSet>>>> = _dups.asStateFlow()
    private val hashMemo = ConcurrentHashMap<String, String>()
    private val watchers = AtomicInteger(0)
    @Volatile private var dupDirty = false
    @Volatile private var dupJob: Job? = null
    /** How many files the last duplicate pass actually read (memo misses); for tests. */
    @Volatile var lastHashReads = 0
        private set

    init {
        // Save the numbers once every part has settled (and again when duplicates land).
        scope.launch {
            state.map { st -> st.takeIf { it.loaded && !it.loading }?.value }
                .distinctUntilChanged()
                .collect { d -> if (d != null) save(d) }
        }
    }

    /** First open: cached numbers at once, then every part loads (no-op if already loaded/running). */
    fun ensureLoaded() {
        readCacheOnce()
        files.ensureLoaded(); apps.ensureLoaded(); bin.ensureLoaded(); unused.ensureLoaded()
    }

    /** Opening the screen again: refresh unless the numbers are only seconds old. */
    fun open(minAgeMs: Long = 15_000) {
        readCacheOnce()
        val fresh = files.state.value.loaded && source.now() - lastRefreshAt < minAgeMs
        if (files.state.value.loaded && !fresh) refresh() else ensureLoaded()
    }

    fun refresh() {
        readCacheOnce()
        lastRefreshAt = source.now()
        files.refresh(); apps.refresh(); bin.refresh(); unused.refresh()
    }

    /** Files changed (delete / move / copy): only the file numbers and the Bin can have moved. */
    fun refreshFiles() {
        lastRefreshAt = source.now()
        files.refresh(); bin.refresh()
    }

    /** Usage access granted / taken away: app sizes and unused apps only. */
    fun refreshUsage() { apps.refresh(); unused.refresh() }

    /** A storage screen is showing (duplicates may run). */
    fun watch() {
        watchers.incrementAndGet()
        if (dupDirty || (_dups.value.value == null && files.state.value.value != null)) scheduleDuplicates(waitForRest = true)
    }

    /** A storage screen left: stop the duplicate pass if nothing shows it any more (picked up next open). */
    fun unwatch() {
        if (watchers.decrementAndGet() <= 0) {
            watchers.set(0)
            dupJob?.let { if (it.isActive) { it.cancel(); dupDirty = true } }
        }
    }

    /** The Duplicates screen is open: hash now, without waiting for the other parts. */
    fun requestDuplicates() {
        if (dupDirty || _dups.value.value == null) scheduleDuplicates(waitForRest = false, force = true)
    }

    fun prune(keep: (List<FileItem>) -> List<FileItem>) {
        files.prune { p -> p.copy(volumes = p.volumes.map { it.pruned(keep) }) }
        _dups.update { s ->
            s.value?.let { m -> s.copy(value = m.mapValues { (_, sets) -> sets.map { DuplicateSet(keep(it.files)) }.filter { it.files.size >= 2 } }) } ?: s
        }
    }

    private fun readCacheOnce() {
        if (cache == null || !cacheRead.compareAndSet(false, true)) return
        // Tiny file; read on the caller so the very first frame can already show it.
        snap.value = cache.read()
    }

    private suspend fun computeFiles(): FilesPart {
        val vols = source.volumes()
        val all = source.live(source.scanFiles(vols))
        val roots = vols.map { it.path }
        val perVol = all.groupBy { StorageMath.volumeOf(it.path, roots) }
        val now = source.now()
        val reports = coroutineScope {
            vols.mapIndexed { i, v -> async(io) { StorageMath.report(v, i == 0, perVol[v.path].orEmpty(), null, null, now) } }.awaitAll()
        }
        return FilesPart(reports)
    }

    private fun onFilesLoaded() {
        dupDirty = true
        dupJob?.cancel()
        if (watchers.get() > 0) scheduleDuplicates(waitForRest = true)
    }

    private fun scheduleDuplicates(waitForRest: Boolean, force: Boolean = false) {
        if (!force && watchers.get() <= 0) { dupDirty = true; return }
        val running = dupJob
        if (running != null && running.isActive) {
            if (!force) return
        }
        running?.cancel()
        dupJob = scope.launch(io) {
            if (waitForRest) {
                // After the rest: the hash pass reads files and would slow the other parts down.
                combine(apps.state, bin.state, unused.state, files.state) { a, b, u, f -> a.loading || b.loading || u.loading || f.loading }
                    .first { !it }
            }
            val part = files.state.value.value ?: return@launch
            _dups.update { it.copy(loading = true) }
            try {
                val ctx = currentCoroutineContext()
                var reads = 0
                val result = part.volumes.associate { v ->
                    v.volume.path to StorageMath.groupDuplicates(source.live(v.dupCandidates), hash = { f ->
                        if (!ctx.isActive) throw CancellationException("left the storage screens")
                        val key = "${f.path}|${f.size}|${f.modified}"
                        hashMemo[key] ?: source.hash(f)?.also { h ->
                            reads++
                            if (hashMemo.size > 50_000) hashMemo.clear()
                            hashMemo[key] = h
                        }
                    })
                }
                lastHashReads = reads
                dupDirty = false
                _dups.value = Loadable(value = result, loading = false, loaded = true)
                state.value.value?.takeIf { state.value.loaded }?.let { save(it) }
            } catch (e: CancellationException) {
                dupDirty = true
                _dups.update { it.copy(loading = false) }
                throw e
            } catch (e: Throwable) {
                runCatching { Diagnostics.log("storage", "duplicate pass failed", e) }
                _dups.update { it.copy(loading = false, error = e) }
            }
        }
    }

    private fun save(d: ManageStorageData) {
        val c = cache ?: return
        val s = StorageCombine.snapshot(d, apps.state.value.value?.v, _dups.value.value, source.now())
        lastSaved?.let { old -> if (old.copy(savedAt = 0) == s.copy(savedAt = 0)) return }
        lastSaved = s
        c.write(s)
    }
}
