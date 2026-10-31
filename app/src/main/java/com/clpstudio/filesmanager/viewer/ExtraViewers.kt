package com.clpstudio.filesmanager.viewer

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clpstudio.filesmanager.data.ExternalFiles
import com.clpstudio.filesmanager.ui.components.EmptyState
import com.clpstudio.filesmanager.ui.glass.glassPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object HexDump {
    /** "00000010  48 65 6c 6c 6f ...  |Hello...|" lines, 16 bytes each. */
    fun lines(bytes: ByteArray, length: Int = bytes.size): List<String> {
        val out = ArrayList<String>((length + 15) / 16)
        var off = 0
        val sb = StringBuilder()
        while (off < length) {
            sb.setLength(0)
            sb.append(String.format(Locale.US, "%08x  ", off))
            val end = minOf(off + 16, length)
            for (i in off until off + 16) {
                if (i < end) sb.append(String.format(Locale.US, "%02x ", bytes[i].toInt() and 0xFF)) else sb.append("   ")
                if (i == off + 7) sb.append(' ')
            }
            sb.append(" |")
            for (i in off until end) {
                val c = bytes[i].toInt() and 0xFF
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            sb.append('|')
            out += sb.toString()
            off = end
        }
        return out
    }

    /** True when the first bytes look like text (UTF-8, few control characters). */
    fun looksLikeText(bytes: ByteArray, length: Int = bytes.size): Boolean {
        if (length == 0) return true
        var bad = 0
        for (i in 0 until length) {
            val c = bytes[i].toInt() and 0xFF
            if (c == 0) return false
            if (c < 9 || (c in 14..31)) bad++
        }
        return bad * 50 < length
    }
}

private const val HEX_LIMIT = 1024 * 1024

/** Any binary file: offset, hex and ASCII (first 1 MB). */
@Composable
fun HexViewerScreen(uri: Uri, name: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val lines by produceState<Result<List<String>>?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    val buf = ByteArray(HEX_LIMIT)
                    var n = 0
                    while (n < HEX_LIMIT) {
                        val r = input.read(buf, n, HEX_LIMIT - n)
                        if (r < 0) break
                        n += r
                    }
                    HexDump.lines(buf, n)
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val l = lines
        when {
            l == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            l.isFailure -> EmptyState(Icons.Filled.FontDownload, "Can't read this file")
            else -> LazyColumn(contentPadding = PaddingValues(top = 100.dp, bottom = 40.dp, start = 8.dp, end = 8.dp)) {
                items(l.getOrThrow()) { line -> Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, softWrap = false) }
            }
        }
        ViewerTopBar(name, "Hex view (first 1 MB)", onBack)
    }
}

/** TTF / OTF: the font's own letters in a few sizes. */
@Composable
fun FontPreviewScreen(uri: Uri, name: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val font by produceState<Result<FontFamily>?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = localFile(context, uri, name)
                FontFamily(androidx.compose.ui.text.font.Typeface(Typeface.createFromFile(file)))
            }
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val f = font
        when {
            f == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            f.isFailure -> EmptyState(Icons.Filled.FontDownload, "This font couldn't be read")
            else -> LazyColumn(
                contentPadding = PaddingValues(top = 110.dp, bottom = 40.dp, start = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val family = f.getOrThrow()
                item {
                    Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(24.dp)).padding(18.dp)) {
                        Text("Aa Bb Cc", fontFamily = family, fontSize = 56.sp)
                        Text("ABCDEFGHIJKLMNOPQRSTUVWXYZ", fontFamily = family, fontSize = 20.sp)
                        Text("abcdefghijklmnopqrstuvwxyz", fontFamily = family, fontSize = 20.sp)
                        Text("0123456789 !@#₹%&*()", fontFamily = family, fontSize = 20.sp)
                    }
                }
                items(listOf(12, 16, 24, 32, 48)) { size ->
                    Text("The quick brown fox jumps over the lazy dog", fontFamily = family, fontSize = size.sp)
                }
            }
        }
        ViewerTopBar(name, "Font preview", onBack)
    }
}

private suspend fun localFile(context: Context, uri: Uri, name: String): File =
    if (uri.scheme == "file") File(uri.path!!) else ExternalFiles.copyToCache(context, uri, name)
