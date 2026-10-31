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
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.clpstudio.filesmanager.data.PdfSecurity
import com.clpstudio.filesmanager.ui.components.EnterPasswordDialog
import com.clpstudio.filesmanager.ui.components.MenuRow
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File


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
    data class Ready(val doc: PdfDocument, val unlockedCopy: File? = null) : PdfLoad
    data object Locked : PdfLoad
    data class Failed(val message: String) : PdfLoad
}

/** The grey (light) / dark desk the white paper pages sit on, as in Charan's reference. */
@Composable
fun paperDeskColor(): Color = if (MaterialTheme.colorScheme.background.luminanceIsDark()) Color(0xFF1B1D22) else Color(0xFFEDEFF2)

private fun Color.luminanceIsDark(): Boolean = (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f

/** One white paper page with a thin outline and a soft shadow. */
fun Modifier.paperPage(): Modifier = this
    .shadow(3.dp, RoundedCornerShape(3.dp))
    .clip(RoundedCornerShape(3.dp))
    .background(Color.White)
    .border(1.dp, Color(0xFFD5D9E0), RoundedCornerShape(3.dp))

/** The small page counter at the bottom right ("1/12"). */
@Composable
fun PageCounter(current: Int, total: Int, modifier: Modifier = Modifier) {
    Text(
        "$current/$total",
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier.navigationBarsPadding().padding(14.dp)
            .glassBar(RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/**
 * PDF pages as separate white paper pages, pinch zoom, vertical scroll, page counter.
 * A password-protected PDF asks for its password; the pages are then read from a private
 * decrypted temp copy that is deleted when the viewer closes.
 */
@Composable
fun PdfContent(
    uri: Uri,
    name: String,
    modifier: Modifier = Modifier,
    topPadding: androidx.compose.ui.unit.Dp = 96.dp,
    onLockedChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    var password by remember(uri) { mutableStateOf<String?>(null) }
    var passwordError by remember(uri) { mutableStateOf<String?>(null) }
    var askPassword by remember(uri) { mutableStateOf(false) }
    val load by produceState<PdfLoad>(PdfLoad.Loading, uri, password) {
        val pw = password
        value = try {
            if (pw == null) {
                PdfLoad.Ready(PdfDocument.open(context, uri, name))
            } else {
                withContext(Dispatchers.IO) {
                    PdfSecurity.init(context)
                    val src = if (uri.scheme == "file") File(uri.path!!) else ExternalFiles.copyToCache(context, uri, name)
                    val dir = File(context.cacheDir, "pdf_unlocked").apply { mkdirs() }
                    val out = File(dir, "${System.nanoTime()}.pdf")
                    PdfSecurity.decrypt(src, pw, out)
                    PdfLoad.Ready(PdfDocument.open(context, Uri.fromFile(out), name), out)
                }
            }
        } catch (e: SecurityException) {
            PdfLoad.Locked
        } catch (e: InvalidPasswordException) {
            passwordError = "Wrong password, try again"
            PdfLoad.Locked
        } catch (e: Throwable) { // incl. OutOfMemoryError on huge PDFs: show the message, never crash
            PdfLoad.Failed("This PDF couldn't be opened. It may be damaged.")
        }
        if (value is PdfLoad.Locked) askPassword = true
    }
    LaunchedEffect(load) { onLockedChanged(load is PdfLoad.Locked) }
    val ready = load as? PdfLoad.Ready
    DisposableEffect(ready) {
        onDispose {
            ready?.doc?.close()
            ready?.unlockedCopy?.delete()
        }
    }
    val zoom = remember { ZoomState(maxScale = 5f) }
    val listState = rememberLazyListState()

    Box(modifier.fillMaxSize().background(paperDeskColor())) {
        when (val l = load) {
            PdfLoad.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            PdfLoad.Locked -> EmptyState(Icons.Filled.Lock, "Password-protected PDF", "Enter the password to open it.")
            is PdfLoad.Failed -> EmptyState(Icons.Filled.Lock, "Can't open", l.message)
            is PdfLoad.Ready -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val widthPx = with(LocalDensity.current) { (maxWidth - 24.dp).roundToPx() }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = topPadding, bottom = 64.dp, start = 12.dp, end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize().zoomable(zoom, verticalScrollOwner = true),
                ) {
                    items(l.doc.pageCount, key = { it }) { index ->
                        val bmp by produceState<Bitmap?>(null, index) { value = runCatching { l.doc.render(index, (widthPx * 1.5f).toInt()) }.getOrNull() }
                        Box(Modifier.fillMaxWidth().aspectRatio(l.doc.ratios[index]).paperPage()) {
                            bmp?.let { Image(it.asImageBitmap(), "Page ${index + 1}", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize()) }
                        }
                    }
                }
                PageCounter(listState.firstVisibleItemIndex + 1, l.doc.pageCount, Modifier.align(Alignment.BottomEnd))
            }
        }
    }
    if (askPassword) {
        EnterPasswordDialog(
            fileName = name,
            error = passwordError,
            onConfirm = { askPassword = false; password = it },
            onDismiss = { askPassword = false },
        )
    }
}

/** Standalone PDF viewer (files from other apps). */
@Composable
fun PdfViewerScreen(uri: Uri, name: String, onBack: () -> Unit, extraActions: @Composable () -> Unit = {}) {
    var info by remember { mutableStateOf(false) }
    val target = remember(uri, name) { InfoTarget(uri, name, uri.takeIf { it.scheme == "file" }?.path, "application/pdf") }
    Box(Modifier.fillMaxSize()) {
        PdfContent(uri, name, Modifier.swipeUpToOpen { info = true }.navigationBarsPadding().padding(bottom = 64.dp))
        ViewerTopBar(name, null, onBack, titleColor = MaterialTheme.colorScheme.primary) {
            IconButton(onClick = { info = true }) { Icon(Icons.Filled.Info, "File info") }
            extraActions()
        }
        UriViewerActionPanel(uri, "application/pdf", onInfo = { info = true }, onDeleted = onBack, modifier = Modifier.align(Alignment.BottomCenter))
    }
    if (info) FileInfoSheet(target, emptyList()) { info = false }
}
