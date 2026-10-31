package com.clpstudio.filesmanager.data

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.provider.MediaStore
import com.clpstudio.filesmanager.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** What "Manage storage" shows: one report per volume plus the app-wide extras. */
data class ManageStorageData(
    val volumes: List<VolumeReport>,
    /** True when Usage access is granted (app sizes and unused apps can be read). */
    val usageAccess: Boolean,
    /** Apps not used in the past 30 days; null when unknown (no usage access). */
    val unusedApps: Int?,
    /** Parts still loading for the first time (shown as "…"); the others are already filled in. */
    val pending: Set<StoragePart> = emptySet(),
    /** Bytes in the Bin (null = not known). */
    val binBytes: Long? = volumes.firstOrNull { it.isPrimary }?.binBytes,
    /** Reclaimable duplicate bytes per volume from the last visit, until the duplicate pass lands. */
    val duplicateBytes: Map<String, Long> = emptyMap(),
    /** When the shown file numbers were saved (null = they're from this visit). */
    val savedAt: Long? = null,
)

/**
 * Computes "Manage storage" in the background, entirely on the device: one MediaStore Files-table
 * query per volume (a direct walk if that fails), sorted into kinds by extension. Nothing here uses
 * the system Files app or the Settings storage screen.
 *
 * Why it was slow (Oct 31): the whole screen waited for one serial report: the file query, then the
 * Bin, then StorageStatsManager.queryStatsForPackage for every installed package one by one (5-20 ms
 * each on a phone, hundreds of packages = seconds), then the unused-apps pass (labels + package info
 * per app), and only then showed any number; nothing was kept across app restarts, and every finished
 * report started a full duplicate hash pass again. Now each part loads on its own in parallel
 * ([StorageEngine]), app sizes come from one queryStatsForUser call, the last numbers are saved and
 * shown at once, and duplicates run last, lazily, with remembered hashes.
 */
object ManageStorageRepository {
    private val scope get() = CategoryRepository.scope
    private var debounce: Job? = null

    val engine: StorageEngine by lazy {
        StorageEngine(
            CategoryRepository.scope,
            // Both read the app lazily: the engine can exist before Graph is set up (tests, early callbacks).
            AndroidStorageSource { Graph.app },
            StorageCache { File(Graph.app.filesDir, "manage_storage_cache.json") },
        )
    }

    /** Duplicate sets per volume path (the slow part: reads 128 KB of each same-size file). */
    val duplicates: StateFlow<Loadable<Map<String, List<DuplicateSet>>>> get() = engine.duplicates

    init {
        // Deleted / moved files leave every number and list at once; the screen recomputes after.
        FileEvents.addListener { change -> if (change.removed.isNotEmpty()) prune() }
        // Usage access granted / taken away (item 36): app sizes and unused apps come in without a restart.
        UsageAccess.onChange { refreshUsage() }
    }

    fun state(): StateFlow<Loadable<ManageStorageData>> = engine.state

    fun ensureLoaded() = engine.ensureLoaded()

    /** The Manage storage screen opened: saved numbers at once, refresh unless just refreshed. */
    fun open() = engine.open()

    fun refresh() = engine.refresh()

    fun refreshUsage() = engine.refreshUsage()

    fun watch() = engine.watch()

    fun unwatch() = engine.unwatch()

    fun requestDuplicates() = engine.requestDuplicates()

    /** Recompute shortly after the last change (a burst of deletes = one recompute). */
    fun scheduleRefresh(delayMs: Long = 1_500, filesOnly: Boolean = false) {
        debounce?.cancel()
        debounce = scope.launch { delay(delayMs); if (filesOnly) engine.refreshFiles() else refresh() }
    }

    fun prune() = engine.prune(FileEvents::filter)

    @Suppress("DEPRECATION")
    fun hasUsageAccess(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

}

/** The real device: MediaStore, StorageStatsManager, UsageStatsManager, the Bin. */
class AndroidStorageSource(private val contextProvider: () -> Context) : StorageSource {
    private val context: Context get() = contextProvider()

    override fun volumes(): List<StorageVolume> = StoragePaths.volumes(context)

    override suspend fun scanFiles(volumes: List<StorageVolume>): List<FileItem> =
        scanFiles(context, Graph.hiddenFilter().withoutNoMedia(), volumes)

    override suspend fun binBytes(): Long? = Graph.bin.totalSize()

    override fun hasUsageAccess(): Boolean = ManageStorageRepository.hasUsageAccess(context)

    override suspend fun unusedApps(): Int? = UnusedApps.load(context).size

    override fun live(files: List<FileItem>): List<FileItem> = FileEvents.filter(files)

    override fun hash(file: FileItem): String? = StorageMath.quickHash(File(file.path))

    /**
     * App code + data of every installed app on internal storage, and of this app (whose data holds
     * the Bin). One queryStatsForUser call sums every app of this user; only if that fails, every
     * package is asked on its own, 8 at a time (it used to be one by one: seconds on a phone).
     */
    override suspend fun appsSize(): AppsSize? {
        val ssm = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val user = Process.myUserHandle()
        val own = runCatching { ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, context.packageName, user) }
            .getOrNull()?.let { it.appBytes + it.dataBytes } ?: 0L
        runCatching { ssm.queryStatsForUser(StorageManager.UUID_DEFAULT, user) }.getOrNull()?.let { st ->
            val t = st.appBytes + st.dataBytes
            if (t > 0) return AppsSize(t, own)
        }
        val pkgs = runCatching { context.packageManager.getInstalledApplications(0).take(1_500).map { it.packageName } }.getOrDefault(emptyList())
        if (pkgs.isEmpty()) return null
        val sums = coroutineScope {
            pkgs.chunked(((pkgs.size + 7) / 8).coerceAtLeast(1)).map { chunk ->
                async(Dispatchers.IO) {
                    var sum = 0L
                    var ok = 0
                    for (p in chunk) {
                        val st = runCatching { ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, p, user) }.getOrNull() ?: continue
                        sum += st.appBytes + st.dataBytes
                        ok++
                    }
                    sum to ok
                }
            }.awaitAll()
        }
        if (sums.sumOf { it.second } == 0) return null
        return AppsSize(sums.sumOf { it.first }, own)
    }

    private fun volumeUris(context: Context): List<Uri> {
        if (Build.VERSION.SDK_INT >= 29) {
            val names = runCatching { MediaStore.getExternalVolumeNames(context).toList() }.getOrNull().orEmpty()
            if (names.isNotEmpty()) return names.map { MediaStore.Files.getContentUri(it) }
        }
        return listOf(MediaStore.Files.getContentUri("external"))
    }

    /** Every file with a size, from the index; a direct walk when the index can't be read or is empty. */
    @Suppress("DEPRECATION")
    private fun scanFiles(context: Context, filter: HiddenFilter, vols: List<StorageVolume>): List<FileItem> {
        val out = LinkedHashMap<String, FileItem>()
        var anyOk = false
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        for (uri in volumeUris(context)) {
            // "format" 12289 = MTP association (a folder row); retried without it if a device lacks the column.
            val selections = listOf(
                "${MediaStore.Files.FileColumns.SIZE} > 0 AND (format IS NULL OR format != 12289)",
                "${MediaStore.Files.FileColumns.SIZE} > 0",
            )
            for (sel in selections) {
                try {
                    val c: Cursor = context.contentResolver.query(uri, projection, sel, null, null) ?: break
                    c.use { CursorRows.readFiles(it, out, accept = { p -> filter.visiblePath(p) }) }
                    anyOk = true
                    break
                } catch (e: SecurityException) {
                    Diagnostics.log("storage", "no permission to query $uri", e)
                    break
                } catch (e: Exception) {
                    Diagnostics.log("storage", "index query failed on $uri", e)
                }
            }
        }
        val list = out.values.filter { !it.isDir && it.size > 0 }
        if (anyOk && list.isNotEmpty()) return list
        Diagnostics.log("storage", "index empty or unreadable, walking the volumes")
        return StorageWalker.walk(
            roots = vols.map { File(it.path) },
            showHidden = filter.showHidden,
            privateRoots = filter.privateRoots,
            accept = { true },
            maxResults = 400_000,
        ).filter { filter.visiblePath(it.path) }
    }

}
