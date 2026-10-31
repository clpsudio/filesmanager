package com.clpstudio.filesmanager.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.FileOps
import com.clpstudio.filesmanager.data.OpProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ClipboardState(val paths: List<String>, val move: Boolean) {
    val count: Int get() = paths.size
}

/** App-wide UI state: the copy/move clipboard and the running file operation. */
object AppState {
    var clipboard by mutableStateOf<ClipboardState?>(null)
    var progress by mutableStateOf<OpProgress?>(null)
        private set
    /** Bumped after any change on storage, so open screens reload. */
    var refreshTick by mutableIntStateOf(0)
        private set
    private var job: Job? = null

    fun refresh() {
        refreshTick++
    }

    fun cancelOperation() {
        job?.cancel()
    }

    private fun toast(context: Context, text: String) {
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    fun paste(context: Context, targetDir: File) {
        val clip = clipboard ?: return
        job = Graph.appScope.launch {
            try {
                val sources = clip.paths.map { File(it) }.filter { it.exists() }
                FileOps.transfer(context, sources, targetDir, clip.move) { p -> progress = p }
                clipboard = null
                withContext(Dispatchers.Main) {
                    toast(context, (if (clip.move) "Moved " else "Copied ") + sources.size + " item" + if (sources.size == 1) "" else "s")
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) { toast(context, "Stopped") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast(context, e.message ?: "Something went wrong") }
            } finally {
                progress = null
                refreshTick++
            }
        }
    }

    fun moveToBin(context: Context, files: List<File>, onDone: () -> Unit = {}) {
        job = Graph.appScope.launch {
            try {
                progress = OpProgress("Moving to Bin", totalItems = files.size)
                val n = Graph.bin.moveToBin(files) { done -> progress = OpProgress("Moving to Bin", doneItems = done, totalItems = files.size) }
                withContext(Dispatchers.Main) {
                    toast(context, "$n item" + (if (n == 1) "" else "s") + " moved to Bin (kept 30 days)")
                    onDone()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast(context, e.message ?: "Couldn't delete") }
            } finally {
                progress = null
                refreshTick++
            }
        }
    }
}
