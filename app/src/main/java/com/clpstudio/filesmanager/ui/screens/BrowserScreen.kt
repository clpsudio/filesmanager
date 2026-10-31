package com.clpstudio.filesmanager.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import com.clpstudio.filesmanager.data.Diagnostics
import com.clpstudio.filesmanager.data.FolderWatcher
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.DateQueryParser
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileOpException
import com.clpstudio.filesmanager.data.FileOps
import com.clpstudio.filesmanager.data.StoragePaths
import com.clpstudio.filesmanager.data.sortItems
import com.clpstudio.filesmanager.ui.AppState
import com.clpstudio.filesmanager.ui.Opener
import com.clpstudio.filesmanager.ui.components.CloseSelectionButton
import com.clpstudio.filesmanager.ui.components.EmptyState
import com.clpstudio.filesmanager.ui.components.FileActionDialogs
import com.clpstudio.filesmanager.ui.components.FileRow
import com.clpstudio.filesmanager.ui.components.FileCollection
import com.clpstudio.filesmanager.ui.components.ViewSortRow
import com.clpstudio.filesmanager.ui.components.PlaySelectedButton
import com.clpstudio.filesmanager.ui.components.PlayAllButton
import com.clpstudio.filesmanager.ui.components.formatSize
import com.clpstudio.filesmanager.data.SortBy
import com.clpstudio.filesmanager.ui.components.FileTile
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.components.SelectionActionsRow
import com.clpstudio.filesmanager.ui.components.SelectionState
import com.clpstudio.filesmanager.ui.components.SortMenu
import com.clpstudio.filesmanager.ui.components.TextInputDialog
import com.clpstudio.filesmanager.ui.components.rememberFileActions
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.glass.glassBar
import com.clpstudio.filesmanager.ui.components.SearchField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Full storage browser: folders, multi-select, copy/move/rename/delete/share, sort, filter. */
@Composable
fun BrowserScreen(startPath: String?, opener: Opener, onBack: () -> Unit, onSearchAll: (String) -> Unit) {
    val context = LocalContext.current
    val volumes = remember { StoragePaths.volumes(context) }
    val home = startPath ?: volumes.first().path
    var path by rememberSaveable { mutableStateOf(home) }
    val settings by Graph.settings.collectAsState()
    val selection = remember { SelectionState() }
    var filter by rememberSaveable { mutableStateOf("") }
    var filtering by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val actions = rememberFileActions(afterCopyOrMove = { selection.clear() }, afterAction = { selection.clear() })
    val scrollMemory = remember { mutableStateMapOf<String, Int>() }

    val root = volumes.firstOrNull { path == it.path || path.startsWith(it.path + "/") }?.path ?: "/"

    // Reload when something changes inside the open folder (another app, a download...).
    var folderTick by remember { mutableIntStateOf(0) }
    DisposableEffect(path) {
        val watcher = FolderWatcher(File(path)) { folderTick++ }
        watcher.start()
        onDispose { watcher.stop() }
    }
    val listing by produceState<List<FileItem>?>(null, path, settings.showHidden, settings.sortBy, settings.sortAscending, AppState.refreshTick, folderTick) {
        value = withContext(Dispatchers.IO) {
            val f = Graph.hiddenFilter(settings.showHidden)
            val dir = File(path)
            val raw = dir.listFiles() ?: run {
                Diagnostics.log("browse", "can't list ${dir.path} (exists=${dir.exists()}, readable=${dir.canRead()})")
                emptyArray()
            }
            val entries = raw.filter { f.visibleEntry(it) }.map { FileItem.of(it, countChildren = true) }
            sortItems(entries, settings.sortBy, settings.sortAscending)
        }
    }
    val items = remember(listing, filter) {
        listing.orEmpty().filter { DateQueryParser.nameMatches(it.name, filter) }
    }
    val selectedItems = items.filter { selection.isSelected(it.path) }
    var view by rememberSaveable(settings.fileView) { mutableStateOf(settings.fileView) }

    fun goTo(p: String) {
        selection.clear()
        filter = ""
        path = p
    }

    BackHandler(enabled = selection.active) { selection.clear() }
    BackHandler(enabled = !selection.active && filtering) { filtering = false; filter = "" }
    BackHandler(enabled = !selection.active && !filtering && path != root && path != home) {
        goTo(File(path).parent ?: root)
    }

    GlassScaffold(
        topBar = {
            if (selection.active) {
                GlassTopBar(
                    title = "${selection.paths.size} selected",
                    navigation = { CloseSelectionButton { selection.clear() } },
                    actions = {
                        PlaySelectedButton(selectedItems, opener)
                        SelectionActionsRow(selectedItems, actions) { selection.selectAll(items.map { it.path }) }
                    },
                )
            } else {
                GlassTopBar(
                    title = if (path == root) volumes.firstOrNull { it.path == root }?.label ?: "Storage" else File(path).name,
                    onBack = onBack,
                    actions = {
                        PlayAllButton(items, opener)
                        IconButton(onClick = { filtering = !filtering; if (!filtering) filter = "" }) { Icon(Icons.Filled.Search, "Search") }
                        IconButton(onClick = { scope.launch { Graph.settingsRepo.setShowHidden(!settings.showHidden) } }) {
                            Icon(if (settings.showHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, "Show hidden files")
                        }
                    },
                    below = {
                        Breadcrumbs(path = path, root = root, rootLabel = volumes.firstOrNull { it.path == root }?.label ?: "Storage", onPick = ::goTo)
                        if (volumes.size > 1) {
                            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(volumes, key = { it.path }) { v ->
                                    FilterChip(selected = root == v.path, onClick = { goTo(v.path) }, label = { Text(v.label) })
                                }
                            }
                        }
                        listing?.let { l ->
                            val files = items.count { !it.isDir }
                            val folders = items.size - files
                            ViewSortRow(
                                summary = listOfNotNull(
                                    folders.takeIf { it > 0 }?.let { "$it folder" + if (it == 1) "" else "s" },
                                    files.takeIf { it > 0 }?.let { "$it file" + if (it == 1) "" else "s" },
                                ).joinToString(", ").ifEmpty { "Empty" } + (if (files > 0) " · " + formatSize(items.sumOf { it.size }) else ""),
                                view = view, onView = { view = it },
                                sortBy = settings.sortBy, ascending = settings.sortAscending,
                                onSort = { s2, a -> scope.launch { Graph.settingsRepo.setSort(s2, a) } },
                            )
                        }
                        AnimatedVisibility(filtering) {
                            SearchField(
                                value = filter, onValueChange = { filter = it },
                                placeholder = "Filter this folder",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                autoFocus = true,
                                trailing = {
                                    TextButton(onClick = { onSearchAll(path) }) { Text("Everywhere") }
                                },
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            val clip = AppState.clipboard
            AnimatedVisibility(clip != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                if (clip != null) {
                    Row(
                        Modifier.fillMaxWidth().glassBar().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            (if (clip.move) "Move " else "Copy ") + "${clip.count} item" + (if (clip.count == 1) "" else "s") + " here?",
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = { AppState.clipboard = null }) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { AppState.paste(context, File(path)) }) {
                            Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (clip.move) "Move here" else "Paste here")
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!selection.active && AppState.clipboard == null) {
                ExtendedFloatingActionButton(
                    onClick = { newFolder = true },
                    icon = { Icon(Icons.Filled.CreateNewFolder, null) },
                    text = { Text("New folder") },
                    shape = RoundedCornerShape(20.dp),
                )
            }
        },
    ) { padding ->
        val current = listing
        when {
            current == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            items.isEmpty() -> EmptyState(
                Icons.Filled.FolderOpen,
                if (filter.isNotEmpty()) "No matches here" else "This folder is empty",
                if (!settings.showHidden && filter.isEmpty()) "Hidden files are off (Settings)" else null,
                Modifier.padding(padding),
            )
            else -> Crossfade(targetState = path, label = "folder") { shownPath ->
                val onClick: (FileItem) -> Unit = { item ->
                    if (selection.active) {
                        selection.toggle(item.path)
                    } else if (item.isDir) {
                        goTo(item.path)
                    } else {
                        opener.open(item, items)
                    }
                }
                FileCollection(
                    items = items, view = view, selection = selection, onClick = onClick,
                    contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 96.dp),
                    sections = settings.sortBy == SortBy.DATE && !settings.sortAscending,
                    initialIndex = scrollMemory[shownPath] ?: 0,
                    onFirstVisible = { scrollMemory[shownPath] = it },
                )
            }
        }
    }

    FileActionDialogs(actions, onDeleted = { selection.clear() })
    if (newFolder) {
        TextInputDialog(
            title = "New folder", initial = "New folder", confirm = "Create", selectBaseName = false,
            onConfirm = { name ->
                try {
                    FileOps.createFolder(File(path), name)
                    AppState.refresh()
                    null
                } catch (e: FileOpException) {
                    e.message
                }
            },
            onDismiss = { newFolder = false },
        )
    }
}

@Composable
private fun Breadcrumbs(path: String, root: String, rootLabel: String, onPick: (String) -> Unit) {
    val parts = remember(path, root) {
        val rel = path.removePrefix(root).trim('/')
        val list = mutableListOf(rootLabel to root)
        var acc = root
        if (rel.isNotEmpty()) rel.split('/').forEach { seg ->
            acc = "$acc/$seg"
            list += seg to acc
        }
        list
    }
    val state = rememberLazyListState()
    LaunchedEffect(parts.size) { state.animateScrollToItem((parts.size - 1).coerceAtLeast(0)) }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        itemsIndexed(parts, key = { _, p -> p.second }) { i, (label, p) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { onPick(p) },
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    border = null,
                )
                if (i < parts.size - 1) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
