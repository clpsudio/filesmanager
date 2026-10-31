package com.clpstudio.filesmanager.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.DesignPreview
import com.clpstudio.filesmanager.data.FileOps
import com.clpstudio.filesmanager.data.FileTypes
import com.clpstudio.filesmanager.ui.components.formatDate
import com.clpstudio.filesmanager.ui.components.formatSize
import com.clpstudio.filesmanager.ui.glass.glassPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Turns a [DesignPreview.Result] into a bitmap (Android side). */
object DesignBitmaps {
    class Loaded(val bitmap: Bitmap?, val result: DesignPreview.Result)

    fun load(file: File, name: String = file.name, maxPx: Int = 2048, thumbnail: Boolean = false): Loaded {
        val result = DesignPreview.read(file, name, maxDim = maxPx, preferThumbnail = thumbnail)
        var bmp = runCatching { result.image?.let { toBitmap(it, maxPx) } }.getOrNull()
        var res = result
        // An Illustrator PDF that PdfRenderer refuses (damaged / locked): try the XMP thumbnail instead.
        if (bmp == null && result.image is DesignPreview.Image.PdfPage) {
            val thumb = runCatching { DesignPreview.Ai.xmpThumbnail(file) }.getOrNull()
            bmp = thumb?.let { runCatching { toBitmap(DesignPreview.Image.Encoded(it), maxPx) }.getOrNull() }
            res = result.copy(image = null, note = if (bmp != null) "Embedded thumbnail" else "The PDF part of this file couldn't be drawn")
        } else if (bmp == null && result.image != null) {
            res = result.copy(image = null, note = "The preview inside this file couldn't be decoded")
        }
        return Loaded(bmp, res)
    }

    fun toBitmap(image: DesignPreview.Image, maxPx: Int): Bitmap? = when (image) {
        is DesignPreview.Image.Encoded -> {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxPx || bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            if (bmp != null && image.swapRedBlue) swapRedBlue(bmp) else bmp
        }
        is DesignPreview.Image.Pixels -> Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)
        is DesignPreview.Image.PdfPage -> ParcelFileDescriptor.open(image.file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                if (r.pageCount == 0) null else r.openPage(0).use { page ->
                    val scale = maxPx.toFloat() / maxOf(page.width, page.height)
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }

    private fun swapRedBlue(src: Bitmap): Bitmap {
        val px = IntArray(src.width * src.height)
        src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
        for (i in px.indices) {
            val c = px[i]
            px[i] = (c and 0xFF00FF00.toInt()) or ((c and 0xFF) shl 16) or ((c shr 16) and 0xFF)
        }
        return Bitmap.createBitmap(px, src.width, src.height, Bitmap.Config.ARGB_8888)
    }
}

/**
 * A PSD / AI / CDR / DWG page: the preview (pinch to zoom) with a caption, or a clean "no preview" card with the file's
 * details and "Open with another app" when the file carries no preview we can draw offline.
 */
@Composable
fun DesignPage(file: File, name: String, zoom: ZoomState, modifier: Modifier = Modifier, topPadding: Dp = 100.dp, onTap: () -> Unit = {}) {
    val state by produceState<DesignBitmaps.Loaded?>(null, file.path, file.lastModified()) {
        value = withContext(Dispatchers.IO) { DesignBitmaps.load(file, name) }
    }
    val loaded = state
    when {
        loaded == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        loaded.bitmap != null -> Box(modifier.fillMaxSize()) {
            val img = remember(loaded.bitmap) { loaded.bitmap.asImageBitmap() }
            Box(Modifier.fillMaxSize().zoomable(zoom, onTap), contentAlignment = Alignment.Center) {
                Image(img, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            val caption = listOfNotNull(
                FileTypes.extension(name).uppercase(),
                loaded.result.details.firstOrNull { it.first == "Dimensions" }?.second,
                loaded.result.note,
            ).joinToString(" · ")
            Text(
                caption, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 76.dp)
                    .glassPanel(RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        else -> NoPreviewCard(file, name, loaded.result, modifier, topPadding)
    }
}

/** The "no preview" card: what the file is, its details, why there is no picture, and Open with. */
@Composable
fun NoPreviewCard(file: File, name: String, result: DesignPreview.Result, modifier: Modifier = Modifier, topPadding: Dp = 100.dp) {
    val context = LocalContext.current
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = topPadding, bottom = 90.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(28.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Brush, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(result.format, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            val rows = buildList {
                add("Size" to formatSize(file.length()))
                add("Modified" to formatDate(file.lastModified()))
                addAll(result.details)
            }
            Spacer(Modifier.height(4.dp))
            rows.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
                    Text(v, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.6f))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("No preview available offline", style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            Text(result.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            OutlinedButton(onClick = {
                FileOps.openWithOtherApp(context, FileOps.uriFor(context, file), FileTypes.mimeOf(name))
            }, modifier = Modifier.fillMaxWidth()) { Text("Open with another app") }
        }
    }
}

/** Full screen (files opened from another app): top bar + the design page. */
@Composable
fun DesignViewerScreen(file: File, name: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    val zoom = remember { ZoomState() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        DesignPage(file, name, zoom)
        ViewerTopBar(name, DesignPreview.formatName(name), onBack, titleColor = MaterialTheme.colorScheme.primary) { actions() }
    }
}
