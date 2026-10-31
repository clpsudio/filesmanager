package com.clpstudio.filesmanager.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import com.clpstudio.filesmanager.data.ArchiveOps
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.FileTypes
import com.clpstudio.filesmanager.data.StoragePaths
import com.clpstudio.filesmanager.ui.components.formatDateTime
import com.clpstudio.filesmanager.ui.components.formatDuration
import com.clpstudio.filesmanager.ui.components.formatSize
import com.clpstudio.filesmanager.ui.components.kindColor
import com.clpstudio.filesmanager.ui.components.kindIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/** What the info sheet is about: a file on storage ([path]) or one opened from another app. */
@Immutable
data class InfoTarget(val uri: Uri, val name: String, val path: String? = null, val mime: String? = null) {
    val kind: FileKind get() = FileTypes.kindOf(name).let { k -> if (k == FileKind.OTHER) FileTypes.kindOfMime(mime) ?: k else k }

    companion object {
        fun of(file: File) = InfoTarget(Uri.fromFile(file), file.name, file.absolutePath, FileTypes.mimeOf(file.name))
    }
}

@Immutable
data class FileDetails(val target: InfoTarget, val rows: List<Pair<String, String>>)

@Immutable
data class SheetAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

object FileDetailsLoader {
    suspend fun load(context: Context, t: InfoTarget): FileDetails = withContext(Dispatchers.IO) {
        val rows = ArrayList<Pair<String, String>>()
        val file = t.path?.let { File(it) }
        val size = file?.length() ?: runCatching {
            context.contentResolver.query(t.uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
        val mime = t.mime ?: FileTypes.mimeOf(t.name)
        rows += "Name" to t.name
        size?.let { rows += "Size" to formatSize(it) + " (" + "%,d".format(it) + " bytes)" }
        rows += "Type" to "${typeLabel(t.kind)} · $mime"
        file?.let { rows += "Modified" to formatDateTime(it.lastModified()) }
        file?.parentFile?.let { rows += "Folder" to StoragePaths.relative(it.absolutePath) }
        file?.let { rows += "Path" to it.absolutePath }
        runCatching { rows += mediaRows(context, t) }
        FileDetails(t, rows)
    }

    private fun typeLabel(k: FileKind) = when (k) {
        FileKind.IMAGE -> "Photo"
        FileKind.VIDEO -> "Video"
        FileKind.AUDIO -> "Audio"
        FileKind.PDF -> "PDF"
        FileKind.TEXT -> "Text"
        FileKind.DOCUMENT -> "Document"
        FileKind.APK -> "Android app"
        FileKind.ARCHIVE -> "Archive"
        FileKind.FONT -> "Font"
        else -> "File"
    }

    private fun mediaRows(context: Context, t: InfoTarget): List<Pair<String, String>> = mediaRowsFor(context, t)

    private fun mediaRowsFor(context: Context, t: InfoTarget): List<Pair<String, String>> { return when (t.kind) {
        FileKind.IMAGE -> {
            val out = ArrayList<Pair<String, String>>()
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(t.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val exif = runCatching { context.contentResolver.openInputStream(t.uri)?.use { ExifInterface(it) } }.getOrNull()
            val rotated = exif?.rotationDegrees == 90 || exif?.rotationDegrees == 270
            if (opts.outWidth > 0) {
                val w = if (rotated) opts.outHeight else opts.outWidth
                val h = if (rotated) opts.outWidth else opts.outHeight
                out += "Resolution" to "$w × $h (${"%.1f".format(w * h / 1_000_000f)} MP)"
            }
            exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { out += "Taken" to it }
            listOfNotNull(exif?.getAttribute(ExifInterface.TAG_MAKE), exif?.getAttribute(ExifInterface.TAG_MODEL)).joinToString(" ").takeIf { it.isNotBlank() }?.let { out += "Camera" to it }
            out
        }
        FileKind.VIDEO, FileKind.AUDIO -> {
            val out = ArrayList<Pair<String, String>>()
            val r = MediaMetadataRetriever()
            try {
                if (t.path != null) r.setDataSource(t.path) else r.setDataSource(context, t.uri)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { out += "Duration" to formatDuration(it) }
                if (t.kind == FileKind.VIDEO) {
                    val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (w != null && h != null) out += "Resolution" to if (rot == 90 || rot == 270) "$h × $w" else "$w × $h"
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()?.let { out += "Frame rate" to "%.0f fps".format(it) }
                } else {
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { out += "Artist" to it }
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { out += "Album" to it }
                }
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { out += "Bitrate" to "${it / 1000} kbps" }
            } finally {
                runCatching { r.release() }
            }
            out
        }
        FileKind.PDF -> listOf("Pages" to try {
            val pfd = context.contentResolver.openFileDescriptor(t.uri, "r") ?: ParcelFileDescriptor.open(File(t.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(pfd).use { it.pageCount.toString() }
        } catch (e: SecurityException) {
            "Password protected"
        })
        FileKind.APK -> {
            val path = t.path ?: return emptyList()
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageArchiveInfo(path, 0) ?: return emptyList()
            listOf("App package" to info.packageName, "App version" to (info.versionName ?: "?"))
        }
        FileKind.ARCHIVE -> {
            val path = t.path ?: return emptyList()
            if (!ArchiveOps.isZip(t.name)) return emptyList()
            ArchiveOps.open(File(path)).use { z ->
                listOf("Items inside" to z.fileHeaders.size.toString(), "Password" to if (z.isEncrypted) "Yes (locked zip)" else "No")
            }
        }
        else -> emptyList()
    } }
}

/** The info sheet's body (also used for screenshots). */
@Composable
fun FileInfoSheetContent(target: InfoTarget, details: FileDetails?, actions: List<SheetAction>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(kindIcon(target.kind), null, tint = kindColor(target.kind), modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Text(target.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            actions.forEach { a ->
                Column(
                    Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = a.onClick).padding(horizontal = 10.dp, vertical = 8.dp).width(64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).then(Modifier), contentAlignment = Alignment.Center) {
                        Icon(a.icon, a.label, tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(a.label, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        val rows = details?.rows ?: listOf("Name" to target.name)
        rows.filter { it.first != "Name" }.forEach { (k, v) ->
            Column(Modifier.padding(vertical = 5.dp)) {
                Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(v, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (details == null) Text("Reading details...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** Slides up from the bottom with the file's details and actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileInfoSheet(target: InfoTarget, actions: List<SheetAction>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val details by produceState<FileDetails?>(null, target) { value = runCatching { FileDetailsLoader.load(context, target) }.getOrNull() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        FileInfoSheetContent(target, details, actions)
    }
}

/**
 * Swipe UP on an open file calls [onSwipeUp]: a vertical upward drag that no child used
 * (not a zoomed photo, not a scrolling page), or an upward overscroll past the end of a page.
 */
fun Modifier.swipeUpToOpen(enabled: Boolean = true, onSwipeUp: () -> Unit): Modifier = composed {
    val threshold = with(LocalDensity.current) { 90.dp.toPx() }
    val latest by rememberUpdatedState(onSwipeUp)
    val latestEnabled by rememberUpdatedState(enabled)
    val connection = remember {
        object : NestedScrollConnection {
            var acc = 0f
            var fired = false
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && latestEnabled) {
                    if (available.y < 0f) {
                        acc += -available.y
                        if (!fired && acc > threshold * 1.4f) {
                            fired = true
                            latest()
                        }
                    } else if (consumed.y != 0f) {
                        acc = 0f
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                acc = 0f
                fired = false
                return Velocity.Zero
            }
        }
    }
    this.nestedScroll(connection).pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var cancelled = false
            var last = down.position
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.changes.count { it.pressed } > 1) cancelled = true
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.pressed && change.isConsumed) cancelled = true // a child (zoom / scroll / pager) took it
                last = change.position
                if (!change.pressed) break
            }
            val dy = last.y - down.position.y
            val dx = last.x - down.position.x
            if (!cancelled && latestEnabled && -dy > threshold && abs(dx) < -dy * 0.6f) latest()
        }
    }
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Full screen: hides the status and navigation bars while [enabled] (swipe from an edge shows them briefly). */
@Composable
fun ImmersiveEffect(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (enabled) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Orientation for viewers: null = follow the phone (sensor when auto-rotate is on). Restored on exit. */
@Composable
fun OrientationEffect(locked: Int?) {
    val context = LocalContext.current
    DisposableEffect(locked) {
        val activity = context.findActivity()
        activity?.requestedOrientation = locked ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}
