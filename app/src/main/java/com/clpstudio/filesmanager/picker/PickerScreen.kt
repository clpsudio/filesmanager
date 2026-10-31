package com.clpstudio.filesmanager.picker

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
) {
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
                    ui.request.multiple -> "Tap to select, then Select"
                    else -> "Tap a file to choose it"
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
                        Icon(if (v.label == "SD card") Icons.Filled.SdCard else Icons.Filled.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
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
                        FileRow(f, f.path in selected, ui.request.multiple && selected.isNotEmpty(), onClick = { onTap(f) }, onLongClick = { onLongPress(f) })
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
