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
        runCatching { Graph.index.invalidate() }
        runCatching { Graph.hiddenMedia.invalidate() }
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
                refresh()
            }
        }
    }

    /**
     * Runs a long file operation (zip, unzip, PDF password...) with the shared progress dialog,
     * a Stop button, a toast with [block]'s result, and a rescan of [block]'s new files.
     */
    /** A finished operation with a follow-up ("Extracted to Photos" → Open folder / Open). */
    class DoneAction(val message: String, val label: String, val target: File)
    var doneAction by mutableStateOf<DoneAction?>(null)
    /** A file or folder to highlight briefly in lists (e.g. the folder a zip was just extracted to). */
    var highlight by mutableStateOf<String?>(null)

    fun runOperation(
        context: Context,
        title: String,
        followUp: ((created: List<File>) -> DoneAction?)? = null,
        block: suspend (report: (OpProgress) -> Unit) -> Pair<String, List<File>>,
    ) {
        job = Graph.appScope.launch {
            try {
                progress = OpProgress(title)
                val (message, created) = block { p -> progress = p }
                val paths = created.flatMap { f -> if (f.isDirectory) f.walkTopDown().filter { it.isFile }.take(5000).map { it.absolutePath }.toList() else listOf(f.absolutePath) }
                if (paths.isNotEmpty()) FileOps.scan(context, paths)
                val follow = followUp?.invoke(created)
                withContext(Dispatchers.Main) {
                    if (follow != null) {
                        highlight = follow.target.absolutePath
                        doneAction = follow
                    } else {
                        toast(context, message)
                    }
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) { toast(context, "Stopped") }
            } catch (e: com.clpstudio.filesmanager.data.WrongPasswordException) {
                withContext(Dispatchers.Main) { toast(context, "Wrong password") }
            } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
                withContext(Dispatchers.Main) { toast(context, "Wrong password") }
            } catch (e: Throwable) {
                com.clpstudio.filesmanager.data.Diagnostics.log("op", "$title failed", e)
                withContext(Dispatchers.Main) { toast(context, e.message ?: "Something went wrong") }
            } finally {
                progress = null
                refresh()
            }
        }
    }

    fun moveToBin(context: Context, files: List<File>, onDone: () -> Unit = {}) {
        job = Graph.appScope.launch {
            try {
                progress = OpProgress("Moving to Bin", totalItems = files.size)
                val n = Graph.bin.moveToBin(files) { done -> progress = OpProgress("Moving to Bin", doneItems = done, totalItems = files.size) }
                com.clpstudio.filesmanager.data.Favorites.onDeleted(files.filter { !it.exists() }.map { it.absolutePath })
                withContext(Dispatchers.Main) {
                    toast(context, "$n item" + (if (n == 1) "" else "s") + " moved to Bin (kept 30 days)")
                    onDone()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast(context, e.message ?: "Couldn't delete") }
            } finally {
                progress = null
                refresh()
            }
        }
    }
}
