package com.clpstudio.filesmanager.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val apkPath: String,
    val size: Long,
    val installedAt: Long,
    val isSystem: Boolean,
    val canLaunch: Boolean,
)

class AppsRepository(private val context: Context) {
    private val pm: PackageManager = context.packageManager

    suspend fun installed(system: Boolean): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pkgs: List<PackageInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }
        pkgs.mapNotNull { p ->
            val ai = p.applicationInfo ?: return@mapNotNull null
            val isSys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (isSys != system) return@mapNotNull null
            InstalledApp(
                packageName = p.packageName,
                label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(p.packageName),
                versionName = p.versionName ?: "",
                apkPath = ai.sourceDir ?: "",
                size = runCatching { File(ai.sourceDir).length() }.getOrDefault(0L),
                installedAt = p.firstInstallTime,
                isSystem = isSys,
                canLaunch = pm.getLaunchIntentForPackage(p.packageName) != null,
            )
        }.sortedBy { it.label.lowercase() }
    }

    /** Label + version of an APK file on storage (null if it can't be read). */
    fun apkInfo(path: String): Pair<String, String>? = runCatching {
        val info = archiveInfo(path) ?: return null
        val ai = info.applicationInfo ?: return null
        ai.sourceDir = path
        ai.publicSourceDir = path
        ai.loadLabel(pm).toString() to (info.versionName ?: "")
    }.getOrNull()

    private fun archiveInfo(path: String): PackageInfo? = if (Build.VERSION.SDK_INT >= 33) {
        pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageArchiveInfo(path, 0)
    }

    /** Copies an installed app's APK to Download/Files Apps and returns the file. */
    suspend fun extractApk(app: InstalledApp): File = withContext(Dispatchers.IO) {
        val outDir = File(StoragePaths.downloads(), "Files Apps").apply { mkdirs() }
        val base = (app.label + "_" + app.versionName).replace(Regex("[^A-Za-z0-9._ -]"), "_").trim()
        val name = uniqueName("$base.apk", false) { File(outDir, it).exists() }
        val out = File(outDir, name)
        File(app.apkPath).copyTo(out)
        FileOps.scan(context, listOf(out.absolutePath))
        out
    }
}
