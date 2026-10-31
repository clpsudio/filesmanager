package com.clpstudio.filesmanager.viewer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Info
import androidx.media3.common.util.UnstableApi
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.ExternalFiles
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.FileOps
import com.clpstudio.filesmanager.data.OpenedFile
import com.clpstudio.filesmanager.data.StoragePaths
import com.clpstudio.filesmanager.data.uniqueName
import com.clpstudio.filesmanager.playback.PlaybackService
import com.clpstudio.filesmanager.playback.PlayerConnection
import com.clpstudio.filesmanager.playback.rememberPlayerUi
import com.clpstudio.filesmanager.ui.Opener
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Open with Files" from any app (WhatsApp, Gmail, Telegram, Drive...): works on content:// uris
 * without a file path. Video and audio play straight from the uri; PDF opens its file descriptor
 * (copied to the cache only if it isn't seekable); unknown types get an info screen.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class OpenWithActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.clpstudio.filesmanager.viewer.VideoPip.register(this)
        com.clpstudio.filesmanager.ui.PrivacyGuard.install(this)
        enableEdgeToEdge()
        val uri = incomingUri(intent)
        if (uri == null) {
            Toast.makeText(this, "Nothing to open", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val type = intent.type
        setContent {
            val settings by Graph.settings.collectAsState()
            FilesTheme(settings.theme, settings.liquidGlass) {
                val opened by produceState<OpenedFile?>(null, uri) { value = ExternalFiles.resolve(this@OpenWithActivity, uri, type) }
                val f = opened
                if (f == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    ExternalViewer(f)
                }
                com.clpstudio.filesmanager.ui.AppState.progress?.let {
                    com.clpstudio.filesmanager.ui.components.ProgressDialog(it) { com.clpstudio.filesmanager.ui.AppState.cancelOperation() }
                }
                com.clpstudio.filesmanager.ui.components.DoneActionHost { target ->
                    // Open the result in the main app.
                    startActivity(
                        Intent(this@OpenWithActivity, com.clpstudio.filesmanager.MainActivity::class.java)
                            .setAction(com.clpstudio.filesmanager.MainActivity.ACTION_BROWSE)
                            .putExtra(com.clpstudio.filesmanager.MainActivity.EXTRA_PATH, if (target.isDirectory) target.absolutePath else target.parent),
                    )
                }
            }
        }
    }

    // Picture-in-picture for the gallery video view (item 34); closing the small window stops it (item 33).
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        com.clpstudio.filesmanager.viewer.VideoPip.onUserLeaveHint(this)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        com.clpstudio.filesmanager.viewer.VideoPip.onModeChanged(this, isInPictureInPictureMode, lifecycle)
    }

    override fun onDestroy() {
        com.clpstudio.filesmanager.viewer.VideoPip.unregister(this)
        super.onDestroy()
    }

    private fun browse(folder: String) {
        com.clpstudio.filesmanager.data.SafeStart.start(
            this,
            Intent(this, com.clpstudio.filesmanager.MainActivity::class.java)
                .setAction(com.clpstudio.filesmanager.MainActivity.ACTION_BROWSE)
                .putExtra(com.clpstudio.filesmanager.MainActivity.EXTRA_PATH, folder),
        )
    }

    private fun incomingUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> intent.data
    }

    @Composable
    private fun ExternalViewer(f: OpenedFile) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val back: () -> Unit = { finish() }
        val actions: @Composable () -> Unit = {
            IconButton(onClick = { scope.launch { saveCopy(f) } }) { Icon(Icons.Filled.SaveAlt, "Save a copy to Downloads") }
            IconButton(onClick = { FileOps.openWithOtherApp(context, f.uri, f.mime, f.name) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open with another app") }
        }
        // A photo / video that is a real file on storage (Samsung Camera's preview included, item 28/29): exactly like
        // opening it in Files, the gallery pager over its folder (Share / Favorite / Info / Delete, swipe, filmstrip).
        if (ExternalMedia.opensInPager(f)) {
            val file = File(f.path!!)
            val paths = remember(f.path) { ExternalMedia.siblings(file, Graph.settings.value.showHidden) }
            FilePagerScreen(
                paths = paths,
                start = file.absolutePath,
                onBack = back,
                openFull = { item, _ -> openInside(File(item.path)) },
                showInFolder = { folder -> browse(folder) },
            )
            return
        }
        when (f.kind) {
            FileKind.IMAGE -> SingleImageViewer(f.uri, f.name, back, actions, path = f.path)
            FileKind.PDF -> PdfViewerScreen(f.uri, f.name, back, actions)
            FileKind.TEXT -> TextViewerScreen(f.uri, f.name, back, actions)
            // Same gallery-style player as the pager, in this screen (no second player screen, no permission passed on).
            FileKind.VIDEO -> ForeignVideoScreen(f, back, actions)
            FileKind.AUDIO -> {
                LaunchedEffect(f) {
                    runCatching {
                        PlayerConnection.play(context, listOf(PlaybackService.item(f.uri, f.name.substringBeforeLast('.'), isVideo = false)), 0)
                    }
                }
                MusicPlayerScreen(rememberPlayerUi(), back)
            }
            FileKind.APK -> LaunchedEffect(f) {
                Opener.installApk(context, f.uri)
                finish()
            }
            FileKind.ARCHIVE -> {
                val local by produceState<File?>(null, f) {
                    value = if (f.mime == "application/zip" || com.clpstudio.filesmanager.data.Archives.canBrowse(f.name) ||
                        f.mime == "application/x-7z-compressed" || f.mime == "application/vnd.rar" || f.mime == "application/x-rar-compressed"
                    ) {
                        f.path?.let { File(it) } ?: runCatching { ExternalFiles.copyToCache(context, f.uri, f.name) }.getOrNull()
                    } else null
                }
                val zip = local
                if (zip != null) {
                    ZipViewerScreen(zip.absolutePath, back) { extracted -> openInside(extracted) }
                } else {
                    FileInfoScreen(f, back)
                }
            }
            FileKind.FONT -> FontPreviewScreen(f.uri, f.name, back)
            FileKind.DOCUMENT -> if (f.name.endsWith(".epub", true) || f.mime == "application/epub+zip") {
                val local by produceState<File?>(null, f) {
                    value = withContext(Dispatchers.IO) {
                        f.path?.let { File(it) } ?: runCatching { ExternalFiles.copyToCache(context, f.uri, f.name) }.getOrNull()
                    }
                }
                val book = local
                if (book == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else EpubReaderScreen(book, back)
            } else if (hasOfficeViewer(f.name)) {
                val local by produceState<File?>(null, f) {
                    value = f.path?.let { File(it) } ?: runCatching { ExternalFiles.copyToCache(context, f.uri, f.name) }.getOrNull()
                }
                var info by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize()) {
                    val file = local
                    if (file == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else OfficeBody(file, Modifier.swipeUpToOpen { info = true }, 100.dp) { FileInfoScreen(f, back) }
                    ViewerTopBar(f.name, null, back, titleColor = androidx.compose.material3.MaterialTheme.colorScheme.primary) {
                        IconButton(onClick = { info = true }) { Icon(androidx.compose.material.icons.Icons.Filled.Info, "File info") }
                        actions()
                    }
                    // Same bottom bar as every viewer: Share · ⭐ · Info · Delete (Delete/⭐ only for a file on storage).
                    UriViewerActionPanel(
                        uri = f.path?.let { Uri.fromFile(File(it)) } ?: f.uri, mime = f.mime,
                        onInfo = { info = true }, onDeleted = back,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                if (info) FileInfoSheet(InfoTarget(f.uri, f.name, f.path, f.mime), emptyList()) { info = false }
            } else {
                FileInfoScreen(f, back)
            }
            else -> if (com.clpstudio.filesmanager.data.DesignPreview.isDesign(f.name)) {
                // PSD / AI / CDR / DWG: the preview inside the file, or a clean "no preview" card.
                val local by produceState<File?>(null, f) {
                    value = withContext(Dispatchers.IO) {
                        f.path?.let { File(it) } ?: runCatching { ExternalFiles.copyToCache(context, f.uri, f.name) }.getOrNull()
                    }
                }
                val file = local
                if (file == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else DesignViewerScreen(file, f.name, back, actions)
            } else {
                var mode by remember { mutableStateOf(0) }
                when (mode) {
                    1 -> TextViewerScreen(f.uri, f.name, { mode = 0 }, actions)
                    2 -> HexViewerScreen(f.uri, f.name) { mode = 0 }
                    else -> FileInfoScreen(f, back, onViewText = { mode = 1 }, onViewHex = { mode = 2 }) {
                        IconButton(onClick = { scope.launch { saveCopy(f) } }) { Icon(Icons.Filled.SaveAlt, "Save a copy to Downloads") }
                    }
                }
            }
        }
    }

    /** Opens a file extracted from a ZIP with the same in-app viewers. */
    private fun openInside(file: File) {
        com.clpstudio.filesmanager.data.SafeStart.start(
            this,
            Intent(this, OpenWithActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setData(FileOps.uriFor(this, file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private suspend fun saveCopy(f: OpenedFile) {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val dir = StoragePaths.downloads().apply { mkdirs() }
                val out = File(dir, uniqueName(f.name, false) { File(dir, it).exists() })
                contentResolver.openInputStream(f.uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
                FileOps.scan(this@OpenWithActivity, listOf(out.absolutePath))
                out.name
            }
        }
        Toast.makeText(this, ok.map { "Saved to Download/$it" }.getOrElse { "Couldn't save (allow storage access in Files first)" }, Toast.LENGTH_LONG).show()
    }
}
