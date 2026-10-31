package com.clpstudio.filesmanager.viewer

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileOps
import com.clpstudio.filesmanager.data.uniqueName
import com.clpstudio.filesmanager.ui.AppState
import com.clpstudio.filesmanager.ui.components.EmptyState
import com.clpstudio.filesmanager.ui.components.FileRow
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class ZipNode(val path: String, val name: String, val isDir: Boolean, val size: Long, val time: Long)

object ZipBrowser {
    /** Direct children of [dir] ("" = root) inside the archive, folders first. */
    fun list(zip: ZipFile, dir: String): List<ZipNode> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val out = LinkedHashMap<String, ZipNode>()
        for (e in zip.entries()) {
            val name = e.name.trimEnd('/')
            if (!name.startsWith(prefix) || name == dir) continue
            val rest = name.removePrefix(prefix)
            val first = rest.substringBefore('/')
            val isDir = rest.contains('/') || e.isDirectory
            val path = prefix + first
            if (!out.containsKey(path)) out[path] = ZipNode(path, first, isDir, if (isDir) 0 else e.size, e.time)
        }
        return out.values.sortedWith(compareBy<ZipNode> { !it.isDir }.thenBy { it.name.lowercase() })
    }

    fun extractEntry(zip: ZipFile, entry: ZipEntry, out: File) {
        out.parentFile?.mkdirs()
        zip.getInputStream(entry).use { i -> out.outputStream().use { i.copyTo(it) } }
    }

    /** Extracts everything into [target], refusing paths that escape it ("zip slip"). */
    fun extractAll(zip: ZipFile, target: File) {
        val root = target.canonicalPath + File.separator
        for (e in zip.entries()) {
            val out = File(target, e.name)
            if (!(out.canonicalPath + if (e.isDirectory) File.separator else "").startsWith(root)) continue
            if (e.isDirectory) out.mkdirs() else extractEntry(zip, e, out)
        }
    }
}

/** Browse a ZIP without extracting; open any file inside; extract all. */
@Composable
fun ZipViewerScreen(path: String, onBack: () -> Unit, openExtracted: (File) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dir by rememberSaveable { mutableStateOf("") }
    val zip by produceState<Result<ZipFile>?>(null, path) {
        value = withContext(Dispatchers.IO) { runCatching { ZipFile(path) } }
    }
    val nodes by produceState<List<ZipNode>?>(null, zip, dir) {
        value = zip?.getOrNull()?.let { z -> withContext(Dispatchers.IO) { runCatching { ZipBrowser.list(z, dir) }.getOrNull() } }
    }
    BackHandler(dir.isNotEmpty()) { dir = dir.substringBeforeLast('/', "") }
    val name = File(path).name
    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = name, subtitle = if (dir.isEmpty()) "ZIP archive" else dir, onBack = onBack,
                actions = {
                    IconButton(onClick = {
                        val z = zip?.getOrNull() ?: return@IconButton
                        scope.launch {
                            val parent = File(path).parentFile!!
                            val folder = File(parent, uniqueName(File(path).nameWithoutExtension, true) { File(parent, it).exists() })
                            val ok = withContext(Dispatchers.IO) { runCatching { ZipBrowser.extractAll(z, folder); FileOps.scan(context, listOf(folder.absolutePath)) }.isSuccess }
                            Toast.makeText(context, if (ok) "Extracted to ${folder.name}" else "Couldn't extract (password-protected ZIPs come in the next update)", Toast.LENGTH_LONG).show()
                            AppState.refresh()
                        }
                    }) { Icon(Icons.Filled.Unarchive, "Extract all") }
                },
            )
        },
    ) { padding ->
        val z = zip
        val list = nodes
        when {
            z == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            z.isFailure -> EmptyState(Icons.Filled.FolderZip, "Can't open this archive", "It may be damaged or use an unsupported format.", Modifier.padding(padding))
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyState(Icons.Filled.FolderZip, "Empty folder", modifier = Modifier.padding(padding))
            else -> LazyColumn(contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 24.dp)) {
                items(list, key = { it.path }) { node ->
                    val item = remember(node) { FileItem(path + "!/" + node.path, node.name, node.isDir, node.size, node.time) }
                    FileRow(item, false, false, onClick = {
                        if (node.isDir) {
                            dir = node.path
                        } else {
                            val zf = z.getOrNull() ?: return@FileRow
                            scope.launch {
                                val base = File(context.cacheDir, "zip")
                                val out = File(base, node.path)
                                val ok = withContext(Dispatchers.IO) {
                                    out.canonicalPath.startsWith(base.canonicalPath + File.separator) && runCatching { ZipBrowser.extractEntry(zf, zf.getEntry(node.path) ?: zf.getEntry(node.path + "/"), out) }.isSuccess
                                }
                                if (ok) openExtracted(out) else Toast.makeText(context, "Couldn't open this file", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }, onLongClick = {})
                }
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(zip) { onDispose { zip?.getOrNull()?.close() } }
}
