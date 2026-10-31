package com.clpstudio.filesmanager.crash

import android.os.StrictMode

/**
 * Debug builds only: flags disk/network IO on the main thread and leaked closeables in Logcat
 * (penaltyLog, never penaltyDeath, so a debug build still runs normally).
 */
object DebugStrictMode {
    fun enable() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .build(),
        )
    }
}
