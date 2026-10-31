package com.clpstudio.filesmanager.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

data class BinEntry(
    val id: String,
    val name: String,
    val originalPath: String,
    val deletedAt: Long,
    val isDir: Boolean,
    val size: Long,
    val dataFile: File,
) {
    fun daysLeft(now: Long = System.currentTimeMillis()): Int {
        val left = BinRepository.RETENTION_MS - (now - deletedAt)
        return if (left <= 0) 0 else ((left + TimeUnit.DAYS.toMillis(1) - 1) / TimeUnit.DAYS.toMillis(1)).toInt()
    }
}

/**
 * The Bin: deleted files are moved (not copied, when on the same volume) into the app's own
 * folder and kept for 30 days, then removed for good by [com.clpstudio.filesmanager.work.BinCleanupWorker].
 */
class BinRepository(
    private val context: Context,
    private val dir: File = StoragePaths.binDir(context),
    /** One Bin per SD card, on the card itself (so deleting a card file never copies it to the phone). */
    private val cardBins: () -> List<CardBin> = { StoragePaths.cardBins(context) },
) {

    companion object {
        val RETENTION_MS: Long = TimeUnit.DAYS.toMillis(30)
    }

    private fun ensureDir(d: File = dir): Boolean {
        if (!d.exists()) d.mkdirs()
        val noMedia = File(d, ".nomedia")
        if (!noMedia.exists()) runCatching { noMedia.createNewFile() }
        return d.isDirectory
    }

    /** Every Bin folder: the phone's, then each mounted card's. */
    private fun allDirs(): List<File> = listOf(dir) + runCatching { cardBins().map { it.dir } }.getOrDefault(emptyList())

    /**
     * Where a deleted file goes: the Bin on its own SD card when it lives on one (a rename, no
     * copy), else the phone's Bin. A card Bin that can't be made falls back to the phone's.
     */
    fun binFor(file: File): File {
        val path = file.absolutePath
        val card = runCatching { cardBins() }.getOrDefault(emptyList()).firstOrNull { VolumeRules.isUnder(path, it.root) }
        return if (card != null && ensureDir(card.dir)) card.dir else dir
    }

    private fun metaOf(e: BinEntry): File = File(e.dataFile.parentFile ?: dir, e.id + ".json")

    suspend fun moveToBin(files: List<File>, onEach: (Int) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        ensureDir()
        var moved = 0
        val scan = ArrayList<String>()
        val gone = ArrayList<String>()
        files.forEachIndexed { i, f ->
            if (!f.exists()) return@forEachIndexed
            val id = UUID.randomUUID().toString()
            val target = binFor(f)
            val data = File(target, id)
            val size = FileOps.sizeOf(f)
            val isDir = f.isDirectory
            // Collect media paths before the move so the index forgets them.
            if (isDir) f.walkTopDown().take(2000).filter { it.isFile }.forEach { scan += it.absolutePath } else scan += f.absolutePath
            val ok = f.renameTo(data) || run {
                runCatching { f.copyRecursively(data, overwrite = true) }.isSuccess && f.deleteRecursively()
            }
            if (ok) {
                val meta = JSONObject()
                    .put("name", f.name)
                    .put("path", f.absolutePath)
                    .put("deletedAt", System.currentTimeMillis())
                    .put("isDir", isDir)
                    .put("size", size)
                File(target, "$id.json").writeText(meta.toString())
                moved++
                gone += f.absolutePath
            } else {
                data.deleteRecursively()
            }
            onEach(i + 1)
        }
        // Every list drops them now; the media index forgets their rows (folder: every file inside).
        FileEvents.removed(gone)
        if (gone.isNotEmpty()) FileOps.scan(context, gone + scan.filter { p -> !File(p).exists() })
        moved
    }

    suspend fun list(): List<BinEntry> = withContext(Dispatchers.IO) {
        ensureDir()
        allDirs().distinct().flatMap { d -> d.listFiles { f -> f.name.endsWith(".json") }.orEmpty().toList() }.mapNotNull { meta ->
            runCatching {
                val j = JSONObject(meta.readText())
                val id = meta.name.removeSuffix(".json")
                BinEntry(
                    id = id,
                    name = j.getString("name"),
                    originalPath = j.getString("path"),
                    deletedAt = j.getLong("deletedAt"),
                    isDir = j.optBoolean("isDir"),
                    size = j.optLong("size"),
                    dataFile = File(meta.parentFile, id),
                )
            }.getOrNull()
        }.filter { it.dataFile.exists() }.sortedByDescending { it.deletedAt }
    }

    suspend fun restore(entries: List<BinEntry>): Int = withContext(Dispatchers.IO) {
        var n = 0
        val scan = ArrayList<String>()
        for (e in entries) {
            val original = File(e.originalPath)
            val parent = original.parentFile ?: continue
            parent.mkdirs()
            val name = uniqueName(original.name, e.isDir) { File(parent, it).exists() }
            val target = File(parent, name)
            val ok = e.dataFile.renameTo(target) || run {
                runCatching { e.dataFile.copyRecursively(target, overwrite = false) }.isSuccess && e.dataFile.deleteRecursively()
            }
            if (ok) {
                metaOf(e).delete()
                scan += target.absolutePath
                n++
            }
        }
        FileEvents.added(scan)
        FileOps.scan(context, scan)
        n
    }

    suspend fun deleteForever(entries: List<BinEntry>): Int = withContext(Dispatchers.IO) {
        var n = 0
        for (e in entries) {
            e.dataFile.deleteRecursively()
            metaOf(e).delete()
            n++
        }
        n
    }

    suspend fun empty(): Int = deleteForever(list())

    /** Removes everything older than 30 days. Returns how many went. */
    suspend fun purgeExpired(now: Long = System.currentTimeMillis()): Int =
        deleteForever(list().filter { now - it.deletedAt >= RETENTION_MS })

    suspend fun totalSize(): Long = list().sumOf { it.size }
}

/** Items in the Bin that share the same number of days before deletion. */
data class DaysGroup<T>(val daysLeft: Int, val items: List<T>) {
    val label: String get() = daysLeftLabel(daysLeft)
}

/** "30 days until deletion", "1 day until deletion", "Deleting today". */
fun daysLeftLabel(days: Int): String = when {
    days <= 0 -> "Deleting today"
    days == 1 -> "1 day until deletion"
    else -> "$days days until deletion"
}

/** Groups by days left, most days first; items keep their order inside a group. */
fun <T> groupByDaysLeft(items: List<T>, daysLeft: (T) -> Int): List<DaysGroup<T>> =
    items.groupBy { daysLeft(it).coerceAtLeast(0) }
        .entries.sortedByDescending { it.key }
        .map { DaysGroup(it.key, it.value) }

/** How many entries a binned folder holds (its direct children), or -1 for a file. */
fun BinEntry.itemCount(): Int = if (isDir) dataFile.list()?.size ?: 0 else -1

