package com.clpstudio.filesmanager.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.BinEntry
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.ui.AppState
import com.clpstudio.filesmanager.ui.components.CloseSelectionButton
import com.clpstudio.filesmanager.ui.components.ConfirmDialog
import com.clpstudio.filesmanager.ui.components.EmptyState
import com.clpstudio.filesmanager.ui.components.FileRow
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.components.SelectionState
import com.clpstudio.filesmanager.ui.components.formatSize
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BinScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val entries by produceState<List<BinEntry>?>(null, tick, AppState.refreshTick) { value = Graph.bin.list() }
    val selection = remember { SelectionState() }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<List<BinEntry>>(emptyList()) }
    val list = entries
    val selected = list.orEmpty().filter { selection.isSelected(it.id) }

    fun restore(items: List<BinEntry>) = scope.launch {
        val n = Graph.bin.restore(items)
        Toast.makeText(context, "Restored $n item" + if (n == 1) "" else "s", Toast.LENGTH_SHORT).show()
        selection.clear(); tick++; AppState.refresh()
    }

    GlassScaffold(
        topBar = {
            if (selection.active) {
                GlassTopBar(
                    title = "${selection.paths.size} selected",
                    navigation = { CloseSelectionButton { selection.clear() } },
                    actions = {
                        IconButton(onClick = { selection.selectAll(list.orEmpty().map { it.id }) }) { Icon(Icons.Filled.SelectAll, "Select all") }
                        IconButton(onClick = { restore(selected) }) { Icon(Icons.Filled.RestoreFromTrash, "Restore") }
                        IconButton(onClick = { confirmDelete = selected }) { Icon(Icons.Filled.DeleteForever, "Delete forever") }
                    },
                )
            } else {
                GlassTopBar(
                    title = "Bin",
                    subtitle = list?.let { "${it.size} items · ${formatSize(it.sumOf { e -> e.size })} · deleted for good after 30 days" },
                    onBack = onBack,
                    actions = { if (!list.isNullOrEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text("Empty") } },
                )
            }
        },
    ) { padding ->
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyState(Icons.Filled.Delete, "Bin is empty", "Deleted files stay here for 30 days.", Modifier.padding(padding))
            else -> LazyColumn(contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 24.dp)) {
                items(list, key = { it.id }) { e ->
                    val item = FileItem(e.dataFile.absolutePath, e.name, e.isDir, e.size, e.deletedAt)
                    FileRow(
                        item = item,
                        selected = selection.isSelected(e.id),
                        selectionMode = selection.active,
                        onClick = { selection.toggle(e.id) },
                        onLongClick = { selection.toggle(e.id) },
                        extraLine = "${e.daysLeft()} days left · from " + e.originalPath.substringBeforeLast('/').substringAfterLast('/'),
                    )
                }
            }
        }
    }
    if (confirmEmpty) {
        ConfirmDialog("Empty the Bin?", "Everything in the Bin is deleted for good. This can't be undone.", "Delete all", destructive = true,
            onConfirm = { scope.launch { Graph.bin.empty(); tick++ } }, onDismiss = { confirmEmpty = false })
    }
    if (confirmDelete.isNotEmpty()) {
        val items = confirmDelete
        ConfirmDialog("Delete forever?", "${items.size} item" + (if (items.size == 1) "" else "s") + " will be deleted for good.", "Delete", destructive = true,
            onConfirm = { scope.launch { Graph.bin.deleteForever(items); selection.clear(); tick++ } }, onDismiss = { confirmDelete = emptyList() })
    }
}
