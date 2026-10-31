package com.clpstudio.filesmanager.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Where a file lives, as File info and the per-category chip say it. */
enum class StorageLocation(val label: String) { PHONE("Device"), SD_CARD("SD card") }

/** Per-category "All / Phone / SD card" chip (shown only while a card is mounted). */
enum class LocationFilter(val label: String) { ALL("All"), PHONE("Device"), SD_CARD("SD card") }

/** A storage box on Home ("Device", "SD card"): used / total bytes and the root it opens. */
data class StorageBox(val path: String, val label: String, val used: Long, val total: Long, val removable: Boolean)

/** One mounted storage volume as the system reports it. */
data class MountedVolume(
    val path: String,
    val description: String?,
    val removable: Boolean,
    val primary: Boolean,
) {
    /** "SD card" for a card (Samsung reports "SD card"), the system's own name for a USB drive. */
    val label: String
        get() = when {
            primary || !removable -> "Internal storage"
            description != null && description.contains("USB", ignoreCase = true) -> description
            else -> "SD card"
        }
}

/**
 * Pure rules about volumes and locations (unit-tested; no Android calls).
 */
object VolumeRules {
    private val mountedStates = setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)

    fun isMounted(state: String?): Boolean = state != null && state in mountedStates

    fun isUnder(path: String, root: String): Boolean {
        val r = root.trimEnd('/')
        if (r.isEmpty()) return false
        return path == r || path.startsWith("$r/")
    }

    /** The removable root [path] sits on, or null when it's on the phone. */
    fun removableRootOf(path: String, removableRoots: List<String>): String? =
        removableRoots.firstOrNull { isUnder(path, it) }

    fun locationOf(path: String, removableRoots: List<String>): StorageLocation =
        if (removableRootOf(path, removableRoots) != null) StorageLocation.SD_CARD else StorageLocation.PHONE

    /**
     * Merges what StorageManager reported with the volumes found through the app's own
     * external folders (some Samsung builds hide a card from one of the two). Primary first,
     * no duplicates, no unmounted volume, no nested duplicates of the primary.
     */
    fun merge(fromStorageManager: List<MountedVolume>, fromAppDirs: List<MountedVolume>, primaryPath: String): List<MountedVolume> {
        val out = LinkedHashMap<String, MountedVolume>()
        val primary = fromStorageManager.firstOrNull { it.primary }
            ?: MountedVolume(primaryPath, null, removable = false, primary = true)
        out[primary.path.trimEnd('/')] = primary.copy(path = primary.path.trimEnd('/'))
        for (v in fromStorageManager + fromAppDirs) {
            val p = v.path.trimEnd('/')
            if (p.isEmpty() || p in out) continue
            if (v.primary) continue
            out[p] = v.copy(path = p)
        }
        return out.values.toList()
    }

    /** Volumes the app lists: the phone always, cards only while "Show SD card" is on. */
    fun visible(all: List<MountedVolume>, showSdCard: Boolean): List<MountedVolume> =
        if (showSdCard) all else all.filter { !it.removable }

    /** Roots whose files must not appear anywhere (the card, when "Show SD card" is off). */
    fun hiddenRoots(all: List<MountedVolume>, showSdCard: Boolean): List<String> =
        if (showSdCard) emptyList() else all.filter { it.removable }.map { it.path }

    /** The per-category chip: only shown while a card is mounted (and visible). */
    fun showLocationChips(all: List<MountedVolume>, showSdCard: Boolean): Boolean =
        showSdCard && all.any { it.removable }

    fun <T> filterByLocation(items: List<T>, filter: LocationFilter, removableRoots: List<String>, path: (T) -> String): List<T> =
        when (filter) {
            LocationFilter.ALL -> items
            LocationFilter.PHONE -> items.filter { locationOf(path(it), removableRoots) == StorageLocation.PHONE }
            LocationFilter.SD_CARD -> items.filter { locationOf(path(it), removableRoots) == StorageLocation.SD_CARD }
        }
}

/**
 * Copy / move between the phone and an SD card: the checks before it starts and the plain
 * words for a failure (pure, unit-tested).
 */
object TransferRules {
    /** The volume root holding [path]: the longest matching root, or "" if none. */
    fun volumeRootOf(path: String, roots: List<String>): String =
        roots.filter { VolumeRules.isUnder(path, it) }.maxByOrNull { it.length } ?: ""

    /** "phone storage" / "SD card": how a message names the place. */
    fun placeName(location: StorageLocation): String = if (location == StorageLocation.SD_CARD) "SD card" else "device storage"

    /**
     * Why the transfer can't start, or null. [sameVolumeMove] = a move inside one volume (a
     * rename: needs no free space).
     */
    fun problem(needBytes: Long, freeBytes: Long?, canWrite: Boolean, target: StorageLocation, sameVolumeMove: Boolean): String? {
        val place = placeName(target)
        if (!canWrite) return "Can't write to the $place. It may be read-only or locked."
        if (!sameVolumeMove && freeBytes != null && needBytes > freeBytes) {
            return "Not enough space on the $place: needs ${sizeText(needBytes)}, ${sizeText(freeBytes)} free"
        }
        return null
    }

    /** Plain words for an I/O failure in the middle of a copy. */
    fun friendlyError(e: Throwable, target: StorageLocation): String {
        val place = placeName(target)
        val m = (e.message ?: "") + " " + (e.cause?.message ?: "")
        return when {
            m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true) -> "Not enough space on the $place"
            m.contains("EROFS") || m.contains("Read-only file system", ignoreCase = true) -> "The $place is read-only"
            m.contains("EACCES") || m.contains("Permission denied", ignoreCase = true) -> "No permission to write to the $place"
            m.contains("EIO") || m.contains("ENODEV") || m.contains("No such device", ignoreCase = true) ||
                m.contains("ENOENT") -> if (target == StorageLocation.SD_CARD) "The SD card was removed or can't be read" else "A file couldn't be read or written"
            else -> e.message?.takeIf { it.isNotBlank() } ?: "Copy failed"
        }
    }

    fun sizeText(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (i == 0) "$bytes B" else String.format(java.util.Locale.US, "%.1f %s", v, units[i])
    }
}

/**
 * Mounted storage volumes, kept fresh by the system's media broadcasts (registered at runtime,
 * no permission). Reads go through [all]; listeners hear every mount / unmount / eject.
 */
object SdCards {
    @Volatile private var cached: List<MountedVolume>? = null
    /** Tests set this to pretend a card is (or isn't) in the slot. */
    @Volatile var override: List<MountedVolume>? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var registered = false

    fun all(context: Context): List<MountedVolume> =
        override ?: cached ?: detect(context).also { cached = it }

    fun removableRoots(context: Context): List<String> = all(context).filter { it.removable }.map { it.path }

    fun hasCard(context: Context): Boolean = all(context).any { it.removable }

    fun locationOf(context: Context, path: String): StorageLocation =
        VolumeRules.locationOf(path, removableRoots(context))

    fun addListener(l: () -> Unit) { listeners += l }

    fun invalidate() { cached = null }

    /** Starts listening for cards going in / out. Safe to call more than once. */
    fun install(context: Context) {
        if (registered) return
        registered = true
        val app = context.applicationContext
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        val handler = Handler(Looper.getMainLooper())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                Diagnostics.log("sd", "volume event ${intent?.action} ${intent?.data}")
                changed()
                // The mount point can need a moment before its folders are readable.
                handler.postDelayed({ changed() }, 1500)
            }
        }
        runCatching { ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED) }
            .onFailure { Diagnostics.log("sd", "couldn't listen for SD card events", it) }
    }

    /** A card went in or out, or the setting changed: re-read volumes and tell every listener. */
    fun changed() {
        cached = null
        listeners.forEach { runCatching { it() } }
    }

    fun detect(context: Context): List<MountedVolume> {
        val primaryPath = runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrDefault("/storage/emulated/0")
        val fromSm = runCatching { fromStorageManager(context) }.getOrDefault(emptyList())
        val fromDirs = runCatching { fromAppDirs(context, primaryPath) }.getOrDefault(emptyList())
        return VolumeRules.merge(fromSm, fromDirs, primaryPath)
    }

    private fun fromStorageManager(context: Context): List<MountedVolume> {
        val sm = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return sm.storageVolumes.mapNotNull { v ->
            if (!VolumeRules.isMounted(v.state)) return@mapNotNull null
            val dir = volumePath(v) ?: return@mapNotNull null
            MountedVolume(dir, runCatching { v.getDescription(context) }.getOrNull(), v.isRemovable, v.isPrimary)
        }
    }

    /** StorageVolume.directory on Android 11+, the hidden getPath() before that. */
    private fun volumePath(v: android.os.storage.StorageVolume): String? {
        if (Build.VERSION.SDK_INT >= 30) return v.directory?.absolutePath
        return runCatching { v.javaClass.getMethod("getPath").invoke(v) as? String }.getOrNull()
            ?: runCatching { (v.javaClass.getMethod("getPathFile").invoke(v) as? File)?.absolutePath }.getOrNull()
    }

    private fun fromAppDirs(context: Context, primaryPath: String): List<MountedVolume> =
        context.getExternalFilesDirs(null).filterNotNull().mapNotNull { appDir ->
            val p = appDir.absolutePath
            val idx = p.indexOf("/Android/data/")
            if (idx <= 0) return@mapNotNull null
            val root = p.substring(0, idx)
            if (root == primaryPath) return@mapNotNull null
            val state = runCatching { Environment.getExternalStorageState(appDir) }.getOrNull()
            if (state != null && !VolumeRules.isMounted(state)) return@mapNotNull null
            val removable = runCatching { Environment.isExternalStorageRemovable(appDir) }.getOrDefault(true)
            MountedVolume(root, null, removable = removable, primary = false)
        }

    /** Used / total bytes of a root (StatFs), or null if it can't be read. */
    fun usage(path: String): Pair<Long, Long>? = runCatching {
        val s = StatFs(path)
        (s.totalBytes - s.availableBytes).coerceAtLeast(0) to s.totalBytes
    }.getOrNull()
}
