package com.clpstudio.filesmanager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.DuplicateSet
import com.clpstudio.filesmanager.data.FileEvents
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.ManageStorageRepository
import com.clpstudio.filesmanager.data.StorageMath
import com.clpstudio.filesmanager.data.SuggestionType
import com.clpstudio.filesmanager.data.VolumeReport
import com.clpstudio.filesmanager.ui.AppState
import com.clpstudio.filesmanager.ui.components.ConfirmDialog
import com.clpstudio.filesmanager.ui.components.FileThumb
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.components.formatDate
import com.clpstudio.filesmanager.ui.components.formatSize
import com.clpstudio.filesmanager.ui.components.rememberLiveFiles
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.glass.glassBar
import java.io.File

/** A block of rows; [header] = e.g. "3 copies · 12 MB" for a duplicate set. */
data class CleanupGroup(val header: String?, val items: List<FileItem>)

/** What one chip of the cleanup screen shows. */
data class CleanupPage(
    val chip: String,
    val explanation: String,
    val groups: List<CleanupGroup>,
    /** What "All" selects (duplicates: every copy except one per set). */
    val selectAll: List<String> = groups.flatMap { g -> g.items.map { it.path } },
)

object CleanupPages {
    fun duplicates(sets: List<DuplicateSet>): List<CleanupPage> = listOf(
        CleanupPage(
            chip = "Duplicates",
            explanation = "Files with the same size and content. \"All\" keeps the oldest copy of each.",
            groups = sets.map { s -> CleanupGroup("${s.files.size} copies · ${formatSize(s.size * s.files.size)}", s.files) },
            selectAll = StorageMath.allButOne(sets),
        ),
    )

    fun large(files: List<FileItem>): List<CleanupPage> =
        StorageMath.LargeGroup.entries.mapNotNull { g ->
            val l = StorageMath.filterLarge(files, g)
            if (g != StorageMath.LargeGroup.ALL && l.isEmpty()) null
            else CleanupPage(g.label, "Files of 25 MB or more, biggest first.", listOf(CleanupGroup(null, l)))
        }

    fun suggestions(report: VolumeReport): List<CleanupPage> = report.suggestions.map { s ->
        CleanupPage(s.type.chip, StorageMath.suggestionExplain(s.type), listOf(CleanupGroup(null, s.files)))
    }

    /** Drops deleted files; a duplicate set left with one copy is no longer a duplicate. */
    fun live(page: CleanupPage, keep: (List<FileItem>) -> List<FileItem>, minPerGroup: Int): CleanupPage {
        val groups = page.groups.map { g -> g.copy(items = keep(g.items)) }.filter { it.items.size >= minPerGroup }
        val alive = groups.flatMap { g -> g.items.map { it.path } }.toSet()
        return page.copy(groups = groups, selectAll = page.selectAll.filter { it in alive })
    }
}

/** The cleanup screen for Duplicates, Large files and every Suggestion. Opens in select mode. */
@Composable
fun StorageCleanupScreen(mode: String, volumePath: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by ManageStorageRepository.state().collectAsState()
    val dups by ManageStorageRepository.duplicates.collectAsState()
    LaunchedEffect(Unit) {
        ManageStorageRepository.ensureLoaded()
        // Duplicates are hashed lazily: opening this list asks for them now.
        if (mode == CleanupMode.DUPLICATES) ManageStorageRepository.requestDuplicates()
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        ManageStorageRepository.watch()
        onDispose { ManageStorageRepository.unwatch() }
    }
    // A report rebuilt from saved numbers has no file lists yet: wait for the fresh one.
    val report = state.value?.volumes?.firstOrNull { it.volume.path == volumePath }?.takeUnless { it.fromCache }
    val pages: List<CleanupPage>? = when {
        mode == CleanupMode.DUPLICATES -> dups.value?.get(volumePath)?.let(CleanupPages::duplicates)
        report == null -> null
        mode == CleanupMode.LARGE -> CleanupPages.large(report.large)
        else -> CleanupPages.suggestions(report)
    }
    val startChip = remember(pages == null) {
        val t = mode.removePrefix("sugg_")
        pages?.indexOfFirst { p -> SuggestionType.entries.any { it.name == t && it.chip == p.chip } }?.coerceAtLeast(0) ?: 0
    }
    var chip by rememberSaveable(startChip) { mutableIntStateOf(startChip) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirm by remember { mutableStateOf(false) }
    val version by FileEvents.version.collectAsState()
    val livePages = remember(pages, version) {
        pages?.map { CleanupPages.live(it, FileEvents::filter, if (mode == CleanupMode.DUPLICATES) 2 else 1) }
    }
    val page = livePages?.getOrNull(chip.coerceAtMost((livePages.size - 1).coerceAtLeast(0)))
    // Keep only rows that are still on screen selected.
    val visible = page?.groups?.flatMap { it.items }.orEmpty()
    LaunchedEffect(visible) { val alive = visible.map { it.path }.toSet(); if (!alive.containsAll(selected)) selected = selected intersect alive }

    StorageCleanupContent(
        page = page,
        chips = livePages?.map { it.chip }.orEmpty(),
        chipIndex = chip,
        onChip = { chip = it; selected = emptySet() },
        selected = selected,
        onToggle = { f -> selected = if (f.path in selected) selected - f.path else selected + f.path },
        onAll = {
            val all = page?.selectAll.orEmpty()
            selected = if (all.isNotEmpty() && selected.containsAll(all)) emptySet() else all.toSet()
        },
        onCancel = onBack,
        onDelete = { confirm = true },
    )
    if (confirm) {
        val files = visible.filter { it.path in selected }
        ConfirmDialog(
            title = "Move to Bin?",
            text = "${files.size} item" + (if (files.size == 1) "" else "s") + " (${formatSize(files.sumOf { it.size })}) will be moved to the Bin and kept for 30 days.",
            confirm = "Move to Bin",
            destructive = true,
            onConfirm = { AppState.moveToBin(context, files.map { File(it.path) }) { selected = emptySet() } },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
fun StorageCleanupContent(
    page: CleanupPage?,
    chips: List<String>,
    chipIndex: Int,
    onChip: (Int) -> Unit,
    selected: Set<String>,
    onToggle: (FileItem) -> Unit,
    onAll: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val items = rememberLiveFiles(page?.groups?.flatMap { it.items }).orEmpty()
    val selItems = items.filter { it.path in selected }
    val selBytes = selItems.sumOf { it.size }
    val allOn = page != null && page.selectAll.isNotEmpty() && selected.containsAll(page.selectAll)
    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = if (selected.isEmpty()) "Select items" else "${selected.size} selected",
                subtitle = if (selected.isEmpty()) null else formatSize(selBytes),
                navigation = {
                    Column(
                        Modifier.padding(start = 8.dp, end = 4.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onAll).padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RoundCheck(allOn, Modifier.size(24.dp))
                        Text(if (allOn) "Deselect all" else "All", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = { TextButton(onClick = onCancel) { Text("Cancel") } },
            )
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().glassBar().navigationBarsPadding().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onDelete).padding(horizontal = 28.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                        Text("Delete · ${formatSize(selBytes)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val side = ((maxWidth - 840.dp) / 2).coerceAtLeast(12.dp)
            if (page == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@BoxWithConstraints
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = side, end = side, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            ) {
                item("title") {
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        Text(StorageMath.itemsTitle(items.size, formatSize(items.sumOf { it.size })), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(page.explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                    }
                }
                if (chips.size > 1) item("chips") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                        items(chips.size) { i -> Chip(chips[i], i == chipIndex) { onChip(i) } }
                    }
                }
                if (items.isEmpty()) item("empty") {
                    Text("Nothing here now.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                }
                page.groups.forEachIndexed { gi, g ->
                    val live = g.items.filter { f -> items.any { it.path == f.path } }
                    if (live.isEmpty()) return@forEachIndexed
                    if (g.header != null) item("h_$gi") {
                        Text(
                            g.header, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp, top = if (gi == 0) 2.dp else 14.dp, bottom = 4.dp),
                        )
                    }
                    items(live, key = { "${gi}_${it.path}" }) { f -> CleanupRow(f, f.path in selected) { onToggle(f) } }
                }
            }
        }
    }
}

@Composable
private fun RoundCheck(on: Boolean, modifier: Modifier = Modifier) {
    if (on) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = modifier)
    else Icon(Icons.Outlined.Circle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val bg = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val fg = if (on) Color.White else MaterialTheme.colorScheme.onSurface
    Text(
        label, color = fg, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun CleanupRow(f: FileItem, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundCheck(on, Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        FileThumb(f, 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(formatDate(f.modified), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Text(formatSize(f.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
