package com.clpstudio.filesmanager.picker

import java.io.File
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.verticalScroll
import com.clpstudio.filesmanager.ui.components.swipeTabs
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.DateQueryParser
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.StorageVolume
import com.clpstudio.filesmanager.ui.components.FileRow
import com.clpstudio.filesmanager.ui.components.FileTile
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.components.SearchField
import com.clpstudio.filesmanager.ui.components.formatSize
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.glass.glassBar
import com.clpstudio.filesmanager.ui.glass.glassPanel

/** What the picker shows right now (filled by [PickerActivity], or by a test). */
@Immutable
data class PickerUi(
    val request: PickerRequest,
    val section: PickerSection,
    /** Files of the section (or folder) after the type filter; null = loading. */
    val items: List<FileItem>?,
    /** Browse: the folder shown (null = the list of storage volumes). */
    val folder: String? = null,
    val volumes: List<StorageVolume> = emptyList(),
    val query: String = "",
    val searching: Boolean = false,
    val needsPermission: Boolean = false,
    /** The file being previewed (item 54), null = the list. */
    val preview: FileItem? = null,
)

/**
 * The picker other apps open (Gallery-style "Browse files in other apps" → Files): categories,
 * folders and search like the app, tap = pick, multi-select with a Select button when allowed.
 */
@Composable
fun PickerContent(
    ui: PickerUi,
    selected: Set<String>,
    /** Multi-select: Select all / Deselect all of the files shown. */
    onToggleAll: (() -> Unit)? = null,
    onSection: (PickerSection) -> Unit,
    onQuery: (String) -> Unit,
    onOpenFolder: (String?) -> Unit,
    onUp: () -> Unit,
    onTap: (FileItem) -> Unit,
    onLongPress: (FileItem) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onAllowAccess: () -> Unit,
    onPreviewSelect: (FileItem) -> Unit = {},
    onPreviewClose: () -> Unit = {},
) {
    ui.preview?.let { pv ->
        PickerPreview(pv, if (ui.request.multiple) (if (pv.path in selected) "Selected ✓" else "Add to selection") else "Select", { onPreviewSelect(pv) }, onPreviewClose)
        return
    }
    var searchOpen by remember { mutableStateOf(ui.query.isNotEmpty()) }
    val browsingFolder = ui.section == PickerSection.BROWSE && ui.folder != null
    BackHandler(enabled = searchOpen || browsingFolder) {
        if (searchOpen) { searchOpen = false; onQuery("") } else onUp()
    }
    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = if (selected.isNotEmpty()) "${selected.size} selected" else ui.request.title,
                subtitle = when {
                    browsingFolder -> ui.folder?.let(::folderLabel)
                    ui.request.multiple -> "Tap to preview · long-press to select"
                    else -> "Tap to preview · long-press to choose"
                },
                navigation = {
                    IconButton(onClick = { if (browsingFolder) onUp() else onCancel() }) {
                        Icon(if (browsingFolder) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close, if (browsingFolder) "Up" else "Cancel")
                    }
                },
                actions = {
                    if (ui.request.multiple && selected.isNotEmpty() && onToggleAll != null) {
                        val files = ui.items.orEmpty().filter { !it.isDir }.map { it.path }
                        com.clpstudio.filesmanager.ui.components.SelectAllToggle(com.clpstudio.filesmanager.data.SelectionLogic.allSelected(selected, files), onToggleAll)
                    }
                    IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) onQuery("") }) { Icon(Icons.Filled.Search, "Search") }
                },
                below = {
                    if (searchOpen) {
                        SearchField(
                            value = ui.query,
                            onValueChange = onQuery,
                            placeholder = "Search by name",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ui.request.sections.forEach { s ->
                            FilterChip(selected = s == ui.section, onClick = { onSection(s) }, label = { Text(s.title) })
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (ui.request.multiple && selected.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().glassBar().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${selected.size} file" + (if (selected.size == 1) "" else "s"),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onDone, modifier = Modifier.height(48.dp)) { Text("Select") }
                }
            }
        },
    ) { padding ->
        val top = padding.calculateTopPadding() + 4.dp
        val bottom = padding.calculateBottomPadding() + 24.dp
        // Swipe left / right to move between the sections (Recent, Images, ...).
        val sections = ui.request.sections
        Box(Modifier.fillMaxSize().swipeTabs(sections.indexOf(ui.section).coerceAtLeast(0), sections.size, enabled = !browsingFolder) { i -> onSection(sections[i]) }) { when {
            ui.needsPermission -> Column(
                Modifier.fillMaxSize().padding(top = top, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.FolderOpen, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("Files needs access to your storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Allow \"All files access\" once, then choose your file here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAllowAccess) { Text("Allow access") }
            }
            ui.section == PickerSection.BROWSE && ui.folder == null && ui.query.isEmpty() -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 4.dp, bottom = bottom),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(ui.volumes, key = { it.path }) { v ->
                    Row(
                        Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(20.dp)).clickable { onOpenFolder(v.path) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (v.removable) Icons.Filled.SdCard else Icons.Filled.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(v.label, style = MaterialTheme.typography.titleSmall)
                            Text("${formatSize(v.free)} free of ${formatSize(v.total)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            ui.items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ui.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(top = top), contentAlignment = Alignment.Center) {
                Text(
                    if (ui.query.isNotEmpty()) "No matching files" else if (ui.searching) "Searching…" else "Nothing to choose here",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            gridFor(ui) -> LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = top, bottom = bottom),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(ui.items, key = { it.path }) { f ->
                    FileTile(f, f.path in selected, onClick = { onTap(f) }, onLongClick = { onLongPress(f) }, showName = true)
                }
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = top, bottom = bottom),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(ui.items, key = { it.path }) { f ->
                    if (f.isDir) {
                        FolderLine(f) { onOpenFolder(f.path) }
                    } else {
                        FileRow(f, f.path in selected, ui.request.multiple && selected.isNotEmpty(), onClick = { onTap(f) }, onLongClick = { onLongPress(f) }, extraLine = f.name.substringAfterLast('.', "").uppercase().ifEmpty { null })
                    }
                }
            }
        } }
    }
}

private fun gridFor(ui: PickerUi): Boolean =
    (ui.section == PickerSection.PHOTOS || ui.section == PickerSection.VIDEOS) ||
        (ui.items.orEmpty().isNotEmpty() && ui.items.orEmpty().all { it.kind == FileKind.IMAGE || it.kind == FileKind.VIDEO })

private fun folderLabel(path: String): String {
    val primary = runCatching { android.os.Environment.getExternalStorageDirectory().absolutePath }.getOrDefault("/storage/emulated/0")
    return if (path.startsWith(primary)) "Internal storage" + path.removePrefix(primary) else path
}

@Composable
private fun FolderLine(f: FileItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).background(Color(0x22F59E0B), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Folder, null, tint = Color(0xFFF59E0B))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (f.childCount >= 0) Text(if (f.childCount == 1) "1 item" else "${f.childCount} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Name filter used by the picker's search box (same rules as the app's search). */
fun pickerFilter(items: List<FileItem>, query: String): List<FileItem> =
    if (query.isBlank()) items else items.filter { DateQueryParser.nameMatches(it.name, query) }


/**
 * Preview inside the picker (item 54): the file itself (photo, video/audio playing, PDF first page, text),
 * with its name, type, size and date, and a Select button. Back / ✕ returns to the list.
 */
@Composable
fun PickerPreview(item: FileItem, selectLabel: String, onSelect: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val file = remember(item.path) { File(item.path) }
    androidx.compose.material3.Surface(Modifier.fillMaxSize().testTag(PICKER_PREVIEW_TAG), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            GlassTopBar(
                title = item.name,
                subtitle = previewFacts(item),
                navigation = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close preview") } },
            )
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                when (item.kind) {
                    FileKind.IMAGE -> coil3.compose.AsyncImage(model = file, contentDescription = item.name, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                    FileKind.VIDEO, FileKind.AUDIO -> PickerMediaPreview(file, item.kind == FileKind.AUDIO, item)
                    FileKind.PDF -> PickerPdfPreview(file, item)
                    FileKind.TEXT -> {
                        val text = remember(item.path) { runCatching {
                            file.inputStream().use { s -> val buf = ByteArray(8 * 1024); var n = 0; while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }; String(buf, 0, n, Charsets.UTF_8) }
                        }.getOrDefault("") }
                        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            modifier = Modifier.fillMaxSize().glassPanel(RoundedCornerShape(16.dp)).padding(14.dp).verticalScroll(rememberScrollState()))
                    }
                    else -> PreviewIcon(item)
                }
            }
            Row(
                Modifier.fillMaxWidth().glassBar().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.OutlinedButton(onClick = onClose, modifier = Modifier.height(48.dp)) { Text("Back") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onSelect, modifier = Modifier.weight(1f).height(48.dp)) { Text(selectLabel) }
            }
        }
    }
}

const val PICKER_PREVIEW_TAG = "pickerPreview"

/** "PDF · 1.2 MB · 31 Oct 2026, 19:40". */
fun previewFacts(item: FileItem): String {
    val type = item.name.substringAfterLast('.', "").uppercase().ifEmpty { "File" }
    val date = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(item.modified))
    return "$type · ${formatSize(item.size)} · $date"
}

@Composable
private fun PreviewIcon(item: FileItem) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        com.clpstudio.filesmanager.ui.components.FileThumb(item, 120.dp)
        Spacer(Modifier.height(12.dp))
        Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(previewFacts(item), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PickerPdfPreview(file: File, item: FileItem) {
    val bmp by produceState<android.graphics.Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    android.graphics.pdf.PdfRenderer(fd).use { r ->
                        r.openPage(0).use { page ->
                            val scale = 1200f / maxOf(page.width, page.height)
                            android.graphics.Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(android.graphics.Color.WHITE)
                                page.render(it, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            }.getOrNull()
        }
    }
    val b = bmp
    if (b != null) androidx.compose.foundation.Image(b.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
    else PreviewIcon(item)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PickerMediaPreview(file: File, audio: Boolean, item: FileItem) {
    val context = LocalContext.current
    val player = remember(file) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file)))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // Leaving the picker stops it (no sound after closing).
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, player) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (audio) { Spacer(Modifier.weight(1f)); PreviewIcon(item) }
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { c -> androidx.media3.ui.PlayerView(c).apply { this.player = player; setShowNextButton(false); setShowPreviousButton(false); if (audio) controllerShowTimeoutMs = 0 } },
            modifier = if (audio) Modifier.fillMaxWidth().height(140.dp) else Modifier.fillMaxSize(),
        )
        if (audio) Spacer(Modifier.weight(1f))
    }
}
