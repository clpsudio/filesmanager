package com.clpstudio.filesmanager.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileOpException
import com.clpstudio.filesmanager.data.FileOps
import com.clpstudio.filesmanager.data.FileTypes
import com.clpstudio.filesmanager.data.StoragePaths
import com.clpstudio.filesmanager.ui.AppState
import com.clpstudio.filesmanager.ui.ClipboardState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Stable
class SelectionState {
    val paths = mutableStateListOf<String>()
    val active: Boolean get() = paths.isNotEmpty()
    fun isSelected(path: String) = path in paths
    fun toggle(path: String) {
        if (!paths.remove(path)) paths.add(path)
    }
    fun clear() = paths.clear()
    fun selectAll(all: List<String>) {
        paths.clear()
        paths.addAll(all)
    }
}

@Stable
class FileActions(private val context: Context, private val afterCopyOrMove: () -> Unit) {
    var renameTarget by mutableStateOf<FileItem?>(null)
    var detailsTarget by mutableStateOf<FileItem?>(null)
    var deleteTargets by mutableStateOf<List<FileItem>>(emptyList())

    fun share(items: List<FileItem>) = FileOps.share(context, items.map { File(it.path) })
    fun copy(items: List<FileItem>) {
        AppState.clipboard = ClipboardState(items.map { it.path }, move = false)
        afterCopyOrMove()
    }
    fun move(items: List<FileItem>) {
        AppState.clipboard = ClipboardState(items.map { it.path }, move = true)
        afterCopyOrMove()
    }
    fun openWith(item: FileItem) {
        val f = File(item.path)
        FileOps.openWithOtherApp(context, FileOps.uriFor(context, f), FileTypes.mimeOf(f.name))
    }
}

@Composable
fun rememberFileActions(afterCopyOrMove: () -> Unit): FileActions {
    val context = LocalContext.current
    return remember { FileActions(context, afterCopyOrMove) }
}

/** The dialogs that file actions open (rename, delete to Bin, details). */
@Composable
fun FileActionDialogs(actions: FileActions, onDeleted: () -> Unit = {}) {
    val context = LocalContext.current
    actions.renameTarget?.let { item ->
        TextInputDialog(
            title = "Rename",
            initial = item.name,
            confirm = "Rename",
            selectBaseName = !item.isDir,
            onConfirm = { name ->
                try {
                    FileOps.rename(context, File(item.path), name)
                    AppState.refresh()
                    null
                } catch (e: FileOpException) {
                    e.message
                }
            },
            onDismiss = { actions.renameTarget = null },
        )
    }
    if (actions.deleteTargets.isNotEmpty()) {
        val items = actions.deleteTargets
        ConfirmDialog(
            title = if (items.size == 1) "Delete ${items[0].name}?" else "Delete ${items.size} items?",
            text = "They go to the Bin and are deleted for good after 30 days. You can restore them from the Bin until then.",
            confirm = "Move to Bin",
            destructive = true,
            onConfirm = { AppState.moveToBin(context, items.map { File(it.path) }, onDeleted) },
            onDismiss = { actions.deleteTargets = emptyList() },
        )
    }
    actions.detailsTarget?.let { item ->
        val size by produceState<Long?>(if (item.isDir) null else item.size, item.path) {
            if (item.isDir) value = withContext(Dispatchers.IO) { FileOps.sizeOf(File(item.path)) }
        }
        InfoDialog(
            title = item.name,
            rows = listOf(
                "Location" to StoragePaths.relative(item.parentPath),
                "Size" to (size?.let { formatSize(it) } ?: "Counting..."),
                "Modified" to formatDateTime(item.modified),
                "Type" to if (item.isDir) "Folder" else FileTypes.mimeOf(item.name),
            ),
            onDismiss = { actions.detailsTarget = null },
        )
    }
}

/** Top-bar actions while files are selected. */
@Composable
fun SelectionActionsRow(
    selected: List<FileItem>,
    actions: FileActions,
    onSelectAll: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    IconButton(onClick = onSelectAll) { Icon(Icons.Filled.SelectAll, "Select all") }
    IconButton(onClick = { actions.share(selected) }) { Icon(Icons.Filled.Share, "Share") }
    IconButton(onClick = { actions.deleteTargets = selected }) { Icon(Icons.Filled.Delete, "Delete") }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MenuRow(Icons.Filled.ContentCopy, "Copy to...") { menu = false; actions.copy(selected) }
            MenuRow(Icons.AutoMirrored.Filled.DriveFileMove, "Move to...") { menu = false; actions.move(selected) }
            if (selected.size == 1) {
                MenuRow(Icons.Filled.DriveFileRenameOutline, "Rename") { menu = false; actions.renameTarget = selected[0] }
                MenuRow(Icons.Filled.Info, "Details") { menu = false; actions.detailsTarget = selected[0] }
                if (!selected[0].isDir) {
                    MenuRow(Icons.AutoMirrored.Filled.OpenInNew, "Open with another app") { menu = false; actions.openWith(selected[0]) }
                }
            }
        }
    }
}

@Composable
fun CloseSelectionButton(onClose: () -> Unit) {
    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cancel selection") }
}
