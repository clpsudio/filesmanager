package com.clpstudio.filesmanager.data

import java.util.Calendar

/** A type chip under a category header ("PDF", "PNG", "Screenshots"...). */
data class TypeChip(val id: String, val label: String, val matches: (FileItem) -> Boolean)

object TypeFilters {
    const val ALL = "ALL"
    private val rawExt = setOf("dng", "cr2", "cr3", "nef", "arw", "orf", "rw2", "raf", "srw", "pef")
    private val wordExt = setOf("doc", "docx", "dot", "dotx", "odt", "rtf")
    private val excelExt = setOf("xls", "xlsx", "xlsm", "csv", "ods")
    private val pptExt = setOf("ppt", "pptx", "pps", "ppsx", "odp", "key")
    private val txtExt = setOf("txt", "md", "log", "json", "xml", "ini", "cfg", "conf", "yaml", "yml")

    private fun ext(vararg e: String): (FileItem) -> Boolean = { it.extension in e }
    private fun extIn(set: Set<String>): (FileItem) -> Boolean = { it.extension in set }

    fun isScreenshot(item: FileItem): Boolean =
        item.path.contains("/Screenshots/", ignoreCase = true) || item.name.startsWith("Screenshot", ignoreCase = true)

    /** The chips for a category, "All" first. Unmatched files fall under "Other". */
    fun chipsFor(category: Category): List<TypeChip> {
        val specific: List<TypeChip> = when (category) {
            Category.DOCUMENTS -> listOf(
                TypeChip("PDF", "PDF", ext("pdf")),
                TypeChip("WORD", "Word", extIn(wordExt)),
                TypeChip("EXCEL", "Excel", extIn(excelExt)),
                TypeChip("PPT", "PPT", extIn(pptExt)),
                TypeChip("TXT", "TXT", extIn(txtExt)),
            )
            Category.PHOTOS -> listOf(
                TypeChip("JPG", "JPG", ext("jpg", "jpeg", "jpe")),
                TypeChip("PNG", "PNG", ext("png")),
                TypeChip("GIF", "GIF", ext("gif")),
                TypeChip("WEBP", "WEBP", ext("webp")),
                TypeChip("HEIC", "HEIC", ext("heic", "heif", "avif")),
                TypeChip("RAW", "RAW", extIn(rawExt)),
            )
            Category.MEDIA -> listOf(
                TypeChip("PHOTOS", "Photos", { it.kind == FileKind.IMAGE }),
                TypeChip("VIDEOS", "Videos", { it.kind == FileKind.VIDEO }),
                TypeChip("GIFS", "GIFs", ext("gif")),
                TypeChip("SCREENSHOTS", "Screenshots", ::isScreenshot),
                TypeChip("CAMERA", "Camera", { it.path.contains("/DCIM/", ignoreCase = true) && !isScreenshot(it) }),
            )
            Category.VIDEOS -> listOf(
                TypeChip("MP4", "MP4", ext("mp4", "m4v")),
                TypeChip("MKV", "MKV", ext("mkv")),
            )
            Category.AUDIO -> listOf(
                TypeChip("MP3", "MP3", ext("mp3")),
                TypeChip("M4A", "M4A/AAC", ext("m4a", "aac")),
                TypeChip("WAV", "WAV", ext("wav")),
            )
            Category.RECENT, Category.DOWNLOADS -> listOf(
                TypeChip("PHOTOS", "Photos", { it.kind == FileKind.IMAGE }),
                TypeChip("VIDEOS", "Videos", { it.kind == FileKind.VIDEO }),
                TypeChip("AUDIO", "Audio", { it.kind == FileKind.AUDIO }),
                TypeChip("DOCS", "Docs", { FileTypes.isDocument(it.name) }),
                TypeChip("APK", "APK", { FileTypes.isApk(it.name) }),
                TypeChip("ZIP", "ZIP", { FileTypes.isArchive(it.name) }),
            )
        }
        // Media chips overlap (a GIF is a photo too), so no "Other" there.
        val other = if (category == Category.MEDIA) emptyList() else listOf(TypeChip("OTHER", "Other") { item -> specific.none { it.matches(item) } })
        val screenshots = if (category == Category.PHOTOS) listOf(TypeChip("SCREENSHOTS", "Screenshots", ::isScreenshot)) else emptyList()
        return listOf(TypeChip(ALL, "All") { true }) + specific + other + screenshots
    }

    /** Chips that have files, with their counts. "All" and the chosen chip always stay. */
    fun counted(category: Category, items: List<FileItem>, chosen: String): List<Pair<TypeChip, Int>> =
        chipsFor(category).map { it to items.count(it.matches) }.filter { (c, n) -> n > 0 || c.id == ALL || c.id == chosen }

    fun apply(category: Category, items: List<FileItem>, chosen: String): List<FileItem> {
        val chip = chipsFor(category).firstOrNull { it.id == chosen } ?: return items
        return if (chip.id == ALL) items else items.filter(chip.matches)
    }
}

/** Date sections for lists sorted by date: Today, Yesterday, This week, This month, Older. */
object DateSections {
    fun label(modifiedMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val today = cal.timeInMillis
        val yesterday = today - 24L * 3600 * 1000
        val week = Calendar.getInstance().apply { timeInMillis = today; set(Calendar.DAY_OF_WEEK, firstDayOfWeek) }.timeInMillis
            .let { if (it > today) it - 7L * 24 * 3600 * 1000 else it }
        val month = Calendar.getInstance().apply { timeInMillis = today; set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        return when {
            modifiedMs >= today -> "Today"
            modifiedMs >= yesterday -> "Yesterday"
            modifiedMs >= week -> "This week"
            modifiedMs >= month -> "This month"
            else -> "Older"
        }
    }

    /** Consecutive groups in list order (the list is already sorted by date). */
    fun group(items: List<FileItem>, nowMs: Long = System.currentTimeMillis()): List<Pair<String, List<FileItem>>> {
        val out = ArrayList<Pair<String, MutableList<FileItem>>>()
        for (item in items) {
            val l = label(item.modified, nowMs)
            if (out.isEmpty() || out.last().first != l) out.add(l to mutableListOf())
            out.last().second.add(item)
        }
        return out
    }
}
