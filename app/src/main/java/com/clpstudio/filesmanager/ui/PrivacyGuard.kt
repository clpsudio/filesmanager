package com.clpstudio.filesmanager.ui

import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.clpstudio.filesmanager.Graph
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Pure rules for FLAG_SECURE (item 39 + "Hide content while switching apps"). */
object PrivacyRules {
    /**
     * FLAG_SECURE on? Always on vault screens ([vaultHeld]); always with "Block screenshots & screen recording"
     * ([block], also in picture-in-picture); on Android 8–12 also while the app is in the background with
     * "Hide content while switching apps" on (not in picture-in-picture, where it would show black: [secureAllowed]).
     */
    fun secure(block: Boolean, hideInRecents: Boolean, resumed: Boolean, sdk: Int, secureAllowed: Boolean, vaultHeld: Boolean): Boolean =
        vaultHeld || block || (sdk < 33 && hideInRecents && !resumed && secureAllowed)
}

/**
 * "Hide content while switching apps": the app's preview in Recents is blank.
 * Android 13+: setRecentsScreenshotEnabled(false), so screenshots inside the app still work.
 * Android 8–12: FLAG_SECURE only while the app is in the background (set on pause, cleared on resume).
 * "Block screenshots & screen recording" (item 39, default off): FLAG_SECURE on every screen, applied live.
 */
object PrivacyGuard {
    /** The block setting as last read; SecureWindow keeps the flag on when a vault screen closes. */
    @Volatile var blockAll: Boolean = false
        private set

    /** [secureAllowed]: false while FLAG_SECURE would hurt (picture-in-picture on Android 8–12 shows black). */
    fun install(activity: ComponentActivity, secureAllowed: () -> Boolean = { true }) {
        val s0 = Graph.settings.value
        var hide = s0.hideInRecents
        blockAll = s0.blockScreenshots
        apply(activity, hide, resumed = true, secureAllowed)
        activity.lifecycleScope.launch {
            Graph.settingsRepo.settings.map { it.hideInRecents to it.blockScreenshots }.distinctUntilChanged().collect { (h, b) ->
                hide = h
                blockAll = b
                apply(activity, h, resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), secureAllowed)
            }
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) = apply(activity, hide, resumed = false, secureAllowed)
            override fun onResume(owner: LifecycleOwner) = apply(activity, hide, resumed = true, secureAllowed)
        })
    }

    /**
     * Screens shown inside another app's flow (the file picker): only "Block screenshots & screen recording",
     * applied live — before item 16's fix the picker read the setting once when it opened (often before the saved
     * settings had loaded), so a toggle only showed after closing and reopening the picker.
     */
    fun installBlockOnly(activity: ComponentActivity) {
        fun set(on: Boolean) {
            blockAll = on
            if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        set(runCatching { Graph.settings.value.blockScreenshots }.getOrDefault(false))
        activity.lifecycleScope.launch {
            Graph.settingsRepo.settings.map { it.blockScreenshots }.distinctUntilChanged().collect { set(it) }
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = set(runCatching { Graph.settings.value.blockScreenshots }.getOrDefault(blockAll))
        })
    }

    private fun apply(activity: ComponentActivity, hide: Boolean, resumed: Boolean, secureAllowed: () -> Boolean) {
        if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(!hide)
        val on = PrivacyRules.secure(
            block = blockAll, hideInRecents = hide, resumed = resumed, sdk = Build.VERSION.SDK_INT,
            secureAllowed = secureAllowed(), vaultHeld = com.clpstudio.filesmanager.ui.security.SecureWindow.isHeld(activity),
        )
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
