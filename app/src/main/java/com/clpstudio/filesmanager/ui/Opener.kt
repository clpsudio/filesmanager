package com.clpstudio.filesmanager.ui

import com.clpstudio.filesmanager.install.ApkInstaller
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.navigation.NavController
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.FileOps
import com.clpstudio.filesmanager.data.FileTypes
import com.clpstudio.filesmanager.playback.PlayerConnection
import com.clpstudio.filesmanager.viewer.VideoPlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/** The list the file viewer swipes through (set just before opening it), in the list's own order. */
object ViewerSession {
    var files: List<String> = emptyList()
    /** Items for "Play N" (slideshow / playlist), in the selected order. */
    var playItems: List<FileItem> = emptyList()
}

/** Opens any file inside the app: everything has an in-app viewer or a clear fallback. */
class Opener(private val context: Context, private val nav: NavController) {

    /** Selected photos, videos and audio as a slideshow / playlist. */
    fun playSelected(items: List<FileItem>) {
        ViewerSession.playItems = items
        nav.navigate(Routes.SLIDESHOW)
    }

    fun open(item: FileItem, siblings: List<FileItem> = emptyList()) {
        if (item.isDir) {
            nav.navigate(Routes.browse(item.path))
            return
        }
        when (item.kind) {
            FileKind.VIDEO, FileKind.AUDIO, FileKind.APK -> openFull(item, siblings)
            FileKind.ARCHIVE -> if (item.extension in setOf("zip", "jar")) openFull(item, siblings) else openPager(item, siblings)
            else -> openPager(item, siblings)
        }
    }

    /** Photos, PDFs, text, documents...: the swipeable viewer over the same list. */
    private fun openPager(item: FileItem, siblings: List<FileItem>) {
        val files = siblings.filter { !it.isDir }.map { it.path }
        ViewerSession.files = if (item.path in files) files else listOf(item.path)
        nav.navigate(Routes.view(item.path))
    }

    /** The full-screen player / installer / archive browser for one file. */
    fun openFull(item: FileItem, siblings: List<FileItem>) {
        val file = File(item.path)
        when (item.kind) {
            FileKind.VIDEO -> {
                // From Media (or any list mixing photos and videos) the player swipes through both.
                val media = siblings.filter { it.kind == FileKind.VIDEO || it.kind == FileKind.IMAGE }
                val list = (if (media.any { it.kind == FileKind.IMAGE }) media else siblings.filter { it.kind == FileKind.VIDEO })
                    .takeIf { l -> l.any { it.path == item.path } } ?: listOf(item)
                VideoPlayerActivity.startFiles(context, list, list.indexOfFirst { it.path == item.path }.coerceAtLeast(0))
            }
            FileKind.AUDIO -> {
                val audios = siblings.filter { it.kind == FileKind.AUDIO }.ifEmpty { listOf(item) }
                val index = audios.indexOfFirst { it.path == item.path }.coerceAtLeast(0)
                Graph.appScope.launch(Dispatchers.Main) {
                    runCatching { PlayerConnection.playAudio(context, audios, index) }
                }
                nav.navigate(Routes.MUSIC)
            }
            FileKind.APK -> installApk(context, file)
            FileKind.ARCHIVE -> if (item.extension in setOf("zip", "jar")) nav.navigate(Routes.zip(item.path)) else nav.navigate(Routes.info(item.path))
            FileKind.FONT -> nav.navigate(Routes.font(Uri.fromFile(file), item.name))
            FileKind.IMAGE, FileKind.PDF, FileKind.TEXT -> openPager(item, siblings)
            else -> nav.navigate(Routes.info(item.path))
        }
    }

    companion object {
        fun installApk(context: Context, file: File) {
            if (file.extension.lowercase() != "apk") {
                FileOps.openWithOtherApp(context, FileOps.uriFor(context, file), FileTypes.mimeOf(file.name))
                return
            }
            installApk(context, FileOps.uriFor(context, file))
        }

        /** Opens the system install prompt directly (no copy to Downloads). */
        fun installApk(context: Context, uri: Uri) = ApkInstaller.install(context, uri)
    }
}
