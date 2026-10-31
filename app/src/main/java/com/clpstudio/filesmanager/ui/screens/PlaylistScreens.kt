package com.clpstudio.filesmanager.ui.screens

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.FileEvents
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.Playlist
import com.clpstudio.filesmanager.data.PlaylistRules
import com.clpstudio.filesmanager.data.PlaylistType
import com.clpstudio.filesmanager.data.Playlists
import com.clpstudio.filesmanager.data.Category
import com.clpstudio.filesmanager.ui.components.ConfirmDialog
import com.clpstudio.filesmanager.ui.components.EmptyState
import com.clpstudio.filesmanager.ui.components.FileThumb
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.components.MenuRow
import com.clpstudio.filesmanager.ui.components.TextInputDialog
import com.clpstudio.filesmanager.ui.components.ThumbFill
import com.clpstudio.filesmanager.ui.components.formatSize
import com.clpstudio.filesmanager.ui.glass.DialogGlassEffect
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.glass.LocalGlass
import com.clpstudio.filesmanager.ui.glass.dialogContainerColor
import com.clpstudio.filesmanager.ui.glass.glassPanel
import com.clpstudio.filesmanager.ui.glass.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ---------- look ----------

fun playlistIcon(type: PlaylistType): ImageVector = when (type) {
    PlaylistType.AUDIO -> Icons.AutoMirrored.Filled.QueueMusic
    PlaylistType.VIDEO -> Icons.Filled.VideoLibrary
    PlaylistType.PHOTO -> Icons.Filled.Collections
    PlaylistType.GALLERY -> Icons.Filled.PermMedia
}

/** Same bright-on-tint style as the Home category tiles (no green). */
fun playlistAccent(type: PlaylistType, dark: Boolean): Color = when (type) {
    PlaylistType.AUDIO -> if (dark) Color(0xFFBB86FF) else Color(0xFF8136E6)
    PlaylistType.VIDEO -> if (dark) Color(0xFFFF6464) else Color(0xFFD12A2A)
    PlaylistType.PHOTO -> if (dark) Color(0xFF5AA2FF) else Color(0xFF1D64D8)
    PlaylistType.GALLERY -> if (dark) Color(0xFFFF9442) else Color(0xFFD2560A)
}

// ---------- Home section ----------

/** Home: "Playlists" (right after Categories) with one entry per kind; [counts] = saved playlists per kind. */
@Composable
fun PlaylistHomeSection(counts: Map<PlaylistType, Int>, columns: Int, onOpen: (PlaylistType) -> Unit) {
    val perRow = if (columns >= 4) 4 else 2
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        PlaylistType.entries.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { t ->
                    PlaylistTypeTile(t, counts[t] ?: 0, Modifier.weight(1f)) { onOpen(t) }
                }
            }
        }
    }
}

@Composable
private fun PlaylistTypeTile(type: PlaylistType, count: Int, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val dark = LocalGlass.current.dark
    val accent = playlistAccent(type, dark)
    Row(
        modifier.height(68.dp).pressScale(interaction).glassPanel(RoundedCornerShape(20.dp))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(accent.copy(alpha = if (dark) 0.20f else 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(playlistIcon(type), null, tint = accent, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(type.listTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (count == 0) "Create one" else PlaylistRules.countLabel(count, "playlist"),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
    }
}

// ---------- cover ----------

/** The cover: the chosen photo, else the first item's thumbnail, else the kind's icon on its colour. */
@Composable
fun PlaylistCover(type: PlaylistType, cover: String?, first: String?, modifier: Modifier = Modifier, corner: androidx.compose.ui.unit.Dp = 18.dp) {
    val dark = LocalGlass.current.dark
    val accent = playlistAccent(type, dark)
    Box(modifier.clip(RoundedCornerShape(corner)), contentAlignment = Alignment.Center) {
        val show = cover?.takeIf { Playlists.exists(it) } ?: first
        if (show != null) {
            val item = remember(show) { Playlists.itemOf(show) }
            ThumbFill(item, corner)
        } else {
            Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.18f)))),
                contentAlignment = Alignment.Center,
            ) { Icon(playlistIcon(type), null, tint = Color.White, modifier = Modifier.size(44.dp)) }
        }
    }
}

// ---------- list of playlists ----------

@Composable
fun PlaylistsScreen(type: PlaylistType, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { Playlists.get(context) }
    val all by store.playlists.collectAsState()
    val version by FileEvents.version.collectAsState()
    val mine = remember(all, type) { all.filter { it.type == type }.sortedByDescending { it.modified } }
    // The first item still on storage (for the default cover) and how many are there, per playlist.
    val present by produceState(emptyMap<String, List<String>>(), mine, version) {
        value = withContext(Dispatchers.IO) { mine.associate { p -> p.id to p.items.filter(Playlists::exists) } }
    }
    var creating by remember { mutableStateOf(false) }
    PlaylistsContent(type, mine, present, onBack, onOpen, onNew = { creating = true })
    if (creating) {
        CreatePlaylistDialog(
            types = listOf(type), existing = all,
            onCreate = { t, name, cover -> store.create(t, name, cover).map { it.id }.onSuccess { onOpen(it) } },
            onDismiss = { creating = false },
        )
    }
}

@Composable
fun PlaylistsContent(
    type: PlaylistType,
    playlists: List<Playlist>,
    present: Map<String, List<String>>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
) {
    GlassScaffold(
        topBar = {
            GlassTopBar(type.listTitle, onBack = onBack, subtitle = PlaylistRules.countLabel(playlists.size, "playlist"), actions = {
                IconButton(onClick = onNew) { Icon(Icons.Filled.Add, "New playlist") }
            })
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "new") { NewPlaylistCard(type, onNew) }
            items(playlists, key = { it.id }) { p ->
                val there = present[p.id]
                PlaylistCard(p, there?.size ?: p.items.size, there?.firstOrNull()) { onOpen(p.id) }
            }
            if (playlists.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    Text(
                        "No ${type.title.lowercase()} playlists yet. Tap New playlist, give it a name, then add " + when (type) {
                            PlaylistType.AUDIO -> "songs."
                            PlaylistType.VIDEO -> "videos."
                            PlaylistType.PHOTO -> "photos."
                            PlaylistType.GALLERY -> "photos and videos."
                        } + " You can also select files anywhere and choose Add to playlist from the ⋮ menu.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NewPlaylistCard(type: PlaylistType, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().pressScale(interaction).clip(RoundedCornerShape(20.dp))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp))
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("New playlist", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp))
        Text(type.listTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
private fun PlaylistCard(p: Playlist, count: Int, first: String?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().pressScale(interaction).clip(RoundedCornerShape(20.dp))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick),
    ) {
        PlaylistCover(p.type, p.cover, first, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        Text(PlaylistRules.countLabel(count), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

// ---------- one playlist ----------

@Composable
fun PlaylistDetailScreen(id: String, onBack: () -> Unit, play: (List<FileItem>, Int) -> Unit) {
    val context = LocalContext.current
    val store = remember { Playlists.get(context) }
    val all by store.playlists.collectAsState()
    val p = all.firstOrNull { it.id == id }
    LaunchedEffect(p == null) { if (p == null) onBack() }
    if (p == null) return
    val version by FileEvents.version.collectAsState()
    val loaded by produceState<Pair<List<FileItem>, List<String>>?>(null, p.items, version) {
        value = withContext(Dispatchers.IO) {
            val (here, gone) = PlaylistRules.split(p.items, Playlists::exists)
            here.map(Playlists::itemOf) to gone
        }
    }
    var dialog by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    val fileActions = com.clpstudio.filesmanager.ui.components.rememberFileActions(afterCopyOrMove = {})
    val items = loaded?.first
    val missing = loaded?.second.orEmpty()
    PlaylistDetailContent(
        playlist = p, items = items, missing = missing.size,
        onBack = onBack,
        onPlay = { start -> items?.takeIf { it.isNotEmpty() }?.let { play(it, start) } },
        onShuffle = { items?.takeIf { it.isNotEmpty() }?.let { play(PlaylistRules.shuffled(it), 0) } },
        onAdd = { picker = "add" },
        onRemove = { store.remove(id, listOf(it.path)) },
        onReorder = { order -> store.reorder(id, order) },
        onMissing = { dialog = "missing" },
        onRename = { dialog = "rename" },
        onCover = { picker = "cover" },
        onClearCover = { store.setCover(id, null) },
        onDelete = { dialog = "delete" },
        onRemoveMany = { list -> store.remove(id, list.map { it.path }) },
        onDeleteFiles = { list -> fileActions.deleteTargets = list },
    )
    // Deleting a file from inside a playlist deletes the file itself and takes it out of the playlist.
    com.clpstudio.filesmanager.ui.components.FileActionDialogs(fileActions, onDeleted = {
        store.remove(id, PlaylistRules.split(p.items, Playlists::exists).second)
    })
    when (dialog) {
        "rename" -> TextInputDialog(
            "Rename playlist", p.name, "Rename", selectBaseName = false,
            onConfirm = { name -> store.rename(id, name).exceptionOrNull()?.message },
            onDismiss = { dialog = null },
        )
        "delete" -> ConfirmDialog(
            "Delete playlist?", "“${p.name}” will be deleted. The files themselves stay where they are.", "Delete",
            onConfirm = { store.delete(id) }, onDismiss = { dialog = null }, destructive = true,
        )
        "missing" -> ConfirmDialog(
            "Remove missing items?",
            "${PlaylistRules.countLabel(missing.size, "file")} in this playlist ${if (missing.size == 1) "was" else "were"} deleted or moved. Remove ${if (missing.size == 1) "it" else "them"} from the playlist?",
            "Remove", onConfirm = { store.remove(id, missing) }, onDismiss = { dialog = null },
        )
    }
    when (picker) {
        "add" -> FilePickerDialog(
            title = "Add to “${p.name}”", category = p.type.category, accepts = { p.type.accepts(it.kind) },
            multiple = true, already = p.items.toSet(), confirm = "Add",
            onPicked = { picked ->
                val n = store.add(id, picked)
                android.widget.Toast.makeText(context, "Added ${PlaylistRules.countLabel(n)}", android.widget.Toast.LENGTH_SHORT).show()
            },
            onDismiss = { picker = null },
        )
        "cover" -> FilePickerDialog(
            title = "Choose cover photo", category = Category.PHOTOS, accepts = { it.kind == com.clpstudio.filesmanager.data.FileKind.IMAGE },
            multiple = false, confirm = "Use as cover",
            onPicked = { picked -> picked.firstOrNull()?.let { store.setCover(id, it.path) } },
            onDismiss = { picker = null },
        )
    }
}

@Composable
fun PlaylistDetailContent(
    playlist: Playlist,
    /** Items whose file is there, in play order (null while checking). */
    items: List<FileItem>?,
    missing: Int,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    onAdd: () -> Unit,
    onRemove: (FileItem) -> Unit,
    onReorder: (List<String>) -> Unit,
    onMissing: () -> Unit,
    onRename: () -> Unit,
    onCover: () -> Unit,
    onClearCover: () -> Unit,
    onDelete: () -> Unit,
    /** Selection bar: "Remove from playlist" (files stay) for the selected rows. */
    onRemoveMany: (List<FileItem>) -> Unit = { it.forEach(onRemove) },
    /** Selection bar: Delete = delete the files themselves (to the Bin; hidden files permanently, after a confirm). */
    onDeleteFiles: (List<FileItem>) -> Unit = {},
    initialSelection: Set<String> = emptySet(),
) {
    var menu by remember { mutableStateOf(false) }
    // Long-press a row to select; tap toggles while selecting.
    var sel by remember { mutableStateOf(initialSelection) }
    LaunchedEffect(items) { items?.let { l -> val alive = l.map { it.path }.toSet(); if (!alive.containsAll(sel)) sel = sel intersect alive } }
    androidx.activity.compose.BackHandler(sel.isNotEmpty()) { sel = emptySet() }
    // Local order while dragging; saved when the drag ends.
    val order = remember(items) { mutableStateListOf<FileItem>().apply { addAll(items.orEmpty()) } }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { 72.dp.toPx() }
    val n = items?.size ?: playlist.items.size
    GlassScaffold(
        topBar = {
            if (sel.isNotEmpty()) {
                val chosen = order.filter { it.path in sel }
                GlassTopBar(
                    "${sel.size} selected",
                    navigation = { com.clpstudio.filesmanager.ui.components.CloseSelectionButton { sel = emptySet() } },
                    actions = {
                        val allPaths = order.map { it.path }
                        com.clpstudio.filesmanager.ui.components.SelectAllToggle(allPaths.isNotEmpty() && sel.containsAll(allPaths)) {
                            sel = if (sel.containsAll(allPaths)) emptySet() else allPaths.toSet()
                        }
                        IconButton(onClick = { onRemoveMany(chosen); sel = emptySet() }) { Icon(Icons.Filled.PlaylistRemove, "Remove from playlist") }
                        IconButton(onClick = { onDeleteFiles(chosen) }) { Icon(Icons.Filled.Delete, "Delete") }
                    },
                )
            } else GlassTopBar(playlist.name, onBack = onBack, subtitle = "${playlist.type.title} playlist · ${PlaylistRules.countLabel(n)}", actions = {
                IconButton(onClick = onAdd) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add items") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        MenuRow(Icons.Filled.DriveFileRenameOutline, "Rename") { menu = false; onRename() }
                        MenuRow(Icons.Filled.Image, "Change cover") { menu = false; onCover() }
                        if (playlist.cover != null) MenuRow(Icons.Filled.HideImage, "Use first item as cover") { menu = false; onClearCover() }
                        MenuRow(Icons.Filled.Delete, "Delete playlist") { menu = false; onDelete() }
                    }
                }
            })
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlaylistCover(playlist.type, playlist.cover, items?.firstOrNull()?.path, Modifier.size(112.dp).clickable(onClick = onCover))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onPlay(0) }, enabled = n > 0, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("Play all")
                        }
                        FilledTonalButton(onClick = onShuffle, enabled = n > 0, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Shuffle, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("Shuffle")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (missing > 0) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)).clickable(onClick = onMissing)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("$missing missing · deleted or moved. Tap to remove", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (items != null && items.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyState(playlistIcon(playlist.type), "No items yet", "Tap Add items, or select files anywhere and choose Add to playlist.")
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onAdd) { Icon(Icons.Filled.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("Add items") }
                    }
                }
            }
            itemsIndexed(order, key = { _, it -> it.path }) { index, item ->
                val isDragged = dragging == item.path
                Row(
                    Modifier.fillMaxWidth().height(72.dp).zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) dragOffset else 0f; shadowElevation = if (isDragged) 12f else 0f }
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isDragged) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .background(if (item.path in sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                        .combinedClickable(
                            onClick = { if (sel.isNotEmpty()) sel = if (item.path in sel) sel - item.path else sel + item.path else onPlay(index) },
                            onLongClick = { sel = if (item.path in sel) sel - item.path else sel + item.path },
                        )
                        .padding(start = 4.dp, end = 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Drag handle: drag up / down to reorder.
                    Box(
                        Modifier.size(40.dp, 56.dp).pointerInput(item.path) {
                            detectVerticalDragGestures(
                                onDragStart = { dragging = item.path; dragOffset = 0f },
                                onVerticalDrag = { change, dy ->
                                    change.consume()
                                    dragOffset += dy
                                    val from = order.indexOfFirst { it.path == item.path }
                                    if (from >= 0) {
                                        val to = (from + (dragOffset / rowPx).roundToInt()).coerceIn(0, order.lastIndex)
                                        if (to != from) {
                                            order.add(to, order.removeAt(from))
                                            dragOffset -= (to - from) * rowPx
                                        }
                                    }
                                },
                                onDragEnd = { dragging = null; dragOffset = 0f; onReorder(order.map { it.path }) },
                                onDragCancel = { dragging = null; dragOffset = 0f; onReorder(order.map { it.path }) },
                            )
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.DragHandle, "Drag to reorder", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    FileThumb(item, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        Text(
                            "${index + 1} · ${formatSize(item.size)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                    }
                    if (sel.isNotEmpty()) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            if (item.path in sel) Icon(Icons.Filled.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
                            else Icon(Icons.Filled.RadioButtonUnchecked, "Not selected", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        IconButton(onClick = { onRemove(item) }) { Icon(Icons.Filled.Close, "Remove from playlist", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

// ---------- create ----------

/** Name (required, unique per kind), kind (when the files fit more than one) and an optional cover from Photos. */
@Composable
fun CreatePlaylistDialog(
    types: List<PlaylistType>,
    existing: List<Playlist>,
    onCreate: (PlaylistType, String, String?) -> Result<String>,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(types.first()) }
    var cover by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickCover by remember { mutableStateOf(false) }
    val submit: () -> Unit = {
        val err = PlaylistRules.nameError(name, type, existing)
        if (err != null) error = err
        else onCreate(type, name, cover).onSuccess { onDismiss() }.onFailure { error = it.message }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        title = { Text("New playlist") },
        text = {
            DialogGlassEffect()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = null }, singleLine = true,
                    label = { Text("Name") }, isError = error != null,
                    supportingText = { if (error != null) Text(error!!) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (types.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        types.forEach { t ->
                            FilterChip(selected = t == type, onClick = { type = t; error = null }, label = { Text(t.listTitle.removeSuffix("s")) },
                                leadingIcon = { Icon(playlistIcon(t), null, Modifier.size(18.dp)) })
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { pickCover = true }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlaylistCover(type, cover, null, Modifier.size(56.dp), corner = 12.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (cover == null) "Choose cover photo" else "Change cover photo", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
                        Text(if (cover == null) "Optional · else the first item is used" else cover!!.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                    if (cover != null) IconButton(onClick = { cover = null }) { Icon(Icons.Filled.Close, "Remove cover") }
                }
            }
        },
        confirmButton = { TextButton(onClick = submit) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickCover) {
        FilePickerDialog(
            title = "Choose cover photo", category = Category.PHOTOS, accepts = { it.kind == com.clpstudio.filesmanager.data.FileKind.IMAGE },
            multiple = false, confirm = "Use as cover",
            onPicked = { cover = it.firstOrNull()?.path }, onDismiss = { pickCover = false },
        )
    }
}

// ---------- add from a selection ----------

/** "Add to playlist" from a selection menu: only playlists whose kind takes every selected file, plus "New playlist…". */
@Composable
fun AddToPlaylistDialog(selected: List<FileItem>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { Playlists.get(context) }
    val all by store.playlists.collectAsState()
    val types = remember(selected) { PlaylistRules.typesFor(selected).toList().sortedBy { it.ordinal } }
    val matching = remember(all, selected) { PlaylistRules.matching(selected, all).sortedWith(compareBy<Playlist> { it.type.ordinal }.thenByDescending { it.modified }) }
    var creating by remember { mutableStateOf(false) }
    var createdOne by remember { mutableStateOf(false) }
    fun added(p: Playlist, n: Int) {
        val skipped = selected.size - n
        val msg = "Added ${PlaylistRules.countLabel(n)} to “${p.name}”" + if (skipped > 0) " ($skipped already in it)" else ""
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
    if (types.isEmpty()) {
        LaunchedEffect(Unit) {
            android.widget.Toast.makeText(context, "Only songs, videos and photos can go into playlists", android.widget.Toast.LENGTH_SHORT).show()
            onDismiss()
        }
        return
    }
    if (creating) {
        CreatePlaylistDialog(
            types = types, existing = all,
            onCreate = { t, name, cover ->
                store.create(t, name, cover).map { p -> val n = store.add(p.id, selected); added(p, n); createdOne = true; p.id }
            },
            // Cancel goes back to the playlist list; a created playlist closes everything.
            onDismiss = { creating = false; if (createdOne) onDismiss() },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        title = { Text("Add to playlist") },
        text = {
            DialogGlassEffect()
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item(key = "new") {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { creating = true }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("New playlist…", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                }
                items(matching, key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
                            val n = store.add(p.id, selected); added(p, n); onDismiss()
                        }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlaylistCover(p.type, p.cover, p.items.firstOrNull(), Modifier.size(48.dp), corner = 12.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.type.title} · ${PlaylistRules.countLabel(p.items.size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (matching.isEmpty()) {
                    item(key = "none") {
                        Text(
                            "No ${types.joinToString(" or ") { it.title.lowercase() }} playlists yet.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------- in-app picker ----------

/** Full-screen picker over a category (Photos for covers; the playlist's kind for "Add items"). */
@Composable
fun FilePickerDialog(
    title: String,
    category: Category,
    accepts: (FileItem) -> Boolean,
    multiple: Boolean,
    confirm: String,
    onPicked: (List<FileItem>) -> Unit,
    onDismiss: () -> Unit,
    already: Set<String> = emptySet(),
) {
    val files by produceState<List<FileItem>?>(null, category) {
        runCatching {
            Graph.media.categoryFlow(category, Graph.hiddenFilter()).collect { load -> value = load.items.filter { !it.isDir && accepts(it) } }
        }.onFailure { if (value == null) value = emptyList() }
    }
    val picked = remember { mutableStateListOf<String>() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        com.clpstudio.filesmanager.ui.glass.GlassBackground(Modifier.fillMaxSize())
        FilePickerContent(title, category, files, picked, multiple, confirm, already,
            onToggle = { path ->
                if (!multiple) { picked.clear(); picked += path } else if (path in picked) picked -= path else picked += path
            },
            onConfirm = {
                val map = files.orEmpty().associateBy { it.path }
                onPicked(picked.mapNotNull { map[it] }); onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
}

@Composable
fun FilePickerContent(
    title: String,
    category: Category,
    files: List<FileItem>?,
    picked: List<String>,
    multiple: Boolean,
    confirm: String,
    already: Set<String>,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val grid = category != Category.AUDIO
    GlassScaffold(
        topBar = {
            GlassTopBar(
                title, subtitle = if (picked.isEmpty()) (if (multiple) "Tap to select" else "Tap a photo") else "${picked.size} selected",
                navigation = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close") } },
                actions = { TextButton(onClick = onConfirm, enabled = picked.isNotEmpty()) { Text(if (multiple && picked.isNotEmpty()) "$confirm (${picked.size})" else confirm) } },
            )
        },
    ) { padding ->
        val pad = PaddingValues(start = 8.dp, end = 8.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp)
        when {
            files == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            files.isEmpty() -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { EmptyState(Icons.Filled.Collections, "Nothing here", "No ${category.title.lowercase()} found on this phone.") }
            grid -> LazyVerticalGrid(GridCells.Adaptive(104.dp), contentPadding = pad, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
                items(files, key = { it.path }) { f ->
                    val sel = f.path in picked
                    val inList = f.path in already
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).clickable(enabled = !inList) { onToggle(f.path) }) {
                        ThumbFill(f, 12.dp)
                        if (sel || inList) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = if (inList) 0.18f else 0.28f))
                                .border(if (sel) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)))
                            Icon(Icons.Filled.CheckCircle, if (inList) "Already in the playlist" else "Selected", tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(6.dp).size(22.dp).align(Alignment.TopEnd).background(MaterialTheme.colorScheme.surface, CircleShape))
                        }
                    }
                }
            }
            else -> LazyColumn(contentPadding = pad, modifier = Modifier.fillMaxSize()) {
                items(files, key = { it.path }) { f ->
                    val inList = f.path in already
                    com.clpstudio.filesmanager.ui.components.FileRow(
                        f, selected = f.path in picked || inList, selectionMode = true,
                        onClick = { if (!inList) onToggle(f.path) }, onLongClick = { if (!inList) onToggle(f.path) },
                        extraLine = if (inList) "Already in the playlist" else null,
                    )
                }
            }
        }
    }
}
