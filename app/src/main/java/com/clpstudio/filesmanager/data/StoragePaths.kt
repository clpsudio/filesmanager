package com.clpstudio.filesmanager.data

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

data class StorageVolume(val path: String, val label: String, val total: Long, val free: Long)

object StoragePaths {
    val primary: String get() = Environment.getExternalStorageDirectory().absolutePath

    fun downloads(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** Internal storage plus any SD card / USB volume the app can reach. */
    fun volumes(context: Context): List<StorageVolume> {
        val roots = linkedSetOf(primary)
        context.getExternalFilesDirs(null).filterNotNull().forEach { appDir ->
            val p = appDir.absolutePath
            val idx = p.indexOf("/Android/data/")
            if (idx > 0) roots += p.substring(0, idx)
        }
        return roots.mapIndexed { i, root ->
            val (total, free) = runCatching { StatFs(root).let { it.totalBytes to it.availableBytes } }.getOrDefault(0L to 0L)
            StorageVolume(root, if (i == 0) "Internal storage" else "SD card", total, free)
        }
    }

    /**
     * Folders that belong to the app alone and must never show up in the browser, search or
     * categories, even with "Show hidden files" on: the vault (phase 2, app-private internal
     * storage) and the Bin.
     */
    fun privateRoots(context: Context): List<String> = listOf(
        vaultDir(context).absolutePath,
        binDir(context).absolutePath,
    )

    /** Phase 2 vault: app-private, internal (not shared) storage, excluded from backups. */
    fun vaultDir(context: Context): File = File(context.noBackupFilesDir, "vault")

    /** Bin lives on the same volume as the files (fast moves), inside the app's own folder. */
    fun binDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "bin")

    fun relative(path: String): String {
        val p = primary
        return if (path.startsWith(p)) "Internal storage" + path.removePrefix(p) else path
    }
}
