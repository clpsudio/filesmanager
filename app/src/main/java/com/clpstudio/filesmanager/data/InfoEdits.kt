package com.clpstudio.filesmanager.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure rules behind editing a file from its info sheet: the new name (Rename) and the new date &
 * time (Modified). No Android here, so it runs in JVM tests.
 */
object InfoEdits {
    /** Most Android file systems (ext4, f2fs, FAT/exFAT on SD cards) allow 255 bytes per name. */
    const val MAX_NAME_BYTES = 255

    /** Characters an SD card (FAT/exFAT) or the shared storage layer refuses, plus the path separator. */
    private const val ILLEGAL = "/\\:*?\"<>|"

    /**
     * Why [newName] can't be used for a file now called [oldName], or null when it can.
     * [exists] answers whether a name is already taken in the same folder.
     * The same name (or a change of upper/lower case only) is allowed.
     */
    fun nameError(newName: String, oldName: String, exists: (String) -> Boolean): String? {
        val t = newName.trim()
        if (t.isEmpty()) return "Type a name"
        if (t == "." || t == "..") return "That name can't be used"
        // A leading dot hides the file (and hidden files can't be renamed or unhidden in Files).
        if (t.startsWith(".") && !oldName.startsWith(".")) return "Names can't start with a dot (.) — that would hide the file"
        t.firstOrNull { it.code < 32 || it.code == 127 }?.let { return "Names can't contain hidden control characters" }
        t.firstOrNull { it in ILLEGAL }?.let { return "Names can't contain $it" }
        if (t.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) return "That name is too long (max $MAX_NAME_BYTES characters)"
        if (t == oldName || isCaseOnly(t, oldName)) return null
        if (exists(t)) return "\"$t\" is already in this folder"
        return null
    }

    /** "photo.JPG" -> "Photo.jpg": only upper/lower case changed (needs a two-step rename). */
    fun isCaseOnly(newName: String, oldName: String) = newName != oldName && newName.equals(oldName, ignoreCase = true)

    /**
     * The calendar day of [epochMs] in [zone], as the Material date picker wants it:
     * midnight UTC of that day.
     */
    fun pickerDateOf(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** Hour and minute of [epochMs] in [zone]. */
    fun timeOf(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Int, Int> =
        Instant.ofEpochMilli(epochMs).atZone(zone).let { it.hour to it.minute }

    /**
     * The picked day ([pickerDateUtcMs], midnight UTC as the date picker returns it) at [hour]:[minute]
     * on the device clock ([zone]), as epoch ms. A time skipped by a daylight-saving jump moves forward.
     */
    fun compose(pickerDateUtcMs: Long, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
        require(hour in 0..23 && minute in 0..59) { "bad time $hour:$minute" }
        val day = Instant.ofEpochMilli(pickerDateUtcMs).atZone(ZoneOffset.UTC).toLocalDate()
        return LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
    }

    private val EXIF = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /** EXIF date text ("2026:10:31 14:30:00") for [epochMs] on the device clock. */
    fun exifDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(EXIF)

    /** EXIF offset text ("+05:30") for [epochMs] in [zone]. */
    fun exifOffset(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val s = zone.rules.getOffset(Instant.ofEpochMilli(epochMs)).totalSeconds
        val sign = if (s < 0) "-" else "+"
        val a = kotlin.math.abs(s)
        return "%s%02d:%02d".format(sign, a / 3600, (a % 3600) / 60)
    }

    /** File systems keep dates in whole seconds (FAT: 2 s); a set date within this counts as applied. */
    const val DATE_SLACK_MS = 2_000L

    fun dateApplied(wanted: Long, actual: Long) = kotlin.math.abs(wanted - actual) <= DATE_SLACK_MS
}
