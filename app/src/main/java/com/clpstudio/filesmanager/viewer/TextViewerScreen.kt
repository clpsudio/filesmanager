package com.clpstudio.filesmanager.viewer

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clpstudio.filesmanager.ui.components.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_BYTES = 4 * 1024 * 1024
/** Lines per "page" of the paper layout. */
const val TEXT_LINES_PER_PAGE = 60

/** Reads up to 4 MB of text. Second = true when the file was longer. */
suspend fun readTextLines(context: android.content.Context, uri: Uri): Pair<List<String>, Boolean> = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)!!.use { input ->
        val buf = ByteArray(MAX_BYTES)
        var n = 0
        while (n < MAX_BYTES) {
            val r = input.read(buf, n, MAX_BYTES - n)
            if (r < 0) break
            n += r
        }
        val truncated = n == MAX_BYTES && input.read() >= 0
        String(buf, 0, n, Charsets.UTF_8).replace("\r\n", "\n").split('\n') to truncated
    }
}

/**
 * Text / code / JSON / XML / CSV on white paper pages (Charan's reference layout): readable
 * serif text, wrapped, real content unchanged; page counter at the bottom right.
 */
@Composable
fun TextContent(uri: Uri, modifier: Modifier = Modifier, topPadding: Dp = 100.dp, wrap: Boolean = true) {
    val context = LocalContext.current
    val lines by produceState<Result<Pair<List<String>, Boolean>>?>(null, uri) { value = runCatching { readTextLines(context, uri) } }
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().background(paperDeskColor())) {
        val r = lines
        when {
            r == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            r.isFailure -> EmptyState(Icons.Filled.Description, "Can't read this file")
            else -> {
                val (list, truncated) = r.getOrThrow()
                val pages = (list.size + TEXT_LINES_PER_PAGE - 1) / TEXT_LINES_PER_PAGE
                val hScroll = rememberScrollState()
                val edge = Color(0xFFD5D9E0)
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = topPadding, bottom = 64.dp, start = 12.dp, end = 12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(list) { i, line ->
                        val first = i % TEXT_LINES_PER_PAGE == 0
                        val last = i % TEXT_LINES_PER_PAGE == TEXT_LINES_PER_PAGE - 1 || i == list.size - 1
                        Box(
                            Modifier.fillMaxWidth()
                                .padding(top = if (first && i > 0) 14.dp else 0.dp)
                                .background(Color.White)
                                .drawBehind {
                                    val w = 1.dp.toPx()
                                    drawLine(edge, Offset(0f, 0f), Offset(0f, size.height), w)
                                    drawLine(edge, Offset(size.width, 0f), Offset(size.width, size.height), w)
                                    if (first) drawLine(edge, Offset(0f, 0f), Offset(size.width, 0f), w)
                                    if (last) drawLine(edge, Offset(0f, size.height), Offset(size.width, size.height), w)
                                }
                                .padding(start = 22.dp, end = 22.dp, top = if (first) 22.dp else 0.dp, bottom = if (last) 22.dp else 0.dp)
                                .then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll)),
                        ) {
                            Text(line.ifEmpty { " " }, fontFamily = FontFamily.Serif, fontSize = 13.5.sp, lineHeight = 19.sp, color = Color(0xFF1F2328), softWrap = wrap)
                        }
                    }
                    if (truncated) item {
                        Text("Showing the first 4 MB.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary)
                    }
                }
                val page = listState.firstVisibleItemIndex / TEXT_LINES_PER_PAGE + 1
                PageCounter(page.coerceAtMost(pages.coerceAtLeast(1)), pages.coerceAtLeast(1), Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

/** Standalone text viewer (files from other apps). */
@Composable
fun TextViewerScreen(uri: Uri, name: String, onBack: () -> Unit, extraActions: @Composable () -> Unit = {}) {
    var wrap by rememberSaveable { mutableStateOf(true) }
    var info by remember { mutableStateOf(false) }
    val target = remember(uri, name) { InfoTarget(uri, name, uri.takeIf { it.scheme == "file" }?.path) }
    Box(Modifier.fillMaxSize()) {
        TextContent(uri, Modifier.swipeUpToOpen { info = true }, wrap = wrap)
        ViewerTopBar(name, null, onBack, titleColor = MaterialTheme.colorScheme.primary) {
            IconButton(onClick = { wrap = !wrap }) { Icon(Icons.AutoMirrored.Filled.WrapText, if (wrap) "No wrap" else "Wrap lines") }
            IconButton(onClick = { info = true }) { Icon(Icons.Filled.Info, "File info") }
            extraActions()
        }
    }
    if (info) FileInfoSheet(target, emptyList()) { info = false }
}
