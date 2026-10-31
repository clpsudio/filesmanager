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
import kotlinx.coroutines.Job
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
)

/**
 * Computes "Manage storage" in the background, entirely on the device: one MediaStore Files-table
 * query per volume (a direct walk if that fails), sorted into kinds by extension. Nothing here uses
 * the system Files app or the Settings storage screen. Keeps the last good result while a refresh
 * runs (shown as "…" before the first one), drops deleted files at once and recomputes after a
 * short pause when files change.
 */
object ManageStorageRepository {
    private val scope get() = CategoryRepository.scope
    private var debounce: Job? = null

    val report: KeepLastGoodLoader<ManageStorageData> by lazy {
        // Duplicates follow each finished report (they hash the report's same-size files).
        KeepLastGoodLoader(CategoryRepository.scope, onLoaded = { duplicates.refresh() }) { compute(Graph.app) }
    }

    /** Duplicate sets per volume path (the slow part: reads 128 KB of each same-size file). */
    val duplicates: KeepLastGoodLoader<Map<String, List<DuplicateSet>>> by lazy {
        KeepLastGoodLoader(CategoryRepository.scope) {
            val data = report.state.value.value ?: report.await() ?: return@KeepLastGoodLoader emptyMap()
            data.volumes.associate { v ->
                v.volume.path to StorageMath.groupDuplicates(FileEvents.filter(v.dupCandidates), hash = { StorageMath.quickHash(File(it.path)) })
            }
        }
    }

    init {
        // Deleted / moved files leave every number and list at once; the screen recomputes after.
        FileEvents.addListener { change -> if (change.removed.isNotEmpty()) prune() }
    }

    fun state(): StateFlow<Loadable<ManageStorageData>> = report.state

    fun ensureLoaded() = report.ensureLoaded()

    fun refresh() = report.refresh()

    /** Recompute shortly after the last change (a burst of deletes = one recompute). */
    fun scheduleRefresh(delayMs: Long = 1_500) {
        debounce?.cancel()
        debounce = scope.launch { delay(delayMs); refresh() }
    }

    fun prune() {
        report.prune { d -> d.copy(volumes = d.volumes.map { it.pruned(FileEvents::filter) }) }
        duplicates.prune { m ->
            m.mapValues { (_, sets) -> sets.map { DuplicateSet(FileEvents.filter(it.files)) }.filter { it.files.size >= 2 } }
        }
    }

    private suspend fun compute(context: Context): ManageStorageData {
        val vols = StoragePaths.volumes(context)
        val filter = Graph.hiddenFilter().withoutNoMedia()
        val files = FileEvents.filter(scanFiles(context, filter, vols))
        val roots = vols.map { it.path }
        val perVol = files.groupBy { StorageMath.volumeOf(it.path, roots) }
        val access = hasUsageAccess(context)
        val bin = runCatching { Graph.bin.totalSize() }.getOrNull()
        val apps = if (access) runCatching { appsBytes(context, bin ?: 0L) }.getOrNull() else null
        val unused = if (access) runCatching { unusedAppCount(context) }.getOrNull() else null
        val now = System.currentTimeMillis()
        val reports = vols.mapIndexed { i, v ->
            StorageMath.report(v, i == 0, perVol[v.path].orEmpty(), apps, bin, now)
        }
        return ManageStorageData(reports, access, unused)
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

    /** App code + data of every installed package on internal storage (our own Bin taken out). */
    private fun appsBytes(context: Context, binBytes: Long): Long? {
        val ssm = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val pm = context.packageManager
        val user = Process.myUserHandle()
        var sum = 0L
        var ok = 0
        for (app in pm.getInstalledApplications(0).take(1_500)) {
            val st = runCatching { ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, app.packageName, user) }.getOrNull() ?: continue
            var b = st.appBytes + st.dataBytes
            if (app.packageName == context.packageName) b = (b - binBytes).coerceAtLeast(0)
            sum += b
            ok++
        }
        return if (ok == 0) null else sum
    }

    /** Apps the user installed (and could open) that weren't used in the past 30 days. */
    private fun unusedAppCount(context: Context): Int? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val since = now - 30 * StorageMath.DAY
        val stats = usm.queryAndAggregateUsageStats(since, now)
        val pm = context.packageManager
        return pm.getInstalledApplications(0).count { app ->
            if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0 || app.packageName == context.packageName) return@count false
            if (pm.getLaunchIntentForPackage(app.packageName) == null) return@count false
            val installed = runCatching { pm.getPackageInfo(app.packageName, 0).firstInstallTime }.getOrDefault(0L)
            if (installed > since) return@count false
            val last = stats[app.packageName]?.lastTimeUsed ?: 0L
            last < since
        }
    }
}
