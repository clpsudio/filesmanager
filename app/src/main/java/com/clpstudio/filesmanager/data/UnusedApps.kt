package com.clpstudio.filesmanager.data

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import com.clpstudio.filesmanager.Graph

/** Item 36: how long an app has gone unused. Ranges start at the label: "2 months" = 2 up to 3 months. */
enum class UnusedBucket(val label: String, val minDays: Int) {
    D30("30 days", 30),
    M2("2 months", 60),
    M3("3 months", 90),
    M6("6 months", 180),
    Y1("1 year", 365),
    OLDER("Older", 730),
}

data class UnusedApp(
    val packageName: String,
    val label: String,
    /** Last use Android recorded; 0 = no usage record (then [installedAt]/[updatedAt] decide). */
    val lastUsed: Long,
    val installedAt: Long,
    val updatedAt: Long,
    val bucket: UnusedBucket,
) {
    val noRecord: Boolean get() = lastUsed <= 0L
}

object UnusedApps {
    const val DAY = 86_400_000L

    /** Last activity: the last use when Android has one, else the newer of install / update. */
    fun lastActivity(lastUsed: Long, installedAt: Long, updatedAt: Long): Long =
        if (lastUsed > 0L) lastUsed else maxOf(installedAt, updatedAt)

    /** Null = used (or installed/updated) within the last 30 days: not an unused app. */
    fun bucket(lastActivity: Long, now: Long): UnusedBucket? {
        if (lastActivity <= 0L) return UnusedBucket.OLDER
        val days = (now - lastActivity) / DAY
        if (days < UnusedBucket.D30.minDays) return null
        return UnusedBucket.entries.last { days >= it.minDays }
    }

    fun classify(pkg: String, label: String, lastUsed: Long, installedAt: Long, updatedAt: Long, now: Long): UnusedApp? {
        val b = bucket(lastActivity(lastUsed, installedAt, updatedAt), now) ?: return null
        return UnusedApp(pkg, label, lastUsed.coerceAtLeast(0L), installedAt, updatedAt, b)
    }

    /**
     * Installed (user / updated) apps with a launcher icon, this app excluded, not used for 30+ days.
     * Android keeps a limited usage history (yearly stats for about two years), so an app it has no
     * record of falls back to its install / update time and says so.
     */
    suspend fun load(context: Context, now: Long = System.currentTimeMillis()): List<UnusedApp> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = runCatching { usm.queryAndAggregateUsageStats(now - 3 * 365 * DAY, now) }.getOrDefault(emptyMap())
        val pm = context.packageManager
        return Graph.apps.installed(system = false).mapNotNull { app ->
            if (app.packageName == context.packageName || !app.canLaunch) return@mapNotNull null
            val s = stats[app.packageName]
            var last = s?.lastTimeUsed ?: 0L
            if (Build.VERSION.SDK_INT >= 29 && s != null) last = maxOf(last, s.lastTimeVisible)
            // Some devices report a "used" time of 1970 for apps never opened.
            if (last < 365L * DAY) last = 0L
            val updated = runCatching { @Suppress("DEPRECATION") pm.getPackageInfo(app.packageName, 0).lastUpdateTime }.getOrDefault(app.installedAt)
            classify(app.packageName, app.label, last, app.installedAt, updated, now)
        }.sortedWith(compareByDescending<UnusedApp> { it.bucket.ordinal }.thenBy { lastActivity(it.lastUsed, it.installedAt, it.updatedAt) })
    }
}
