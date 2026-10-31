package com.clpstudio.filesmanager.data

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.ui.AppState

/**
 * Keeps every list fresh without pull-to-refresh: while the app is in front it watches the
 * MediaStore for changes (debounced), and coming back to the app reloads open screens
 * (files may have been added by WhatsApp, a download, the camera...).
 */
object StorageWatcher : DefaultLifecycleObserver {
    private val main = Handler(Looper.getMainLooper())
    private var registered = false
    private var startedOnce = false

    private val reload = Runnable {
        Graph.index.invalidate()
        AppState.refresh()
    }

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            main.removeCallbacks(reload)
            main.postDelayed(reload, 1200)
        }
    }

    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /** A change we made ourselves (copy, move, extract...): reload now. */
    fun changedByUs() {
        main.removeCallbacks(reload)
        main.post(reload)
    }

    override fun onStart(owner: LifecycleOwner) {
        if (!registered) {
            runCatching {
                Graph.app.contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer)
                registered = true
            }.onFailure { Diagnostics.log("watch", "can't watch MediaStore", it) }
        }
        if (startedOnce) changedByUs() // back in the app: storage may have changed meanwhile
        startedOnce = true
    }

    override fun onStop(owner: LifecycleOwner) {
        if (registered) {
            runCatching { Graph.app.contentResolver.unregisterContentObserver(observer) }
            registered = false
        }
    }
}
