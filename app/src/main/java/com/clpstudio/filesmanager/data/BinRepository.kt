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
class BinRepository(private val context: Context, private val dir: File = StoragePaths.binDir(context)) {

    companion object {
        val RETENTION_MS: Long = TimeUnit.DAYS.toMillis(30)
    }

    private fun ensureDir() {
        if (!dir.exists()) dir.mkdirs()
        val noMedia = File(dir, ".nomedia")
        if (!noMedia.exists()) runCatching { noMedia.createNewFile() }
    }

    suspend fun moveToBin(files: List<File>, onEach: (Int) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        ensureDir()
        var moved = 0
        val scan = ArrayList<String>()
        files.forEachIndexed { i, f ->
            if (!f.exists()) return@forEachIndexed
            val id = UUID.randomUUID().toString()
            val data = File(dir, id)
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
                File(dir, "$id.json").writeText(meta.toString())
                moved++
            } else {
                data.deleteRecursively()
            }
            onEach(i + 1)
        }
        FileOps.scan(context, emptyList())
        if (scan.isNotEmpty()) android.media.MediaScannerConnection.scanFile(context, scan.toTypedArray(), null, null)
        moved
    }

    suspend fun list(): List<BinEntry> = withContext(Dispatchers.IO) {
        ensureDir()
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { meta ->
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
                    dataFile = File(dir, id),
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
                File(dir, e.id + ".json").delete()
                scan += target.absolutePath
                n++
            }
        }
        FileOps.scan(context, scan)
        n
    }

    suspend fun deleteForever(entries: List<BinEntry>): Int = withContext(Dispatchers.IO) {
        var n = 0
        for (e in entries) {
            e.dataFile.deleteRecursively()
            File(dir, e.id + ".json").delete()
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
