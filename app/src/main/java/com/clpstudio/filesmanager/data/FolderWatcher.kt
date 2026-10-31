package com.clpstudio.filesmanager.data

import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.File

/** Watches one folder and calls [onChange] (debounced, on the main thread) when its entries change. */
class FolderWatcher(private val dir: File, private val onChange: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val fire = Runnable { onChange() }
    private val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
        FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF

    private val observer: FileObserver = if (Build.VERSION.SDK_INT >= 29) {
        object : FileObserver(dir, mask) {
            override fun onEvent(event: Int, path: String?) = changed()
        }
    } else {
        @Suppress("DEPRECATION")
        object : FileObserver(dir.absolutePath, mask) {
            override fun onEvent(event: Int, path: String?) = changed()
        }
    }

    private fun changed() {
        main.removeCallbacks(fire)
        main.postDelayed(fire, 400)
    }

    fun start() = runCatching { observer.startWatching() }.let { }
    fun stop() {
        runCatching { observer.stopWatching() }
        main.removeCallbacks(fire)
    }
}
