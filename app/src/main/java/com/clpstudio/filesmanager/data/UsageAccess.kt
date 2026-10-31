package com.clpstudio.filesmanager.data

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Item 36: "Usage access" (app usage permission) state, live.
 *
 * Why it wasn't detected before: Manage storage only learned the permission inside its full storage
 * report (a slow scan), its "came back from Settings" check skipped when that report hadn't finished
 * yet, and the Unused apps tile decided from the old report — so after granting, the app kept saying
 * "allow Usage access" until a full recompute happened to finish. Now the permission is its own state:
 * checked on every resume and watched with AppOpsManager.startWatchingMode, so it flips the moment
 * the user grants it in Settings, with no restart and no refresh.
 */
object UsageAccess {
    private val _granted = MutableStateFlow(false)
    val granted: StateFlow<Boolean> = _granted.asStateFlow()

    private var watching: AppOpsManager.OnOpChangedListener? = null
    private val listeners = mutableListOf<(Boolean) -> Unit>()

    /** Reads the permission now (and updates [granted]). */
    fun check(context: Context): Boolean {
        val now = ManageStorageRepository.hasUsageAccess(context)
        val was = _granted.value
        _granted.value = now
        if (now != was) synchronized(listeners) { listeners.toList() }.forEach { it(now) }
        return now
    }

    /** Called with the new value whenever the permission changes (any thread). */
    fun onChange(l: (Boolean) -> Unit) { synchronized(listeners) { listeners += l } }

    /** Watches the app-op for this app; idempotent. */
    fun watch(context: Context) {
        val app = context.applicationContext
        check(app)
        if (watching != null) return
        val ops = app.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return
        val main = Handler(Looper.getMainLooper())
        val l = AppOpsManager.OnOpChangedListener { op, pkg ->
            if (op == AppOpsManager.OPSTR_GET_USAGE_STATS && (pkg == null || pkg == app.packageName)) {
                // The system writes the new mode right after the callback on some versions: read again shortly.
                main.post { check(app) }
                main.postDelayed({ check(app) }, 400)
            }
        }
        runCatching { ops.startWatchingMode(AppOpsManager.OPSTR_GET_USAGE_STATS, app.packageName, l); watching = l }
            .onFailure { Diagnostics.log("usage", "startWatchingMode failed", it) }
    }

    /** The Usage access page, straight to Files when the device supports it, else the list. */
    fun openSettings(context: Context) {
        val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val list = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = Build.VERSION.SDK_INT >= 29 && runCatching { context.startActivity(direct) }.isSuccess
        if (!ok) runCatching { context.startActivity(list) }
    }
}
