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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.clpstudio.filesmanager.data.ArchiveEntry
import com.clpstudio.filesmanager.data.ArchiveFormat
import com.clpstudio.filesmanager.data.ArchiveOps
import com.clpstudio.filesmanager.data.Archives
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.WrongPasswordException
import com.clpstudio.filesmanager.data.uniqueName
import com.clpstudio.filesmanager.ui.AppState
import com.clpstudio.filesmanager.ui.components.CloseSelectionButton
import com.clpstudio.filesmanager.ui.components.EmptyState
import com.clpstudio.filesmanager.ui.components.EnterPasswordDialog
import com.clpstudio.filesmanager.ui.components.FileRow
import com.clpstudio.filesmanager.ui.components.FolderPickerDialog
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.components.MenuRow
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface ZipLoad {
    data object Loading : ZipLoad
    data class Ready(
        val entries: List<ArchiveEntry>,
        val encrypted: Boolean,
        val format: ArchiveFormat = ArchiveFormat.ZIP,
        /** The names themselves are locked (7z with encrypted names): nothing to list until the password. */
        val listLocked: Boolean = false,
    ) : ZipLoad
    data class Failed(val message: String, val title: String = "Can't open this archive") : ZipLoad
}

/**
 * Browse a ZIP or 7z archive (normal or password-protected) without extracting; open any file
 * inside; extract all or selected entries, here or to a folder of your choice, with progress.
 * Any other archive (RAR, tar, ...) that reaches this screen shows the clear "not supported" message.
 */
@Composable
fun ZipViewerScreen(path: String, onBack: () -> Unit, openExtracted: (File) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zipFile = remember(path) { File(path) }
    var dir by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf<String?>(null) }
    var askPassword by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var pickFolderFor by remember { mutableStateOf<List<String>?>(null) }
    var menu by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    /** Entries waiting for "Delete from archive" (archives have no Bin: permanent, after a confirm). */
    var confirmDeleteEntries by remember { mutableStateOf<List<String>?>(null) }

    val probe by produceState<ZipLoad>(ZipLoad.Loading, path, AppState.refreshTick) {
        value = withContext(Dispatchers.IO) {
            try {
                val p = Archives.probe(zipFile)
                ZipLoad.Ready(p.entries, p.encrypted, p.format, p.listLocked)
            } catch (e: Throwable) {
                ZipLoad.Failed(Archives.failureMessage(e), Archives.failureTitle(e))
            }
        }
        if ((value as? ZipLoad.Ready)?.encrypted == true && password == null) askPassword = true
    }
    // A locked name list is read again once the password is known.
    val load by produceState(probe, probe, password) {
        val p = probe
        value = if (p is ZipLoad.Ready && p.listLocked && password != null) {
            withContext(Dispatchers.IO) {
                runCatching { p.copy(entries = Archives.list(zipFile, password!!.toCharArray()), listLocked = false) }
                    .getOrElse { ZipLoad.Failed(Archives.failureMessage(it), Archives.failureTitle(it)) }
            }
        } else p
    }
    val ready = load as? ZipLoad.Ready
    val nodes = remember(ready, dir) { ready?.let { ArchiveOps.children(it.entries, dir) } }

    fun pw(): CharArray? = password?.toCharArray()

    fun extract(paths: List<String>?, target: File, closeAfter: Boolean = false) {
        selected.clear()
        if (closeAfter) onBack()
        AppState.runOperation(
            context, "Extracting",
            followUp = { AppState.DoneAction("Extracted to ${target.name}", "Open folder", target) },
        ) { report ->
            val n = Archives.extract(zipFile, pw(), target, paths = paths, stripPrefix = dir, onProgress = report)
            "Extracted $n file" + (if (n == 1) "" else "s") + " to ${target.name}" to listOf(target)
        }
    }

    fun defaultTarget(): File {
        val parent = zipFile.parentFile!!
        return ArchiveOps.newFolderFor(zipFile, parent)
    }

    BackHandler(selected.isNotEmpty()) { selected.clear() }
    BackHandler(selected.isEmpty() && dir.isNotEmpty()) { dir = dir.substringBeforeLast('/', "") }

    GlassScaffold(
        topBar = {
            if (selected.isNotEmpty()) {
                GlassTopBar(
                    title = "${selected.size} selected",
                    navigation = { CloseSelectionButton { selected.clear() } },
                    actions = {
                        run {
                            val allZ = nodes.orEmpty().map { it.path }
                            com.clpstudio.filesmanager.ui.components.SelectAllToggle(com.clpstudio.filesmanager.data.SelectionLogic.allSelected(selected, allZ)) {
                                val next = com.clpstudio.filesmanager.data.SelectionLogic.toggleAll(selected.toList(), allZ)
                                selected.clear(); selected.addAll(next)
                            }
                        }
                        IconButton(onClick = { extract(selected.toList(), if (dir.isEmpty()) defaultTarget() else defaultTarget()) }) { Icon(Icons.Filled.Unarchive, "Extract") }
                        IconButton(onClick = { pickFolderFor = selected.toList() }) { Icon(Icons.Filled.DriveFolderUpload, "Extract to...") }
                        // No Delete at all inside an APK / app bundle: its files are read-only (view, open, extract).
                        val entriesNow = ready?.entries.orEmpty()
                        if (ArchiveOps.showDeleteInside(zipFile, entriesNow)) IconButton(onClick = {
                            val why = ArchiveOps.deleteInsideBlocker(zipFile, ready?.format ?: Archives.formatOfName(zipFile.name), entriesNow)
                            if (why != null) Toast.makeText(context, why, Toast.LENGTH_LONG).show() else confirmDeleteEntries = selected.toList()
                        }) { Icon(Icons.Filled.Delete, "Delete") }
                    },
                )
            } else {
                GlassTopBar(
                    title = zipFile.name,
                    subtitle = Archives.subtitle(ready?.format ?: Archives.formatOfName(zipFile.name), ready?.encrypted == true) + if (dir.isEmpty()) "" else " · $dir",
                    onBack = onBack,
                    actions = {
                        if (ready?.encrypted == true) Icon(Icons.Filled.Lock, "Password protected", Modifier.padding(end = 4.dp))
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(menu, { menu = false }) {
                                MenuRow(Icons.Filled.Unarchive, "Extract all here") { menu = false; extract(null, defaultTarget()) }
                                MenuRow(Icons.Filled.DriveFolderUpload, "Extract all to...") { menu = false; pickFolderFor = emptyList() }
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        val list = nodes
        when {
            load is ZipLoad.Failed -> EmptyState(Icons.Filled.FolderZip, (load as ZipLoad.Failed).title, (load as ZipLoad.Failed).message, Modifier.padding(padding))
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ready?.encrypted == true && password == null -> EmptyState(Icons.Filled.Lock, "Password-protected " + ready.format.label, "Enter the password to open the files inside.", Modifier.padding(padding))
            list.isEmpty() -> EmptyState(Icons.Filled.FolderZip, "Empty folder", modifier = Modifier.padding(padding))
            else -> LazyColumn(contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 24.dp)) {
                items(list, key = { it.path }) { node ->
                    val item = remember(node) { FileItem(path + "!/" + node.path, node.name, node.isDir, node.size, node.time) }
                    FileRow(item, node.path in selected, selected.isNotEmpty(), onClick = {
                        when {
                            selected.isNotEmpty() -> if (!selected.remove(node.path)) selected.add(node.path)
                            node.isDir -> dir = node.path
                            else -> scope.launch {
                                val base = File(context.cacheDir, "zip")
                                val out = File(base, node.path)
                                if (!out.canonicalPath.startsWith(base.canonicalPath + File.separator)) return@launch
                                val r = runCatching { Archives.extractEntry(zipFile, pw(), node.path, out) }
                                r.onSuccess { f ->
                                    // A binary XML from an APK (manifest, res/…xml) opens as readable XML.
                                    val shown = withContext(Dispatchers.IO) { com.clpstudio.filesmanager.data.apk.ApkInspector.readable(context, zipFile, f) }
                                    openExtracted(shown)
                                }.onFailure { e ->
                                    if (e is WrongPasswordException) { password = null; passwordError = "Wrong password"; askPassword = true }
                                    else Toast.makeText(context, "Couldn't open this file", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }, onLongClick = { if (!selected.remove(node.path)) selected.add(node.path) })
                }
            }
        }
    }

    if (askPassword) {
        // Locked ZIP: the password, then (default) extract everything into a new folder next to it,
        // or "View contents" to browse inside.
        fun check(typed: String, then: () -> Unit) {
            checking = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { Archives.checkPassword(zipFile, typed.toCharArray()) }.getOrDefault(false) }
                checking = false
                if (ok) {
                    password = typed
                    passwordError = null
                    askPassword = false
                    then()
                } else {
                    passwordError = "Wrong password, try again"
                }
            }
        }
        EnterPasswordDialog(
            fileName = zipFile.name,
            error = passwordError,
            confirm = "Extract",
            checking = checking,
            secondary = if (ready != null && password == null) "View contents" else null,
            onSecondary = { typed -> check(typed) {} },
            onConfirm = { typed ->
                check(typed) {
                    // A wrong password found later while browsing re-asks here; then just browse on.
                    if (ready != null) extract(null, defaultTarget(), closeAfter = true)
                }
            },
            onDismiss = {
                askPassword = false
                if (password == null) onBack()
            },
        )
    }
    pickFolderFor?.let { paths ->
        FolderPickerDialog(
            start = zipFile.parentFile ?: File("/"),
            title = "Extract to",
            confirm = "Extract here",
            onPick = { dest -> pickFolderFor = null; extract(paths.ifEmpty { null }, dest) },
            onDismiss = { pickFolderFor = null },
        )
    }
    confirmDeleteEntries?.let { paths ->
        com.clpstudio.filesmanager.ui.components.ConfirmDialog(
            title = if (paths.size == 1) "Delete ${paths[0].substringAfterLast('/')} from ${zipFile.name}?" else "Delete ${paths.size} items from ${zipFile.name}?",
            text = "They're removed from the archive for good (archives have no Bin). This can't be undone.",
            confirm = "Delete",
            destructive = true,
            onConfirm = {
                selected.clear()
                // Belt and braces: never rewrite an app package.
                if (!ArchiveOps.isAppPackage(zipFile, ready?.entries.orEmpty())) AppState.runOperation(context, "Deleting from archive") {
                    val n = withContext(Dispatchers.IO) { ArchiveOps.removeEntries(zipFile, paths) }
                    "Deleted $n item" + (if (n == 1) "" else "s") + " from ${zipFile.name}" to listOf(zipFile)
                }
            },
            onDismiss = { confirmDeleteEntries = null },
        )
    }
}
