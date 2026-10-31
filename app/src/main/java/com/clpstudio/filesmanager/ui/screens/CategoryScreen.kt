package com.clpstudio.filesmanager.ui.screens

import androidx.activity.compose.BackHandler
import com.clpstudio.filesmanager.data.DefaultView
import com.clpstudio.filesmanager.data.Diagnostics
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.Category
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.FolderGroup
import com.clpstudio.filesmanager.data.SortBy
import com.clpstudio.filesmanager.data.groupByFolder
import com.clpstudio.filesmanager.data.sortItems
import com.clpstudio.filesmanager.ui.AppState
import com.clpstudio.filesmanager.ui.Opener
import com.clpstudio.filesmanager.ui.components.CloseSelectionButton
import com.clpstudio.filesmanager.ui.components.EmptyState
import com.clpstudio.filesmanager.ui.components.FileActionDialogs
import com.clpstudio.filesmanager.ui.components.FileRow
import com.clpstudio.filesmanager.ui.components.FileThumb
import com.clpstudio.filesmanager.ui.components.FileTile
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.components.SelectionActionsRow
import com.clpstudio.filesmanager.ui.components.SelectionState
import com.clpstudio.filesmanager.ui.components.SortMenu
import com.clpstudio.filesmanager.ui.components.formatSize
import com.clpstudio.filesmanager.ui.components.rememberFileActions
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.glass.glassPanel
import com.clpstudio.filesmanager.ui.glass.pressScale
import kotlinx.coroutines.launch

/** Last loaded list per category, so going into a folder and back is instant. */
object CategoryCache {
    private val lists = HashMap<Category, List<FileItem>>()
    operator fun get(c: Category) = lists[c]
    operator fun set(c: Category, v: List<FileItem>) { lists[c] = v }
}

val Category.typeFilter: String?
    get() = when (this) {
        Category.PHOTOS -> "PHOTOS"
        Category.VIDEOS -> "VIDEOS"
        Category.AUDIO -> "AUDIO"
        Category.DOCUMENTS -> "DOCS"
        else -> null
    }

private val Category.gridFiles: Boolean get() = this == Category.PHOTOS || this == Category.VIDEOS

/** The category list, streamed: null = still loading (spinner, never "empty" before the first result). */
@Composable
private fun rememberCategoryItems(cat: Category): Pair<List<FileItem>?, Boolean> {
    val settings by Graph.settings.collectAsState()
    var loading by remember(cat) { mutableStateOf(true) }
    val items by produceState(CategoryCache[cat]?.takeIf { it.isNotEmpty() }, cat, settings.showHidden, AppState.refreshTick) {
        loading = true
        try {
            Graph.media.categoryFlow(cat, Graph.hiddenFilter(settings.showHidden)).collect { load ->
                // Partial empty results keep the spinner; only a finished load may say "empty".
                if (load.items.isNotEmpty() || load.done) {
                    value = load.items
                    if (load.items.isNotEmpty()) CategoryCache[cat] = load.items
                }
                loading = !load.done
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.log("category", "${cat.title} failed to load", e)
            if (value == null) value = emptyList()
            loading = false
        }
    }
    return items to loading
}

@Composable
fun CategoryScreen(
    cat: Category,
    opener: Opener,
    onBack: () -> Unit,
    onFolder: (String) -> Unit,
    onSearch: () -> Unit,
    onPaste: () -> Unit,
) {
    val (items, loading) = rememberCategoryItems(cat)
    // 0 = Folders, 1 = All files. Opens on the view chosen in Settings (All files by default).
    var tab by rememberSaveable { mutableStateOf(if (Graph.settings.value.defaultView == DefaultView.FOLDERS) 0 else 1) }
    val groups = remember(items) { groupByFolder(items.orEmpty()) }
    if (tab == 0) {
        GlassScaffold(
            topBar = {
                GlassTopBar(
                    title = cat.title,
                    subtitle = items?.let { "${it.size} files in ${groups.size} folders" + if (loading) " · searching..." else "" },
                    onBack = onBack,
                    actions = { IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Search") } },
                    below = { CategoryTabs(tab) { tab = it } },
                )
            },
        ) { padding ->
            when {
                items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                groups.isEmpty() -> EmptyState(Icons.Filled.FolderOpen, "Nothing here yet", modifier = Modifier.padding(padding))
                else -> FolderGroupsGrid(groups, padding, onFolder)
            }
        }
    } else {
        FileListScreen(
            title = cat.title,
            subtitle = items?.let { "${it.size} files" + if (loading) " · searching..." else "" },
            items = items,
            grid = cat.gridFiles,
            opener = opener,
            onBack = onBack,
            onSearch = onSearch,
            onPaste = onPaste,
            tabs = { CategoryTabs(tab) { tab = it } },
        )
    }
}

@Composable
private fun CategoryTabs(selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        listOf("Folders", "All files").forEachIndexed { i, label ->
            SegmentedButton(
                selected = selected == i, onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
            ) { Text(label) }
        }
    }
}

@Composable
fun FolderGroupsGrid(groups: List<FolderGroup>, padding: PaddingValues, onFolder: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(groups, key = { it.path }) { g -> FolderGroupCard(g) { onFolder(g.path) } }
    }
}

@Composable
fun FolderGroupCard(group: FolderGroup, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().pressScale(interaction).glassPanel(RoundedCornerShape(22.dp))
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.25f).clip(RoundedCornerShape(16.dp))) {
            // A 2x2 mosaic of the newest files: real thumbnails.
            val firsts = group.items.take(4)
            if (firsts.size >= 4) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (r in 0..1) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (c in 0..1) {
                            Box(Modifier.weight(1f).fillMaxSize()) { MosaicThumb(firsts[r * 2 + c]) }
                        }
                    }
                }
            } else {
                MosaicThumb(firsts.first())
            }
        }
        Text(group.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        Text("${group.count} · ${formatSize(group.totalSize)}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
    }
}

@Composable
private fun MosaicThumb(item: FileItem) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
        FileThumb(item, size = maxOf(maxWidth, maxHeight), corner = 0.dp, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun CategoryFolderScreen(cat: Category, folder: String, opener: Opener, onBack: () -> Unit, onSearch: () -> Unit, onPaste: () -> Unit) {
    val (all, loading) = rememberCategoryItems(cat)
    val items = remember(all, folder, loading) { all?.filter { it.parentPath == folder }?.takeIf { it.isNotEmpty() || !loading } }
    FileListScreen(
        title = folder.substringAfterLast('/'),
        subtitle = cat.title + (items?.let { " · ${it.size}" } ?: ""),
        items = items,
        grid = cat.gridFiles,
        opener = opener,
        onBack = onBack,
        onSearch = onSearch,
        onPaste = onPaste,
    )
}

/** A list (or grid) of files with selection, actions and sort. Used by categories and the Bin-less views. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListScreen(
    title: String,
    subtitle: String?,
    items: List<FileItem>?,
    grid: Boolean,
    opener: Opener,
    onBack: () -> Unit,
    onSearch: (() -> Unit)?,
    onPaste: () -> Unit,
    tabs: (@Composable () -> Unit)? = null,
    defaultSort: SortBy = SortBy.DATE,
) {
    val selection = remember { SelectionState() }
    val actions = rememberFileActions(afterCopyOrMove = { selection.clear(); onPaste() }, afterAction = { selection.clear() })
    var sortBy by rememberSaveable { mutableStateOf(defaultSort) }
    var asc by rememberSaveable { mutableStateOf(false) }
    var useGrid by rememberSaveable { mutableStateOf(grid) }
    var sortMenu by remember { mutableStateOf(false) }
    val sorted = remember(items, sortBy, asc) { items?.let { sortItems(it, sortBy, asc) } }
    val selected = sorted.orEmpty().filter { selection.isSelected(it.path) }
    BackHandler(selection.active) { selection.clear() }

    GlassScaffold(
        topBar = {
            if (selection.active) {
                GlassTopBar(
                    title = "${selection.paths.size} selected",
                    navigation = { CloseSelectionButton { selection.clear() } },
                    actions = { SelectionActionsRow(selected, actions) { selection.selectAll(sorted.orEmpty().map { it.path }) } },
                    below = tabs,
                )
            } else {
                GlassTopBar(
                    title = title, subtitle = subtitle, onBack = onBack,
                    actions = {
                        if (onSearch != null) IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Search") }
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                            SortMenu(sortMenu, { sortMenu = false }, sortBy, asc, { s, a -> sortBy = s; asc = a }, useGrid, { useGrid = it })
                        }
                    },
                    below = tabs,
                )
            }
        },
    ) { padding ->
        val list = sorted
        val click: (FileItem) -> Unit = { if (selection.active) selection.toggle(it.path) else opener.open(it, list.orEmpty()) }
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyState(Icons.Filled.FolderOpen, "Nothing here", modifier = Modifier.padding(padding))
            useGrid -> LazyVerticalGrid(
                columns = GridCells.Adaptive(if (list.firstOrNull()?.kind == FileKind.IMAGE) 100.dp else 110.dp),
                contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.path }, contentType = { it.kind }) { item ->
                    FileTile(item, selection.isSelected(item.path), { click(item) }, { selection.toggle(item.path) },
                        showName = item.kind != FileKind.IMAGE && item.kind != FileKind.VIDEO)
                }
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.path }, contentType = { it.kind }) { item ->
                    FileRow(item, selection.isSelected(item.path), selection.active, { click(item) }, { selection.toggle(item.path) })
                }
            }
        }
    }
    FileActionDialogs(actions, onDeleted = { selection.clear() })
}
