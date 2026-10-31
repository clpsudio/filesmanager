package com.clpstudio.filesmanager.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.FileTypes
import com.clpstudio.filesmanager.data.OpenedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Photos and videos another app hands us (Samsung Camera's preview, "Open with"), item 28 / 29:
 *  - the real file found on storage -> the same gallery pager as inside the app, over that folder's photos and videos;
 *  - stream only (secure camera on the lock screen, a chat app's private file) -> the photo decoded from the stream
 *    (retrying while the camera is still saving), or the video in the same gallery-style player.
 */
object ExternalMedia {
    /** The pager's pages for a photo / video on storage: its folder's photos and videos, newest first, like the gallery. */
    fun siblings(file: File, showHidden: Boolean = false): List<String> {
        val dir = file.parentFile ?: return listOf(file.absolutePath)
        val all = dir.listFiles { f ->
            f.isFile && (showHidden || !f.name.startsWith(".")) && FileTypes.kindOf(f.name).let { it == FileKind.IMAGE || it == FileKind.VIDEO }
        }?.sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })?.map { it.absolutePath }.orEmpty()
        return if (file.absolutePath in all) all else listOf(file.absolutePath) + all
    }

    /** True when [f] can open in the pager exactly like a photo / video on storage. */
    fun opensInPager(f: OpenedFile): Boolean =
        (f.kind == FileKind.IMAGE || f.kind == FileKind.VIDEO) && f.path?.let { File(it) }?.let { it.isFile && it.canRead() } == true &&
            FileTypes.kindOf(File(f.path).name) == f.kind
}

/** Decodes a photo from a file or any uri: sized for the screen, EXIF rotation applied (JPEG, HEIC). */
object ForeignBitmap {
    fun decode(context: Context, uri: Uri, path: String?, maxSide: Int = 3072): Bitmap? {
        val bytes = runCatching {
            path?.let { File(it) }?.takeIf { it.isFile && it.length() > 0 }?.readBytes()
                ?: context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val degrees = runCatching { ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees }.getOrDefault(0)
        if (degrees == 0) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return runCatching { Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true) }.getOrDefault(bmp)
    }
}

private sealed interface Loaded {
    data object Loading : Loaded
    data class Ok(val bitmap: Bitmap) : Loaded
    data object Failed : Loaded
}

/**
 * A photo that never shows as a black screen: a spinner while it loads (retrying for ~3 s while the camera is still
 * writing it), the photo, or a message with Try again.
 */
@Composable
fun RobustImage(uri: Uri, path: String?, zoom: ZoomState, onTap: () -> Unit) {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    val state by produceState<Loaded>(Loaded.Loading, uri, path, attempt) {
        value = Loaded.Loading
        var waited = 0L
        while (true) {
            val bmp = withContext(Dispatchers.IO) { ForeignBitmap.decode(context, uri, path) }
            if (bmp != null) { value = Loaded.Ok(bmp); return@produceState }
            if (waited >= com.clpstudio.filesmanager.data.ExternalFiles.PENDING_WAIT_MS) break
            delay(300)
            waited += 300
        }
        value = Loaded.Failed
    }
    Box(Modifier.fillMaxSize().zoomable(zoom, onTap), contentAlignment = Alignment.Center) {
        when (val s = state) {
            Loaded.Loading -> CircularProgressIndicator(color = Color.White)
            is Loaded.Ok -> Image(s.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().testTag("photo"))
            Loaded.Failed -> Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't show this photo", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "The app that sent it may still be saving it, or it doesn't allow reading it.",
                    color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                )
                TextButton(onClick = { attempt++ }) { Text("Try again") }
            }
        }
    }
}

/**
 * A video from another app that isn't a file we can reach (item 29): the same gallery-style player as the pager
 * (centre buttons, timeline, 1x / loop row, Share / Info bar, gestures), right in this screen, from the uri.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun ForeignVideoScreen(f: OpenedFile, onBack: () -> Unit, extraActions: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val item = remember(f.uri) { FileItem(f.uri.toString(), f.name, false, f.size.coerceAtLeast(0L), 0L, FileKind.VIDEO) }
    val video = remember { PagerVideo(context) }
    DisposableEffect(video) { onDispose { video.closeAll(context); video.release() } }
    var chrome by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf(false) }
    val pill = remember { LevelPillState() }
    val dismiss = remember { DismissDragState() }
    LaunchedEffect(item.path) {
        video.onNext = { video.restart() }
        video.load(item, runCatching { Graph.settings.value }.getOrDefault(com.clpstudio.filesmanager.data.AppSettings()))
    }
    LaunchedEffect(item.path) { while (true) { video.tick(); delay(250) } }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                val act = context.findActivity()
                // Closing the screen or the small window stops it (item 33); otherwise it waits paused.
                when (VideoClose.onStop(
                    changingConfig = act?.isChangingConfigurations == true, finishing = act?.isFinishing == true,
                    pipNow = VideoPip.inPip, pipSinceResume = VideoPip.pipSinceResume,
                    playing = video.playing, hasVideo = true, backgroundPlay = false, // shared videos never hand off
                    screenOff = VideoPip.screenOff(context),
                )) {
                    VideoClose.OnStop.CLOSE -> video.closeAll(context)
                    VideoClose.OnStop.PAUSE, VideoClose.OnStop.HAND_OFF -> video.pause()
                    VideoClose.OnStop.NOTHING -> Unit
                }
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    VideoPipEffect(video, isVideo = true, hasPrevNext = false, onNext = {}, onPrevious = {})
    LaunchedEffect(VideoPip.inPip) { chrome = !VideoPip.inPip }
    ImmersiveEffect(!chrome)
    ResetBrightnessOnExit()
    val gestures = remember {
        object : VideoPageGestureCallbacks {
            override fun onPageDrag(dx: Float) {}
            override fun onPageEnd(dx: Float, velocityPxS: Float) {}
            override fun onInfo() { info = true }
            override fun onDismiss() = onBack()
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        InlineVideoPage(item, isCurrent = true, video = video, onTap = { chrome = !chrome }, gestures = gestures, pill = pill, dismiss = dismiss)
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
            ViewerTopBar(f.name, null, onBack) { extraActions() }
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            VideoCenterButtons(
                playing = video.playing, hasPrev = false, hasNext = false,
                onPrevious = {}, onPlayPause = { video.playPause() }, onNext = {}, showPrevNext = false,
            )
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            VideoBottomBlock(
                items = listOf(item), current = 0, onSelect = {},
                favorite = null, onFavorite = null, onDelete = null,
                onShare = { com.clpstudio.filesmanager.data.SafeStart.shareForeign(context, f.uri, f.mime, f.name) },
                onInfo = { info = true },
                video = {
                    VideoSeekRow(video.position, video.duration, onSeek = { ms, _ -> video.seek(ms) })
                    VideoControlRow(
                        muted = video.muted, speed = video.speed, loop = video.loop, fill = video.fill, loopHint = video.loopHint,
                        onMute = video::toggleMute, onSpeed = video::changeSpeed,
                        onLoop = { video.changeLoop(!video.loop) }, onFit = { video.fill = !video.fill },
                    )
                },
            )
        }
        LevelPillHost(pill)
    }
    if (info) FileInfoSheet(InfoTarget(f.uri, f.name, null, f.mime), emptyList()) { info = false }
}
