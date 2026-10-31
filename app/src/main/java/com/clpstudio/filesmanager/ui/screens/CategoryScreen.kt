package com.clpstudio.filesmanager.ui.screens

import androidx.activity.compose.BackHandler
import com.clpstudio.filesmanager.data.DefaultView
import com.clpstudio.filesmanager.data.Diagnostics
import com.clpstudio.filesmanager.data.distinctByPath
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
import androidx.compose.runtime.LaunchedEffect
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
import com.clpstudio.filesmanager.ui.components.FileCollection
import com.clpstudio.filesmanager.ui.components.TypeChipsRow
import com.clpstudio.filesmanager.ui.components.ViewSortRow
import com.clpstudio.filesmanager.ui.components.PlaySelectedButton
import com.clpstudio.filesmanager.ui.components.PlayAllButton
import com.clpstudio.filesmanager.data.TypeFilters
import androidx.compose.runtime.rememberCoroutineScope
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
import com.clpstudio.filesmanager.ui.components.rememberLiveFiles

/**
 * Last loaded list per category, in memory and on disk, so a category opens instantly (also right
 * after the app starts) and then refreshes quietly in the background.
 */
object CategoryCache {
    private val lists = java.util.concurrent.ConcurrentHashMap<Category, List<FileItem>>()
    private fun file(c: Category) = java.io.File(Graph.app.cacheDir, "category_${c.name}.tsv")

    operator fun get(c: Category): List<FileItem>? =
        (lists[c] ?: runCatching { read(c) }.getOrNull()?.also { lists[c] = it })?.let(com.clpstudio.filesmanager.data.FileEvents::filter)

    /** Deleted / moved files leave the cached lists too (memory and disk). */
    fun prune() {
        lists.entries.forEach { (c, v) ->
            val kept = com.clpstudio.filesmanager.data.FileEvents.filter(v)
            if (kept !== v) set(c, kept)
        }
    }

    operator fun set(c: Category, v: List<FileItem>) {
        if (lists[c] === v) return
        lists[c] = v
        Graph.appScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { write(c, v) } }
    }

    private fun read(c: Category): List<FileItem>? {
        val f = file(c)
        if (!f.isFile) return null
        return f.bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 3) null else {
                    val path = p[0]
                    // Skip files that are gone since the last run.
                    FileItem(path, path.substringAfterLast('/'), false, p[1].toLongOrNull() ?: 0, p[2].toLongOrNull() ?: 0)
                }
            }.toList()
        }.takeIf { it.isNotEmpty() }
    }

    private fun write(c: Category, v: List<FileItem>) {
        val tmp = java.io.File(Graph.app.cacheDir, "category_${c.name}.tmp")
        tmp.bufferedWriter().use { w -> v.take(50_000).forEach { w.append(it.path).append('\t').append(it.size.toString()).append('\t').append(it.modified.toString()).append('\n') } }
        tmp.renameTo(file(c))
    }
}

val Category.typeFilter: String?
    get() = when (this) {
        Category.PHOTOS -> "PHOTOS"
        Category.VIDEOS -> "VIDEOS"
        Category.AUDIO -> "AUDIO"
        Category.DOCUMENTS -> "DOCS"
        else -> null
    }

private val Category.gridFiles: Boolean get() = this == Category.PHOTOS || this == Category.VIDEOS || this == Category.MEDIA

/** The category list, streamed: null = still loading (spinner, never "empty" before the first result). */
@Composable
private fun rememberCategoryItems(cat: Category): Pair<List<FileItem>?, Boolean> {
    val settings by Graph.settings.collectAsState()
    var loading by remember(cat) { mutableStateOf(true) }
    val t0 = remember(cat) { System.currentTimeMillis() }
    val cached = remember(cat) { CategoryCache[cat]?.takeIf { it.isNotEmpty() } }
    LaunchedEffect(cat) { if (cached != null) Diagnostics.shown(cat.title, System.currentTimeMillis() - t0, fromCache = true) }
    // True once a complete list is on screen: a refresh then keeps it until its own load is complete,
    // so streamed partial results (index first, walk later) no longer make items vanish and come back.
    var hadComplete by remember(cat) { mutableStateOf(cached != null) }
    val items by produceState(cached, cat, settings.showHidden, AppState.refreshTick) {
        loading = true
        for (attempt in 0..1) {
            try {
                Graph.media.categoryFlow(cat, Graph.hiddenFilter(settings.showHidden)).collect { load ->
                    // Partial empty results keep the spinner; only a finished load may say "empty".
                    if (load.done || (load.items.isNotEmpty() && !hadComplete)) {
                        if (value == null) Diagnostics.shown(cat.title, System.currentTimeMillis() - t0, fromCache = false)
                        value = load.items.distinctByPath()
                        if (load.items.isNotEmpty()) CategoryCache[cat] = load.items
                        if (load.done) hadComplete = true
                    }
                    loading = !load.done
                }
                break
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Diagnostics.log("category", "${cat.title} failed to load (attempt ${attempt + 1})", e)
                if (attempt == 0) { kotlinx.coroutines.delay(400); continue }
                if (value == null) value = emptyList()
                loading = false
            }
        }
    }
    return rememberLiveFiles(items) to loading
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
            subtitle = null,
            items = items,
            grid = cat.gridFiles,
            opener = opener,
            onBack = onBack,
            onSearch = onSearch,
            onPaste = onPaste,
            tabs = { CategoryTabs(tab) { tab = it } },
            category = cat,
            loading = loading,
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

/** Files with type chips, total size, view (Grid / Large / List), sort, drag selection and actions. */
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
    category: Category? = null,
    loading: Boolean = false,
) {
    @Suppress("NAME_SHADOWING")
    val items = rememberLiveFiles(items)
    val settings by Graph.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val selection = remember { SelectionState() }
    val actions = rememberFileActions(afterCopyOrMove = { selection.clear(); onPaste() }, afterAction = { selection.clear() })
    var sortBy by rememberSaveable { mutableStateOf(defaultSort) }
    var asc by rememberSaveable { mutableStateOf(false) }
    // The app-wide view from Settings; the header dropdown changes it for this screen only.
    var view by rememberSaveable(settings.fileView) { mutableStateOf(settings.fileView) }
    val chosen = category?.let { settings.typeFilters[it.name] } ?: TypeFilters.ALL
    val chips = remember(items, category, chosen) { if (category != null && items != null) TypeFilters.counted(category, items, chosen) else emptyList() }
    val filtered = remember(items, category, chosen) { if (category != null && items != null) TypeFilters.apply(category, items, chosen) else items }
    val sorted = remember(filtered, sortBy, asc) { filtered?.let { sortItems(it, sortBy, asc) } }
    val totalSize = remember(filtered) { filtered?.sumOf { it.size } ?: 0L }
    val selected = sorted.orEmpty().filter { selection.isSelected(it.path) }
    BackHandler(selection.active) { selection.clear() }

    GlassScaffold(
        topBar = {
            if (selection.active) {
                GlassTopBar(
                    title = "${selection.paths.size} selected",
                    navigation = { CloseSelectionButton { selection.clear() } },
                    actions = {
                        PlaySelectedButton(selected, opener)
                        SelectionActionsRow(selected, actions) { selection.selectAll(sorted.orEmpty().map { it.path }) }
                    },
                    below = tabs,
                )
            } else {
                GlassTopBar(
                    title = title, subtitle = subtitle, onBack = onBack,
                    actions = {
                        PlayAllButton(sorted, opener)
                        if (onSearch != null) IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Search") }
                    },
                    below = {
                        tabs?.invoke()
                        if (chips.size > 1) TypeChipsRow(chips, chosen) { id -> category?.let { c -> scope.launch { Graph.settingsRepo.setTypeFilter(c.name, id) } } }
                        if (sorted != null) {
                            ViewSortRow(
                                summary = "${sorted.size} file" + (if (sorted.size == 1) "" else "s") + " · " + formatSize(totalSize) + if (loading) " · searching..." else "",
                                view = view, onView = { view = it },
                                sortBy = sortBy, ascending = asc, onSort = { s, a -> sortBy = s; asc = a },
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        val list = sorted
        val click: (FileItem) -> Unit = { if (selection.active) selection.toggle(it.path) else opener.open(it, list.orEmpty()) }
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyState(Icons.Filled.FolderOpen, if (chosen != TypeFilters.ALL) "No files of this type" else "Nothing here", modifier = Modifier.padding(padding))
            else -> FileCollection(
                items = list, view = view, selection = selection, onClick = click,
                contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                sections = sortBy == SortBy.DATE,
            )
        }
    }
    FileActionDialogs(actions, onDeleted = { selection.clear() })
}
