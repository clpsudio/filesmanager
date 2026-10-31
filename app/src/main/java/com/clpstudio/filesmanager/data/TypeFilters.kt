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
        // App / folder chips (only those with files show up in a list).
        val taken = (specific + screenshots).map { it.id }.toSet()
        val folders = appFolderChips().filter { it.id !in taken }
        return listOf(TypeChip(ALL, "All") { true }) + specific + other + screenshots + folders
    }

    private fun pathHas(vararg parts: String): (FileItem) -> Boolean = { item -> parts.any { item.path.contains(it, ignoreCase = true) } }

    /** Chips for the folders apps save into. */
    fun appFolderChips(): List<TypeChip> = listOf(
        TypeChip("CAMERA", "Camera", { it.path.contains("/DCIM/", ignoreCase = true) && !isScreenshot(it) }),
        TypeChip("SCREENSHOTS", "Screenshots", ::isScreenshot),
        TypeChip("APP_WHATSAPP", "WhatsApp", pathHas("/WhatsApp/", "/com.whatsapp/", "/WhatsApp Business/", "/com.whatsapp.w4b/")),
        TypeChip("APP_TELEGRAM", "Telegram", pathHas("/Telegram/", "/org.telegram.messenger/")),
        TypeChip("APP_INSTAGRAM", "Instagram", pathHas("/Instagram/", "/com.instagram.android/")),
        TypeChip("APP_SNAPCHAT", "Snapchat", pathHas("/Snapchat/", "/com.snapchat.android/")),
        TypeChip("APP_FACEBOOK", "Facebook", pathHas("/Facebook/", "/Messenger/", "/com.facebook.")),
        TypeChip("APP_DOWNLOADS", "Downloads", pathHas("/Download/", "/Downloads/")),
        TypeChip("APP_BLUETOOTH", "Bluetooth", pathHas("/Bluetooth/")),
        TypeChip("APP_RECORDINGS", "Recordings", pathHas("/Recordings/", "/Voice Recorder/", "/Call/")),
    )

    /** Settings → Folder filters, applied: "All" first and always there, then the user's order, hidden ones left out. */
    fun customized(category: Category, config: ChipConfig?): List<TypeChip> = ChipConfig.apply(chipsFor(category), config)

    /** Chips that have files, with their counts. "All" and the chosen chip always stay. */
    fun counted(category: Category, items: List<FileItem>, chosen: String, config: ChipConfig? = null): List<Pair<TypeChip, Int>> =
        customized(category, config).map { it to items.count(it.matches) }.filter { (c, n) -> n > 0 || c.id == ALL || c.id == chosen }

    fun apply(category: Category, items: List<FileItem>, chosen: String): List<FileItem> {
        val chip = chipsFor(category).firstOrNull { it.id == chosen } ?: return items
        return if (chip.id == ALL) items else items.filter(chip.matches)
    }
}

/** Date sections for lists sorted by date: Today, Yesterday, This week, This month, Older. */
/** Settings → "Group files by" for date-sorted lists. */
enum class DateGrouping(val label: String) {
    NONE("None"), DAY("Daily"), WEEK("Weekly"), MONTH("Monthly"), QUARTER("Quarterly"), YEAR("Yearly");

    companion object {
        fun parse(v: String?): DateGrouping = entries.firstOrNull { it.name == v } ?: MONTH
    }
}

/**
 * Section headers for date-sorted lists: "31 Oct 2026", "Week of 27 Oct 2026" (weeks start on Monday),
 * "October 2026", "Q4 2026", "2026". English names on purpose (locale-safe, same everywhere in the app).
 */
object DateSections {
    private val MONTHS = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private fun short(m: Int) = MONTHS[m].take(3)

    fun label(modifiedMs: Long, grouping: DateGrouping, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        val c = Calendar.getInstance(zone, java.util.Locale.ROOT).apply { timeInMillis = modifiedMs }
        val y = c.get(Calendar.YEAR)
        val m = c.get(Calendar.MONTH)
        return when (grouping) {
            DateGrouping.NONE -> ""
            DateGrouping.DAY -> "${c.get(Calendar.DAY_OF_MONTH)} ${short(m)} $y"
            DateGrouping.WEEK -> {
                c.firstDayOfWeek = Calendar.MONDAY
                val back = (c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
                c.add(Calendar.DAY_OF_MONTH, -back)
                "Week of ${c.get(Calendar.DAY_OF_MONTH)} ${short(c.get(Calendar.MONTH))} ${c.get(Calendar.YEAR)}"
            }
            DateGrouping.MONTH -> "${MONTHS[m]} $y"
            DateGrouping.QUARTER -> "Q${m / 3 + 1} $y"
            DateGrouping.YEAR -> "$y"
        }
    }

    /** Consecutive groups in list order (the list is already sorted by date). */
    fun group(items: List<FileItem>, grouping: DateGrouping = DateGrouping.MONTH, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): List<Pair<String, List<FileItem>>> {
        if (grouping == DateGrouping.NONE) return listOf("" to items)
        val out = ArrayList<Pair<String, MutableList<FileItem>>>()
        for (item in items) {
            val l = label(item.modified, grouping, zone)
            if (out.isEmpty() || out.last().first != l) out.add(l to mutableListOf())
            out.last().second.add(item)
        }
        return out
    }
}

/** Select all / deselect all and per-group select, as pure functions. */
object SelectionLogic {
    /** "Select all" is a toggle: everything already selected → nothing; otherwise → everything. */
    fun toggleAll(selected: Collection<String>, all: List<String>): List<String> =
        if (all.isNotEmpty() && selected.containsAll(all)) emptyList() else all

    fun allSelected(selected: Collection<String>, all: List<String>): Boolean = all.isNotEmpty() && selected.containsAll(all)

    enum class GroupState { NONE, SOME, ALL }

    fun groupState(selected: Collection<String>, group: List<String>): GroupState {
        val n = group.count { it in selected }
        return when { n == 0 -> GroupState.NONE; n == group.size -> GroupState.ALL; else -> GroupState.SOME }
    }

    /** A group's checkbox: all of it selected → unselect the group; else select the whole group. Order kept. */
    fun toggleGroup(selected: List<String>, group: List<String>): List<String> =
        if (groupState(selected, group) == GroupState.ALL) selected.filter { it !in group.toSet() }
        else selected + group.filter { it !in selected.toSet() }
}

/** One category's chip settings: the order (ids) and the hidden ids. */
data class ChipConfig(val order: List<String> = emptyList(), val hidden: Set<String> = emptySet()) {
    companion object {
        /** "All" first always; then [config]'s order (chips it doesn't know keep their default place after it); hidden ones out. */
        fun apply(chips: List<TypeChip>, config: ChipConfig?): List<TypeChip> {
            if (config == null) return chips
            val all = chips.firstOrNull { it.id == TypeFilters.ALL }
            val rest = chips.filter { it.id != TypeFilters.ALL }
            val rank = config.order.withIndex().associate { it.value to it.index }
            val ordered = rest.withIndex().sortedWith(compareBy({ rank[it.value.id] ?: (config.order.size + it.index) })).map { it.value }
            return listOfNotNull(all) + ordered.filter { it.id !in config.hidden }
        }

        /** "PHOTOS|JPG,PNG|GIF;VIDEOS|..." ⇄ map (ids never contain | , ;). */
        fun encode(map: Map<String, ChipConfig>): String =
            map.entries.joinToString(";") { (k, v) -> k + "|" + v.order.joinToString(",") + "|" + v.hidden.joinToString(",") }

        fun decode(s: String?): Map<String, ChipConfig> {
            if (s.isNullOrBlank()) return emptyMap()
            return s.split(';').mapNotNull { part ->
                val p = part.split('|')
                if (p.size < 3 || p[0].isBlank()) null
                else p[0] to ChipConfig(p[1].split(',').filter { it.isNotBlank() }, p[2].split(',').filter { it.isNotBlank() }.toSet())
            }.toMap()
        }

        /** Move [id] one place up (-1) or down (+1) in [order]. */
        fun move(order: List<String>, id: String, delta: Int): List<String> {
            val i = order.indexOf(id)
            val j = i + delta
            if (i < 0 || j < 0 || j >= order.size) return order
            return order.toMutableList().apply { add(j, removeAt(i)) }
        }
    }
}
