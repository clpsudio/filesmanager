package com.clpstudio.filesmanager.data

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** The rows of "Manage storage", in the order the 2-column list shows them (left column, then right). */
enum class StorageKind(val label: String) {
    VIDEOS("Videos"),
    APKS("Installation files"),
    DOCUMENTS("Documents"),
    APPS("Apps"),
    OTHER("Other files"),
    ARCHIVES("Compressed files"),
    AUDIO("Audio files"),
    IMAGES("Images"),
    SYSTEM("System"),
    BIN("Bin");

    companion object {
        /** Kinds that come straight from the files on the volume (each has a file list). */
        val fileKinds = listOf(VIDEOS, IMAGES, AUDIO, DOCUMENTS, APKS, ARCHIVES)
        val leftColumn = listOf(VIDEOS, APKS, DOCUMENTS, APPS, OTHER)
        val rightColumn = listOf(ARCHIVES, AUDIO, IMAGES, SYSTEM, BIN)
    }
}

/** The suggestion cards, with the honest rule each one uses (Android records "changed", not "opened"). */
enum class SuggestionType(val chip: String) {
    OLD_DOWNLOADS("Old downloads"),
    OLD_SCREENSHOTS("Old screenshots"),
    APKS("Installation files"),
    OLD_LARGE_VIDEOS("Old large videos"),
    OLD_AUDIO("Old audio"),
}

data class Suggestion(val type: SuggestionType, val files: List<FileItem>) {
    val size: Long get() = files.sumOf { it.size }
}

/** A set of identical files (same size and same quick hash); [files] oldest first. */
data class DuplicateSet(val files: List<FileItem>) {
    val size: Long get() = files.firstOrNull()?.size ?: 0L
    /** What deleting every copy but one frees. */
    val reclaimable: Long get() = size * (files.size - 1).coerceAtLeast(0)
}

/** One volume's numbers. A null size = not known (shown as "—"), never as 0. */
data class StorageUsage(
    val total: Long,
    val free: Long,
    val sizes: Map<StorageKind, Long?>,
) {
    val used: Long get() = (total - free).coerceAtLeast(0)
    val percentUsed: Int get() = StorageMath.percentUsed(total, free)
}

/** Everything the screen shows for one volume, derived from its file list by [StorageMath.report]. */
data class VolumeReport(
    val volume: StorageVolume,
    val isPrimary: Boolean,
    val byKind: Map<StorageKind, List<FileItem>>,
    /** Bytes of indexed files that fit no category (kept as a number: the files themselves aren't kept). */
    val otherBytes: Long,
    val appsBytes: Long?,
    val binBytes: Long?,
    val large: List<FileItem>,
    val suggestions: List<Suggestion>,
    /** Files that share their size with another one (only these can be duplicates), for the hash pass. */
    val dupCandidates: List<FileItem>,
) {
    val breakdown: StorageUsage get() = StorageMath.breakdown(volume.total, volume.free, byKind.mapValues { e -> e.value.sumOf { it.size } }, otherBytes, appsBytes, binBytes, isPrimary)
    val largeSize: Long get() = large.sumOf { it.size }

    /** The same report without files that were deleted / moved since (sizes recomputed). */
    fun pruned(keep: (List<FileItem>) -> List<FileItem>): VolumeReport {
        val nb = byKind.mapValues { keep(it.value) }
        val changed = nb.any { it.value.size != byKind[it.key]?.size }
        val nl = keep(large)
        val ns = suggestions.map { Suggestion(it.type, keep(it.files)) }.filter { it.size > 0 }
        if (!changed && nl.size == large.size && ns.sumOf { it.files.size } == suggestions.sumOf { it.files.size }) return this
        return copy(byKind = nb, large = nl, suggestions = ns, dupCandidates = keep(dupCandidates))
    }
}

object StorageMath {
    const val DAY = 24L * 60 * 60 * 1000
    const val LARGE_FILE = 25L * 1024 * 1024
    const val LARGE_OLD_VIDEO = 50L * 1024 * 1024
    const val DUP_MIN_SIZE = 4L * 1024
    const val QUICK_HASH_CHUNK = 64 * 1024

    fun percentUsed(total: Long, free: Long): Int {
        if (total <= 0) return 0
        // Truncated like the system does: 47.0 of 128 GB reads "36% used".
        val used = (total - free).coerceIn(0, total)
        return (used * 100 / total).toInt().coerceIn(0, 100)
    }

    /** Which list a file belongs to; null = "Other files". */
    fun kindOf(name: String): StorageKind? {
        val ext = FileTypes.extension(name)
        return when {
            ext in FileTypes.videoExt -> StorageKind.VIDEOS
            ext in FileTypes.imageExt || ext in FileTypes.rawExt -> StorageKind.IMAGES
            ext in FileTypes.audioExt -> StorageKind.AUDIO
            ext in FileTypes.apkExt -> StorageKind.APKS
            ext in FileTypes.archiveExt -> StorageKind.ARCHIVES
            ext in FileTypes.documentExt -> StorageKind.DOCUMENTS
            else -> null
        }
    }

    /** Splits [files] by kind; returns the lists (newest first) and the bytes of everything else. */
    fun bucket(files: List<FileItem>): Pair<Map<StorageKind, List<FileItem>>, Long> {
        val m = StorageKind.fileKinds.associateWith { ArrayList<FileItem>() }
        var other = 0L
        for (f in files) {
            if (f.isDir || f.size <= 0) continue
            val k = kindOf(f.name)
            if (k == null) other += f.size else m.getValue(k).add(f)
        }
        return m.mapValues { e -> e.value.sortedByDescending { it.modified } } to other
    }

    /**
     * The numbers per row. System = used − files − apps (if known) − bin, never below 0. On a volume
     * that isn't the primary one there are no apps and no system share: the rest is "Other files".
     */
    fun breakdown(
        total: Long,
        free: Long,
        kindBytes: Map<StorageKind, Long>,
        otherBytes: Long,
        appsBytes: Long?,
        binBytes: Long?,
        isPrimary: Boolean = true,
    ): StorageUsage {
        val used = (total - free).coerceAtLeast(0)
        val filesBytes = kindBytes.values.sum() + otherBytes
        val sizes = LinkedHashMap<StorageKind, Long?>()
        StorageKind.fileKinds.forEach { sizes[it] = kindBytes[it] ?: 0L }
        if (isPrimary) {
            sizes[StorageKind.APPS] = appsBytes
            sizes[StorageKind.BIN] = binBytes
            sizes[StorageKind.OTHER] = otherBytes
            sizes[StorageKind.SYSTEM] = systemBytes(used, filesBytes, appsBytes, binBytes)
        } else {
            sizes[StorageKind.OTHER] = otherBytes + (used - filesBytes).coerceAtLeast(0)
        }
        return StorageUsage(total, free, sizes)
    }

    fun systemBytes(used: Long, filesBytes: Long, appsBytes: Long?, binBytes: Long?): Long =
        (used - filesBytes - (appsBytes ?: 0L) - (binBytes ?: 0L)).coerceAtLeast(0)

    /** Files of at least [min] bytes, biggest first. */
    fun largeFiles(files: List<FileItem>, min: Long = LARGE_FILE): List<FileItem> =
        files.filter { !it.isDir && it.size >= min }.sortedByDescending { it.size }

    /** Chips on the Large files screen. */
    enum class LargeGroup(val label: String) { ALL("All"), VIDEOS("Videos"), AUDIO("Audio"), IMAGES("Images"), DOCUMENTS("Documents"), OTHER("Other") }

    fun largeGroup(f: FileItem): LargeGroup = when (kindOf(f.name)) {
        StorageKind.VIDEOS -> LargeGroup.VIDEOS
        StorageKind.AUDIO -> LargeGroup.AUDIO
        StorageKind.IMAGES -> LargeGroup.IMAGES
        StorageKind.DOCUMENTS -> LargeGroup.DOCUMENTS
        else -> LargeGroup.OTHER
    }

    fun filterLarge(files: List<FileItem>, g: LargeGroup): List<FileItem> =
        if (g == LargeGroup.ALL) files else files.filter { largeGroup(it) == g }

    private fun lower(p: String) = p.lowercase()

    fun isInDownloads(path: String, volumeRoot: String): Boolean =
        lower(path).startsWith(lower(volumeRoot.trimEnd('/')) + "/download/")

    fun isScreenshot(path: String): Boolean {
        val p = lower(path)
        return p.contains("/screenshots/")
    }

    /**
     * The suggestion cards, by rule ([now] in ms). Only cards with something in them are returned.
     * Dates are the file's last change: Android keeps no "last opened" date.
     */
    fun suggestions(files: List<FileItem>, volumeRoot: String, now: Long): List<Suggestion> {
        val downloads = ArrayList<FileItem>()
        val shots = ArrayList<FileItem>()
        val apks = ArrayList<FileItem>()
        val videos = ArrayList<FileItem>()
        val audio = ArrayList<FileItem>()
        for (f in files) {
            if (f.isDir || f.size <= 0) continue
            val age = now - f.modified
            val kind = kindOf(f.name)
            if (isInDownloads(f.path, volumeRoot) && age > 90 * DAY) downloads += f
            if (kind == StorageKind.IMAGES && isScreenshot(f.path) && age > 30 * DAY) shots += f
            if (kind == StorageKind.APKS) apks += f
            if (kind == StorageKind.VIDEOS && f.size >= LARGE_OLD_VIDEO && age > 180 * DAY) videos += f
            if (kind == StorageKind.AUDIO && age > 365 * DAY) audio += f
        }
        return listOf(
            Suggestion(SuggestionType.OLD_DOWNLOADS, downloads.sortedByDescending { it.size }),
            Suggestion(SuggestionType.OLD_SCREENSHOTS, shots.sortedBy { it.modified }),
            Suggestion(SuggestionType.APKS, apks.sortedByDescending { it.size }),
            Suggestion(SuggestionType.OLD_LARGE_VIDEOS, videos.sortedByDescending { it.size }),
            Suggestion(SuggestionType.OLD_AUDIO, audio.sortedByDescending { it.size }),
        ).filter { it.size > 0 }
    }

    /** The sentence on a suggestion card. */
    fun suggestionText(s: Suggestion, sizeText: String): String = when (s.type) {
        SuggestionType.OLD_DOWNLOADS -> "Clear $sizeText of downloaded files older than 90 days."
        SuggestionType.OLD_SCREENSHOTS -> "Delete $sizeText of screenshots older than 30 days."
        SuggestionType.APKS -> "Review installation files (APKs). $sizeText"
        SuggestionType.OLD_LARGE_VIDEOS -> "Old large videos: $sizeText of videos over 50 MB, not changed in 6 months."
        SuggestionType.OLD_AUDIO -> "Old audio: $sizeText of audio files not changed in a year."
    }

    fun suggestionAction(t: SuggestionType): String = if (t == SuggestionType.OLD_DOWNLOADS) "Select files" else "Review"

    /** One line under the cleanup title. */
    fun suggestionExplain(t: SuggestionType): String = when (t) {
        SuggestionType.OLD_DOWNLOADS -> "Files in Download that haven't changed in over 90 days."
        SuggestionType.OLD_SCREENSHOTS -> "Screenshots older than 30 days."
        SuggestionType.APKS -> "App installers. Once an app is installed you usually don't need its APK."
        SuggestionType.OLD_LARGE_VIDEOS -> "Videos over 50 MB that haven't changed in 6 months."
        SuggestionType.OLD_AUDIO -> "Audio files that haven't changed in over a year."
    }

    /** Files (≥ [minSize]) whose size is shared with at least one other file: the only possible duplicates. */
    fun sameSizeCandidates(files: List<FileItem>, minSize: Long = DUP_MIN_SIZE, max: Int = 6_000): List<FileItem> {
        val bySize = HashMap<Long, MutableList<FileItem>>()
        for (f in files) if (!f.isDir && f.size >= minSize) bySize.getOrPut(f.size) { ArrayList(2) }.add(f)
        // Biggest sizes first, so a bound cuts the least useful (small) ones.
        val out = ArrayList<FileItem>()
        for ((_, l) in bySize.entries.sortedByDescending { it.key }) {
            if (l.size < 2) continue
            if (out.size + l.size > max) break
            out += l
        }
        return out
    }

    /**
     * Groups [files] into duplicate sets: same size, then same [hash] (null = unreadable, skipped).
     * Files under [minSize] are ignored. Sets come biggest-reclaimable first, each set oldest first
     * (the oldest is taken as the original to keep).
     */
    fun groupDuplicates(
        files: List<FileItem>,
        hash: (FileItem) -> String?,
        minSize: Long = DUP_MIN_SIZE,
        maxHashed: Int = 6_000,
    ): List<DuplicateSet> {
        var hashed = 0
        val sets = ArrayList<DuplicateSet>()
        val bySize = files.filter { !it.isDir && it.size >= minSize }.distinctBy { it.path }.groupBy { it.size }
        for ((_, group) in bySize.entries.sortedByDescending { it.key }) {
            if (group.size < 2) continue
            if (hashed + group.size > maxHashed) break
            hashed += group.size
            val byHash = HashMap<String, MutableList<FileItem>>()
            for (f in group) { val h = hash(f) ?: continue; byHash.getOrPut(h) { ArrayList() }.add(f) }
            for (l in byHash.values) if (l.size >= 2) sets += DuplicateSet(l.sortedWith(compareBy<FileItem> { it.modified }.thenBy { it.path }))
        }
        return sets.sortedByDescending { it.reclaimable }
    }

    /** "All" on the duplicates screen: every copy except the first (oldest) of each set. */
    fun allButOne(sets: List<DuplicateSet>): List<String> = sets.flatMap { s -> s.files.drop(1).map { it.path } }

    /** First 64 KB + last 64 KB + size, SHA-1. Null when the file can't be read. */
    fun quickHash(file: File, chunk: Int = QUICK_HASH_CHUNK): String? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val md = MessageDigest.getInstance("SHA-1")
            val buf = ByteArray(chunk)
            val first = raf.read(buf, 0, minOf(chunk.toLong(), len).toInt().coerceAtLeast(0))
            if (first > 0) md.update(buf, 0, first)
            if (len > chunk) {
                val start = maxOf(chunk.toLong(), len - chunk)
                raf.seek(start)
                val n = raf.read(buf, 0, (len - start).toInt())
                if (n > 0) md.update(buf, 0, n)
            }
            md.update(len.toString().toByteArray())
            md.digest().joinToString("") { "%02x".format(it) }
        }
    }.getOrNull()

    /** Builds the report for one volume from its files. */
    fun report(
        volume: StorageVolume,
        isPrimary: Boolean,
        files: List<FileItem>,
        appsBytes: Long?,
        binBytes: Long?,
        now: Long,
    ): VolumeReport {
        val (byKind, other) = bucket(files)
        return VolumeReport(
            volume = volume,
            isPrimary = isPrimary,
            byKind = byKind,
            otherBytes = other,
            appsBytes = if (isPrimary) appsBytes else null,
            binBytes = if (isPrimary) binBytes else null,
            large = largeFiles(files),
            suggestions = suggestions(files, volume.path, now),
            dupCandidates = sameSizeCandidates(files),
        )
    }

    /** Which volume a path is on (the longest matching root). */
    fun volumeOf(path: String, roots: List<String>): String? =
        roots.filter { path == it || path.startsWith(it.trimEnd('/') + "/") }.maxByOrNull { it.length }

    /** "132 videos (6.55 GB)" style list title. */
    fun listTitle(kind: StorageKind, count: Int, sizeText: String): String {
        val noun = when (kind) {
            StorageKind.VIDEOS -> if (count == 1) "video" else "videos"
            StorageKind.IMAGES -> if (count == 1) "image" else "images"
            StorageKind.AUDIO -> if (count == 1) "audio file" else "audio files"
            StorageKind.DOCUMENTS -> if (count == 1) "document" else "documents"
            StorageKind.ARCHIVES -> if (count == 1) "compressed file" else "compressed files"
            StorageKind.APKS -> if (count == 1) "installation file" else "installation files"
            else -> if (count == 1) "item" else "items"
        }
        return "$count $noun ($sizeText)"
    }

    fun itemsTitle(count: Int, sizeText: String): String = "$count item" + (if (count == 1) "" else "s") + " ($sizeText)"
}
