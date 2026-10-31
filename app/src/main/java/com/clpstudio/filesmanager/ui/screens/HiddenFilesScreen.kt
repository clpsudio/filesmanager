package com.clpstudio.filesmanager.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.HiddenScan
import com.clpstudio.filesmanager.data.FileView
import com.clpstudio.filesmanager.ui.Opener
import com.clpstudio.filesmanager.ui.components.CloseSelectionButton
import com.clpstudio.filesmanager.ui.components.EmptyState
import com.clpstudio.filesmanager.ui.components.FileCollection
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.components.SelectionState
import com.clpstudio.filesmanager.ui.components.rememberLiveFiles
import com.clpstudio.filesmanager.ui.components.swipeTabs
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import kotlinx.coroutines.launch

/** The type chips of the Hidden files screen. */
enum class HiddenChip(val label: String) {
    ALL("All"), FOLDERS("Folders"), IMAGES("Images"), VIDEOS("Videos"), AUDIO("Audio"), DOCUMENTS("Documents"), OTHER("Other");

    fun matches(item: FileItem): Boolean = when (this) {
        ALL -> true
        FOLDERS -> item.isDir
        IMAGES -> !item.isDir && item.kind == FileKind.IMAGE
        VIDEOS -> !item.isDir && item.kind == FileKind.VIDEO
        AUDIO -> !item.isDir && item.kind == FileKind.AUDIO
        DOCUMENTS -> !item.isDir && item.kind in setOf(FileKind.PDF, FileKind.DOCUMENT, FileKind.TEXT)
        OTHER -> !item.isDir && item.kind !in setOf(FileKind.IMAGE, FileKind.VIDEO, FileKind.AUDIO, FileKind.PDF, FileKind.DOCUMENT, FileKind.TEXT)
    }
}

/** Every hidden file and folder in one place. Files has no Unhide: select and Delete permanently (one or many). */
@Composable
fun HiddenFilesScreen(opener: Opener, onBack: () -> Unit, onFolder: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by HiddenScan.state.collectAsState()
    LaunchedEffect(Unit) { if (st.items == null) HiddenScan.refresh(context.applicationContext) }
    val all = rememberLiveFiles(st.items)
    var chip by rememberSaveable { mutableStateOf(HiddenChip.ALL) }
    val shown = remember(all, chip) { all?.filter { chip.matches(it) } }
    val selection = remember { SelectionState() }
    BackHandler(selection.active) { selection.clear() }
    // Delete here = permanent (hidden files skip the Bin), after a confirm that says it can't be undone.
    val actions = com.clpstudio.filesmanager.ui.components.rememberFileActions(afterCopyOrMove = {})
    GlassScaffold(
        topBar = {
            val chips: @Composable () -> Unit = {
                Column {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        items(HiddenChip.entries.toList()) { c ->
                            val n = all?.count { c.matches(it) } ?: 0
                            FilterChip(
                                selected = c == chip, onClick = { chip = c },
                                label = { Text("${c.label} $n", fontWeight = if (c == chip) FontWeight.SemiBold else FontWeight.Normal) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = Color.White),
                            )
                        }
                    }
                    if (st.scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                }
            }
            if (selection.active) {
                GlassTopBar(
                    title = "${selection.paths.size} selected",
                    navigation = { CloseSelectionButton { selection.clear() } },
                    actions = {
                        val allP = shown.orEmpty().map { it.path }
                        com.clpstudio.filesmanager.ui.components.SelectAllToggle(com.clpstudio.filesmanager.data.SelectionLogic.allSelected(selection.paths, allP)) {
                            selection.selectAll(com.clpstudio.filesmanager.data.SelectionLogic.toggleAll(selection.paths, allP))
                        }
                        IconButton(onClick = { actions.deleteTargets = shown.orEmpty().filter { selection.isSelected(it.path) } }) {
                            Icon(Icons.Filled.DeleteForever, "Delete permanently", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    below = chips,
                )
            } else {
                GlassTopBar(
                    title = "Hidden files",
                    subtitle = when {
                        st.scanning -> "Searching storage... ${st.scannedFolders} folders"
                        all != null -> "${all.size} hidden · long-press to select, then Delete permanently"
                        else -> null
                    },
                    onBack = onBack,
                    actions = { IconButton(onClick = { scope.launch { HiddenScan.refresh(context.applicationContext) } }, enabled = !st.scanning) { Icon(Icons.Filled.Refresh, "Refresh") } },
                    below = chips,
                )
            }
        },
    ) { padding ->
        val idx = HiddenChip.entries.indexOf(chip)
        Box(Modifier.fillMaxSize().swipeTabs(idx, HiddenChip.entries.size, enabled = !selection.active) { chip = HiddenChip.entries[it] }) {
            val list = shown
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> EmptyState(Icons.Filled.VisibilityOff, if (st.scanning) "Searching..." else "No hidden files", modifier = Modifier.padding(padding))
                else -> FileCollection(
                    items = list, view = FileView.LIST, selection = selection,
                    onClick = { item ->
                        when {
                            selection.active -> selection.toggle(item.path)
                            item.isDir -> onFolder(item.path)
                            else -> opener.open(item, list.filter { !it.isDir })
                        }
                    },
                    contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                )
            }
        }
    }
    com.clpstudio.filesmanager.ui.components.FileActionDialogs(actions, onDeleted = { selection.clear() })
}
