package com.clpstudio.filesmanager.data

import java.io.File

/**
 * Item 38: the app's local log file (Settings > Logs), kept in app-private storage. Two files of at most
 * [maxBytes]/2 each: when "app.log" is full it becomes "app.1.log" (the older one is dropped), so the log
 * never grows past about [maxBytes]. Lines carry their own timestamps. Never holds file contents, vault
 * data or PINs (callers log names/paths and error messages only).
 */
class RollingLog(private val dir: File, private val maxBytes: Long = 2L * 1024 * 1024) {
    private val current get() = File(dir, "app.log")
    private val older get() = File(dir, "app.1.log")

    @Synchronized
    fun append(line: String) {
        runCatching {
            dir.mkdirs()
            val bytes = (line + "\n").toByteArray()
            if (current.exists() && current.length() + bytes.size > maxBytes / 2) {
                older.delete()
                current.renameTo(older)
            }
            current.appendBytes(bytes)
        }
    }

    /** Everything kept, oldest first. */
    @Synchronized
    fun readAll(): String = buildString {
        if (older.exists()) append(runCatching { older.readText() }.getOrDefault(""))
        if (current.exists()) append(runCatching { current.readText() }.getOrDefault(""))
    }

    @Synchronized
    fun sizeBytes(): Long = (if (older.exists()) older.length() else 0L) + (if (current.exists()) current.length() else 0L)

    /** Deletes every log file. */
    @Synchronized
    fun deleteAll() {
        older.delete()
        current.delete()
    }
}
