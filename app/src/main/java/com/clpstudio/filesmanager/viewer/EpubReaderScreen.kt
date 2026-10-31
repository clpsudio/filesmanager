package com.clpstudio.filesmanager.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.clpstudio.filesmanager.data.Epub
import com.clpstudio.filesmanager.data.EpubBook
import com.clpstudio.filesmanager.ui.components.EmptyState
import com.clpstudio.filesmanager.ui.glass.glassBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

/** Where the reader stopped in each book, and the text size (SharedPreferences, offline). */
object EpubPositions {
    private const val PREFS = "epub_reader"

    data class Position(val chapter: Int, val fraction: Float)

    private fun key(file: File) = "pos:" + file.absolutePath + ":" + file.length()

    fun get(context: Context, file: File): Position? {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(file), null) ?: return null
        return parse(v)
    }

    fun set(context: Context, file: File, p: Position) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key(file), format(p)).apply()
    }

    fun format(p: Position) = "${p.chapter}:${p.fraction}"
    fun parse(v: String): Position? {
        val c = v.substringBefore(':').toIntOrNull() ?: return null
        val f = v.substringAfter(':', "0").toFloatOrNull() ?: 0f
        return Position(c.coerceAtLeast(0), f.coerceIn(0f, 1f))
    }

    fun textZoom(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("zoom", 100)
    fun setTextZoom(context: Context, z: Int) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("zoom", z).apply()
    fun night(context: Context, default: Boolean): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("night", default)
    fun setNight(context: Context, n: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("night", n).apply()
}

/**
 * Offline WebView plumbing for one book: chapters, pictures, CSS and fonts are served from the EPUB itself under
 * https://appassets.androidplatform.net/epub/…; every other request is answered empty (no network, JavaScript off).
 */
class EpubAssets(private val book: EpubBook, private val night: () -> Boolean) {
    private val zip = ZipFile(book.file)

    fun urlOf(path: String, fragment: String? = null): String = BASE + path.split('/').joinToString("/") { Uri.encode(it) } + (fragment?.let { "#$it" } ?: "")

    /** The zip path a reader URL points at, or null for anything outside the book. */
    fun pathOf(url: String): String? {
        if (!url.startsWith(BASE)) return null
        return Uri.decode(url.removePrefix(BASE).substringBefore('#').substringBefore('?'))
    }

    fun respond(url: String): WebResourceResponse {
        val path = pathOf(url) ?: return empty()
        val entry = zip.getEntry(path) ?: return empty()
        val mime = book.mediaTypeOf(path)
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        return if (mime.contains("html")) {
            val html = Epub.styled(String(bytes, Charsets.UTF_8), night())
            WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
        } else {
            WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
        }
    }

    fun close() = runCatching { zip.close() }

    private fun empty() = WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    companion object {
        const val BASE = "https://appassets.androidplatform.net/epub/"
    }
}

/** EPUB reader: chapters in reading order, table of contents, text size, day / night, remembers where you stopped. */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EpubReaderScreen(file: File, onBack: () -> Unit) {
    val context = LocalContext.current
    val loaded by produceState<Result<EpubBook>?>(null, file.path) {
        value = withContext(Dispatchers.IO) { runCatching { Epub.open(file) } }
    }
    val res = loaded
    if (res == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val book = res.getOrNull()
    if (book == null || book.spine.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            ViewerTopBar(file.name, "EPUB", onBack)
            EmptyState(
                androidx.compose.material.icons.Icons.AutoMirrored.Filled.List,
                "This book can't be opened",
                res.exceptionOrNull()?.message ?: "It has no chapters.",
            )
        }
        return
    }
    val systemNight = MaterialTheme.colorScheme.background.luminance() < 0.5f
    var night by remember { mutableStateOf(EpubPositions.night(context, systemNight)) }
    var zoom by remember { mutableIntStateOf(EpubPositions.textZoom(context)) }
    val start = remember { EpubPositions.get(context, file) }
    var chapter by remember { mutableIntStateOf((start?.chapter ?: 0).coerceIn(0, book.spine.size - 1)) }
    var restoreFraction by remember { mutableStateOf(start?.fraction ?: 0f) }
    var pendingFragment by remember { mutableStateOf<String?>(null) }
    var showToc by remember { mutableStateOf(false) }
    val nightNow = remember { arrayOf(night) }
    nightNow[0] = night
    val assets = remember(book) { EpubAssets(book) { nightNow[0] } }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun saveNow() {
        val w = webView ?: return
        val total = (w.contentHeight * w.resources.displayMetrics.density) - w.height
        val f = if (total > 0) (w.scrollY / total).coerceIn(0f, 1f) else 0f
        EpubPositions.set(context, file, EpubPositions.Position(chapter, f))
    }

    fun go(to: Int, fraction: Float = 0f, fragment: String? = null) {
        if (to !in book.spine.indices) return
        saveNow()
        chapter = to
        restoreFraction = fraction
        pendingFragment = fragment
    }

    DisposableEffect(book) {
        onDispose {
            saveNow()
            assets.close()
            webView?.destroy()
        }
    }

    Column(Modifier.fillMaxSize()) {
        ViewerTopBar(book.title, book.author ?: "Chapter ${chapter + 1} of ${book.spine.size}", onBack, titleColor = MaterialTheme.colorScheme.primary) {
            IconButton(onClick = { showToc = true }) { Icon(Icons.AutoMirrored.Filled.List, "Contents") }
            IconButton(onClick = { zoom = (zoom - 10).coerceAtLeast(60); EpubPositions.setTextZoom(context, zoom) }) { Icon(Icons.Filled.TextDecrease, "Smaller text") }
            IconButton(onClick = { zoom = (zoom + 10).coerceAtMost(220); EpubPositions.setTextZoom(context, zoom) }) { Icon(Icons.Filled.TextIncrease, "Bigger text") }
            IconButton(onClick = { saveNow(); night = !night; EpubPositions.setNight(context, night); restoreFraction = EpubPositions.get(context, file)?.fraction ?: 0f; webView?.reload() }) {
                Icon(if (night) Icons.Filled.LightMode else Icons.Filled.DarkMode, if (night) "Day mode" else "Night mode")
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = false
                        settings.blockNetworkLoads = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        settings.builtInZoomControls = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                                runCatching { assets.respond(request.url.toString()) }.getOrElse { assets.respond("") }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url.toString()
                                val path = assets.pathOf(url) ?: return true // outside links: offline, ignore
                                val i = book.spineIndexOf(path)
                                if (i >= 0 && i != chapter) { go(i, 0f, url.substringAfter('#', "").ifEmpty { null }); return true }
                                return false
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                val f = restoreFraction
                                if (f > 0f) view.postDelayed({
                                    val total = view.contentHeight * view.resources.displayMetrics.density - view.height
                                    if (total > 0) view.scrollTo(0, (total * f).toInt())
                                }, 120)
                                restoreFraction = 0f
                            }
                        }
                        webView = this
                    }
                },
                update = { w ->
                    w.settings.textZoom = zoom
                    w.setBackgroundColor(if (night) 0xFF121212.toInt() else 0xFFFFFFFF.toInt())
                    val url = assets.urlOf(book.spine[chapter], pendingFragment)
                    if (w.tag != url) {
                        w.tag = url
                        w.loadUrl(url)
                    }
                },
            )
        }
        Row(
            Modifier.fillMaxWidth().glassBar().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { go(chapter - 1) }, enabled = chapter > 0) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous chapter") }
            Text(
                book.toc.lastOrNull { book.spineIndexOf(it.path) == chapter }?.title ?: "Chapter ${chapter + 1}",
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            Text("${chapter + 1} / ${book.spine.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { go(chapter + 1) }, enabled = chapter < book.spine.size - 1) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next chapter") }
        }
    }

    LaunchedEffect(chapter) { EpubPositions.set(context, file, EpubPositions.Position(chapter, restoreFraction)) }

    if (showToc) {
        ModalBottomSheet(onDismissRequest = { showToc = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Text("Contents", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            val current = book.toc.indexOfLast { book.spineIndexOf(it.path) == chapter }.coerceAtLeast(0)
            LazyColumn(state = rememberLazyListState(current), modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                itemsIndexed(book.toc) { i, e ->
                    val isHere = i == current
                    Text(
                        e.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isHere) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isHere) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clickable {
                            showToc = false
                            val idx = book.spineIndexOf(e.path)
                            if (idx >= 0) go(idx, 0f, e.fragment)
                        }.padding(start = (20 + 16 * e.depth.coerceAtMost(4)).dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }
}

/** The book's cover (thumbnails), decoded small. */
fun epubCover(file: File, maxPx: Int = 256): Bitmap? = runCatching {
    val book = Epub.open(file)
    val bytes = book.coverPath?.let { Epub.read(file, it) } ?: return null
    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
    var s = 1
    while (b.outWidth / (s * 2) >= maxPx && b.outHeight / (s * 2) >= maxPx) s *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
}.getOrNull()
