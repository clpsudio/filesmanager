package com.clpstudio.filesmanager.ui

import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.clpstudio.filesmanager.Graph
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * "Hide content while switching apps": the app's preview in Recents is blank.
 * Android 13+: setRecentsScreenshotEnabled(false), so screenshots inside the app still work.
 * Android 8–12: FLAG_SECURE only while the app is in the background (set on pause, cleared on resume).
 */
object PrivacyGuard {
    fun install(activity: ComponentActivity) {
        var on = Graph.settings.value.hideInRecents
        apply(activity, on, resumed = true)
        activity.lifecycleScope.launch {
            Graph.settingsRepo.settings.map { it.hideInRecents }.distinctUntilChanged().collect {
                on = it
                apply(activity, it, resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            }
        }
        if (Build.VERSION.SDK_INT < 33) {
            activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onPause(owner: LifecycleOwner) = apply(activity, on, resumed = false)
                override fun onResume(owner: LifecycleOwner) = apply(activity, on, resumed = true)
            })
        }
    }

    private fun apply(activity: ComponentActivity, on: Boolean, resumed: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.setRecentsScreenshotEnabled(!on)
        } else if (on && !resumed) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
