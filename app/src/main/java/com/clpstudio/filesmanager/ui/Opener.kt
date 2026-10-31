package com.clpstudio.filesmanager.ui

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

/** The list the photo viewer swipes through (set just before opening it). */
object ViewerSession {
    var images: List<String> = emptyList()
}

/** Opens any file inside the app: everything has an in-app viewer or a clear fallback. */
class Opener(private val context: Context, private val nav: NavController) {

    fun open(item: FileItem, siblings: List<FileItem> = emptyList()) {
        if (item.isDir) {
            nav.navigate(Routes.browse(item.path))
            return
        }
        val file = File(item.path)
        when (item.kind) {
            FileKind.IMAGE -> {
                val images = siblings.filter { it.kind == FileKind.IMAGE }.map { it.path }
                ViewerSession.images = if (item.path in images) images else listOf(item.path)
                nav.navigate(Routes.image(item.path))
            }
            FileKind.VIDEO -> {
                val videos = siblings.filter { it.kind == FileKind.VIDEO }.ifEmpty { listOf(item) }
                VideoPlayerActivity.startFiles(context, videos, videos.indexOfFirst { it.path == item.path }.coerceAtLeast(0))
            }
            FileKind.AUDIO -> {
                val audios = siblings.filter { it.kind == FileKind.AUDIO }.ifEmpty { listOf(item) }
                val index = audios.indexOfFirst { it.path == item.path }.coerceAtLeast(0)
                Graph.appScope.launch(Dispatchers.Main) {
                    runCatching { PlayerConnection.playAudio(context, audios, index) }
                }
                nav.navigate(Routes.MUSIC)
            }
            FileKind.PDF -> nav.navigate(Routes.pdf(Uri.fromFile(file), item.name))
            FileKind.TEXT -> nav.navigate(Routes.text(Uri.fromFile(file), item.name))
            FileKind.ARCHIVE -> if (item.extension in setOf("zip", "jar")) nav.navigate(Routes.zip(item.path)) else nav.navigate(Routes.info(item.path))
            FileKind.APK -> installApk(context, file)
            FileKind.DOCUMENT, FileKind.OTHER, FileKind.FOLDER -> nav.navigate(Routes.info(item.path))
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

        fun installApk(context: Context, uri: Uri) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "The installer isn't available", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
