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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/** The list the file viewer swipes through (set just before opening it), in the list's own order. */
object ViewerSession {
    /** Setting a new list ends playlist mode; "Play N" turns it back on right after (item 37). */
    var files: List<String> = emptyList()
        set(v) { field = v; playlist = false }
    /** The pager was opened by "Play N" on videos: loop settings are ignored, it plays on and wraps (item 37). */
    var playlist: Boolean = false
    /** Items for "Play N" (slideshow / playlist), in the selected order. */
    var playItems: List<FileItem> = emptyList()
}

/** Opens any file inside the app: everything has an in-app viewer or a clear fallback. */
class Opener(private val context: Context, private val nav: NavController) {

    /**
     * "Play N": only audio → the music player's playlist (keeps playing in the background and when
     * you go back, with the mini player); only videos → the video player's playlist; anything with
     * photos → the slideshow (its "Time per photo" only shows when there are photos).
     */
    fun playSelected(items: List<FileItem>) {
        when (PlayRoute.of(items)) {
            PlayRoute.NONE -> return
            PlayRoute.MUSIC -> {
                val audios = items.filter { it.kind == FileKind.AUDIO }
                Graph.appScope.launch(Dispatchers.Main) {
                    runCatching { PlayerConnection.playAudio(context, audios, 0, playlist = true) }
                }
                nav.navigate(Routes.MUSIC) { launchSingleTop = true }
            }
            PlayRoute.VIDEO -> {
                // The same gallery-style video view as everywhere else, over just these videos.
                val videos = items.filter { it.kind == FileKind.VIDEO }
                ViewerSession.files = videos.map { it.path }
                ViewerSession.playlist = true
                nav.navigate(Routes.view(videos.first().path))
            }
            PlayRoute.SLIDESHOW -> {
                ViewerSession.playItems = items
                nav.navigate(Routes.SLIDESHOW)
            }
        }
    }

    fun open(item: FileItem, siblings: List<FileItem> = emptyList()) {
        if (item.isDir) {
            nav.navigate(Routes.browse(item.path))
            return
        }
        when (item.kind) {
            // Videos play in the photo / video pager (one video UI everywhere: Videos, Media, Recent, search, folders, Favorites).
            FileKind.VIDEO -> openVideo(item, siblings)
            FileKind.AUDIO, FileKind.APK -> openFull(item, siblings)
            FileKind.ARCHIVE -> if (com.clpstudio.filesmanager.data.Archives.canBrowse(item.name)) openFull(item, siblings) else openPager(item, siblings)
            // E-books open straight in the reader.
            FileKind.DOCUMENT -> if (item.extension == "epub") nav.navigate(Routes.epub(item.path)) else openPager(item, siblings)
            else -> openPager(item, siblings)
        }
    }

    /**
     * A video: the pager over the photos and videos of the same list (Media swipes through both; a list of only
     * videos, or a folder, swipes through its videos), so every video opens in the same gallery-style view.
     */
    private fun openVideo(item: FileItem, siblings: List<FileItem>) {
        val media = siblings.filter { !it.isDir && (it.kind == FileKind.VIDEO || it.kind == FileKind.IMAGE) }
        ViewerSession.files = VideoOpen.pages(item.path, media.map { it.path to (it.kind == FileKind.VIDEO) })
        nav.navigate(Routes.view(item.path))
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
            FileKind.VIDEO -> openVideo(item, siblings)
            FileKind.AUDIO -> {
                // Every song of this list, in order, from the tapped one: the player moves on by itself (item 25).
                val (audios, index) = com.clpstudio.filesmanager.playback.AudioQueue.build(item, siblings)
                Graph.appScope.launch(Dispatchers.Main) {
                    runCatching { PlayerConnection.playAudio(context, audios, index) }
                }
                nav.navigate(Routes.MUSIC)
            }
            // The APK preview page (icon, version, permissions, signature, files inside) with Install / Update / Open.
            FileKind.APK -> nav.navigate(Routes.apk(item.path))
            FileKind.ARCHIVE -> if (com.clpstudio.filesmanager.data.Archives.canBrowse(item.name)) nav.navigate(Routes.zip(item.path)) else nav.navigate(Routes.info(item.path))
            FileKind.FONT -> nav.navigate(Routes.font(Uri.fromFile(file), item.name))
            FileKind.IMAGE, FileKind.PDF, FileKind.TEXT -> openPager(item, siblings)
            // Word / Excel / PowerPoint: the paper-page viewer in the pager (it used to fall through to the
            // "no preview" info screen, so "Open" on a .docx never showed the document).
            FileKind.DOCUMENT -> when {
                item.extension == "epub" -> nav.navigate(Routes.epub(item.path))
                com.clpstudio.filesmanager.viewer.hasOfficeViewer(item.name) -> openPager(item, siblings)
                else -> nav.navigate(Routes.info(item.path))
            }
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

/** Which pages a video opens with (pure, unit tested). */
object VideoOpen {
    /**
     * [media] = the list's photos and videos in order (path to isVideo). The pager gets all of them, so Media swipes
     * through photos and videos together; the opened video is always in it (alone when the list doesn't have it).
     */
    fun pages(open: String, media: List<Pair<String, Boolean>>): List<String> {
        val paths = media.map { it.first }.distinct()
        return if (open in paths) paths else listOf(open)
    }

    /**
     * After a video is done (Off, or "Play N times" finished): the next video page after [from], skipping photos and
     * other files; after the last video the first one again (item 24), which is [from] itself when it's the only one.
     * Null only when the list has no video.
     */
    fun nextVideo(isVideo: List<Boolean>, from: Int): Int? =
        ((from + 1 until isVideo.size) + (0..from.coerceAtMost(isVideo.size - 1))).firstOrNull { isVideo.getOrElse(it) { false } }
}
