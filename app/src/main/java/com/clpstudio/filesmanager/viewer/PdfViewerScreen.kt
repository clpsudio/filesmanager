package com.clpstudio.filesmanager.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.ExternalFiles
import com.clpstudio.filesmanager.ui.components.EmptyState
import com.clpstudio.filesmanager.ui.glass.glassBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

/** Renders PDF pages on demand (PdfRenderer is single-threaded, so one page at a time). */
class PdfDocument private constructor(private val pfd: ParcelFileDescriptor) : Closeable {
    private val renderer = PdfRenderer(pfd)
    private val mutex = Mutex()
    private val cache = object : LruCache<Int, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    val pageCount: Int get() = renderer.pageCount
    val ratios: List<Float> = (0 until renderer.pageCount).map { i ->
        renderer.openPage(i).use { p -> if (p.height > 0) p.width.toFloat() / p.height else 0.707f }
    }

    suspend fun render(index: Int, width: Int): Bitmap = cache.get(index) ?: mutex.withLock {
        cache.get(index) ?: withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val w = width.coerceIn(200, 1800)
                val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(AColor.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(index, bmp)
                bmp
            }
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }

    companion object {
        /** Opens the file descriptor directly; copies to the cache only if the source isn't seekable. */
        suspend fun open(context: Context, uri: Uri, name: String): PdfDocument = withContext(Dispatchers.IO) {
            val direct = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
            if (direct != null) {
                try {
                    return@withContext PdfDocument(direct)
                } catch (e: SecurityException) {
                    direct.close()
                    throw e
                } catch (e: Exception) {
                    direct.close()
                }
            }
            val copy = ExternalFiles.copyToCache(context, uri, name)
            PdfDocument(ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY))
        }
    }
}

private sealed interface PdfLoad {
    data object Loading : PdfLoad
    data class Ready(val doc: PdfDocument) : PdfLoad
    data class Locked(val message: String) : PdfLoad
    data class Failed(val message: String) : PdfLoad
}

@Composable
fun PdfViewerScreen(uri: Uri, name: String, onBack: () -> Unit, extraActions: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val load by produceState<PdfLoad>(PdfLoad.Loading, uri) {
        value = try {
            PdfLoad.Ready(PdfDocument.open(context, uri, name))
        } catch (e: SecurityException) {
            PdfLoad.Locked("This PDF has a password. Opening password-protected PDFs comes in the next update.")
        } catch (e: Exception) {
            PdfLoad.Failed("This PDF couldn't be opened. It may be damaged.")
        }
    }
    val doc = (load as? PdfLoad.Ready)?.doc
    DisposableEffect(doc) { onDispose { doc?.close() } }
    val zoom = remember { ZoomState(maxScale = 5f) }
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        when (val l = load) {
            PdfLoad.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is PdfLoad.Locked -> EmptyState(Icons.Filled.Lock, "Password-protected PDF", l.message)
            is PdfLoad.Failed -> EmptyState(Icons.Filled.Lock, "Can't open", l.message)
            is PdfLoad.Ready -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val widthPx = with(LocalDensity.current) { (maxWidth - 16.dp).roundToPx() }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 96.dp, bottom = 48.dp, start = 8.dp, end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().zoomable(zoom, verticalScrollOwner = true),
                ) {
                    items(l.doc.pageCount, key = { it }) { index ->
                        val bmp by produceState<Bitmap?>(null, index) { value = runCatching { l.doc.render(index, (widthPx * 1.5f).toInt()) }.getOrNull() }
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(l.doc.ratios[index]).shadow(2.dp, RoundedCornerShape(4.dp))
                                .clip(RoundedCornerShape(4.dp)).background(Color.White),
                        ) {
                            bmp?.let { Image(it.asImageBitmap(), "Page ${index + 1}", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize()) }
                        }
                    }
                }
                val page = listState.firstVisibleItemIndex + 1
                Text(
                    "$page / ${l.doc.pageCount}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)
                        .glassBar(RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
        ViewerTopBar(name, null, onBack, extraActions)
    }
}
