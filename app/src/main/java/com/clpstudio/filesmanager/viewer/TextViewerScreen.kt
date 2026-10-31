package com.clpstudio.filesmanager.viewer

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clpstudio.filesmanager.ui.components.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_BYTES = 4 * 1024 * 1024

/** Text / code / CSV / JSON / XML / HTML (shown as source). */
@Composable
fun TextViewerScreen(uri: Uri, name: String, onBack: () -> Unit, extraActions: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    var wrap by rememberSaveable { mutableStateOf(true) }
    val lines by produceState<Result<Pair<List<String>, Boolean>>?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
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
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val r = lines
        when {
            r == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            r.isFailure -> EmptyState(Icons.Filled.Description, "Can't read this file")
            else -> {
                val (list, truncated) = r.getOrThrow()
                val hScroll = rememberScrollState()
                SelectionContainer {
                    LazyColumn(
                        contentPadding = PaddingValues(top = 100.dp, bottom = 40.dp, start = 4.dp, end = 12.dp),
                        modifier = Modifier.fillMaxSize().then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll)),
                    ) {
                        itemsIndexed(list) { i, line ->
                            Row {
                                Text(
                                    "${i + 1}", modifier = Modifier.width(44.dp).padding(end = 8.dp), textAlign = TextAlign.End,
                                    color = MaterialTheme.colorScheme.outline, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                )
                                Text(line, fontFamily = FontFamily.Monospace, fontSize = 13.sp, softWrap = wrap)
                            }
                        }
                        if (truncated) item {
                            Text("Showing the first 4 MB.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        ViewerTopBar(name, null, onBack) {
            IconButton(onClick = { wrap = !wrap }) { Icon(Icons.AutoMirrored.Filled.WrapText, if (wrap) "No wrap" else "Wrap lines") }
            extraActions()
        }
    }
}
