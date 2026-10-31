package com.clpstudio.filesmanager.data

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

/** A Bin folder on an SD card: [root] is the card, [dir] the Bin inside the app's folder on it. */
data class CardBin(val root: String, val dir: File)

data class StorageVolume(val path: String, val label: String, val total: Long, val free: Long, val removable: Boolean = false)

object StoragePaths {
    val primary: String get() = Environment.getExternalStorageDirectory().absolutePath

    fun downloads(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /**
     * Internal storage plus every mounted SD card / USB volume, the cards only while
     * Settings → "Show SD card" is on (then they are hidden everywhere: browser, categories,
     * search, picker). [allVolumes] ignores the setting.
     */
    fun volumes(context: Context): List<StorageVolume> =
        toStorageVolumes(VolumeRules.visible(SdCards.all(context), showSdCard()))

    /** Every mounted volume, whatever "Show SD card" says (File info, diagnostics). */
    fun allVolumes(context: Context): List<StorageVolume> = toStorageVolumes(SdCards.all(context))

    /** Card roots to hide everywhere (empty while "Show SD card" is on). */
    fun hiddenRoots(context: Context): List<String> = VolumeRules.hiddenRoots(SdCards.all(context), showSdCard())

    fun showSdCard(): Boolean = runCatching { com.clpstudio.filesmanager.Graph.settings.value.showSdCard }.getOrDefault(true)

    private fun toStorageVolumes(list: List<MountedVolume>): List<StorageVolume> = list.map { v ->
        val (total, free) = runCatching { StatFs(v.path).let { it.totalBytes to it.availableBytes } }.getOrDefault(0L to 0L)
        StorageVolume(v.path, v.label, total, free, v.removable)
    }

    /**
     * Folders that belong to the app alone and must never show up in the browser, search or
     * categories, even with "Show hidden files" on: the vault (phase 2, app-private internal
     * storage) and the Bin.
     */
    fun privateRoots(context: Context): List<String> = listOf(
        vaultDir(context).absolutePath,
        binDir(context).absolutePath,
    ) + cardBins(context).map { it.dir.absolutePath }

    /** Phase 2 vault: app-private, internal (not shared) storage, excluded from backups. */
    fun vaultDir(context: Context): File = File(context.noBackupFilesDir, "vault")

    /** Bin lives on the same volume as the files (fast moves), inside the app's own folder. */
    fun binDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "bin")

    /**
     * The SD card's own Bin: `<card>/Android/data/<app>/files/bin`, the app's folder on the card
     * (writable on every Android version without extra permission). A card file deleted to the
     * Bin is renamed into it — no copy, no phone space used, and it stays on the card.
     */
    fun cardBins(context: Context): List<CardBin> {
        val cards = runCatching { SdCards.removableRoots(context) }.getOrDefault(emptyList())
        if (cards.isEmpty()) return emptyList()
        val appDirs = runCatching { context.getExternalFilesDirs(null).filterNotNull() }.getOrDefault(emptyList())
        return cards.mapNotNull { root ->
            val appDir = appDirs.firstOrNull { VolumeRules.isUnder(it.absolutePath, root) } ?: return@mapNotNull null
            CardBin(root, File(appDir, "bin"))
        }
    }

    fun relative(path: String): String {
        val p = primary
        if (path.startsWith(p)) return "Internal storage" + path.removePrefix(p)
        val card = runCatching { SdCards.all(com.clpstudio.filesmanager.Graph.app) }.getOrDefault(emptyList())
            .firstOrNull { it.removable && VolumeRules.isUnder(path, it.path) }
        return if (card != null) card.label + path.removePrefix(card.path) else path
    }
}
