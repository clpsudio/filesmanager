package com.clpstudio.filesmanager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.Category
import com.clpstudio.filesmanager.data.CategoryRepository
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.StoragePaths
import com.clpstudio.filesmanager.data.StorageVolume
import com.clpstudio.filesmanager.ui.AppState
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.components.formatSize
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.glass.glassPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One coloured slice of a volume's bar. [category] null = "Other" / "Free". */
@Immutable
data class StorageSlice(val label: String, val bytes: Long, val color: Color, val category: Category? = null)

/** What the in-app Internal storage screen shows for one volume. */
@Immutable
data class VolumeInfo(
    val volume: StorageVolume,
    val slices: List<StorageSlice>,
    val folders: List<FileItem>,
)

object StorageBreakdown {
    val colors = mapOf(
        Category.PHOTOS to Color(0xFF3B82F6),
        Category.VIDEOS to Color(0xFFEF4444),
        Category.AUDIO to Color(0xFFF59E0B),
        Category.DOCUMENTS to Color(0xFF10B981),
    )
    val other = Color(0xFF8B5CF6)

    /**
     * Slices for [volume]: per-category sizes of files on that volume, then "Other" (the rest of
     * the used space). Never more than the used space.
     */
    fun slices(volume: StorageVolume, lists: Map<Category, List<FileItem>?>): List<StorageSlice> {
        val used = (volume.total - volume.free).coerceAtLeast(0)
        val root = volume.path.trimEnd('/') + "/"
        var left = used
        val out = ArrayList<StorageSlice>()
        for ((cat, color) in colors) {
            val bytes = lists[cat].orEmpty().asSequence().filter { it.path.startsWith(root) }.sumOf { it.size }.coerceAtMost(left)
            left -= bytes
            out += StorageSlice(cat.title, bytes, color, cat)
        }
        out += StorageSlice("Other", left, other)
        return out
    }
}

/**
 * Home → Internal storage, inside the app (no system Files app or Settings screen needed):
 * each volume's used / free space with a coloured breakdown, its top folders, and Browse.
 */
@Composable
fun StorageScreen(onBack: () -> Unit, onBrowse: (String) -> Unit, onCategory: (Category) -> Unit) {
    val context = LocalContext.current
    val settings by Graph.settings.collectAsState()
    val cats = StorageBreakdown.colors.keys.toList()
    val states = cats.map { c ->
        remember(c, settings.showHidden) { CategoryRepository.loader(c, settings.showHidden).also { it.ensureLoaded() } }.state.collectAsState()
    }
    val lists = cats.mapIndexed { i, c -> c to states[i].value.value }.toMap()
    val vols by produceState<List<Pair<StorageVolume, List<FileItem>>>?>(null, AppState.refreshTick, settings.showHidden) {
        value = withContext(Dispatchers.IO) {
            val filter = Graph.hiddenFilter(settings.showHidden)
            StoragePaths.volumes(context).map { v ->
                val dirs = File(v.path).listFiles().orEmpty()
                    .filter { it.isDirectory && filter.visibleEntry(it) }
                    .map { FileItem.of(it, countChildren = true) }
                    .sortedBy { it.name.lowercase() }
                v to dirs
            }
        }
    }
    val infos = remember(vols, lists) { vols?.map { (v, dirs) -> VolumeInfo(v, StorageBreakdown.slices(v, lists), dirs) } }
    StorageContent(infos, onBack, onBrowse, onCategory)
}

@Composable
fun StorageContent(
    volumes: List<VolumeInfo>?,
    onBack: () -> Unit,
    onBrowse: (String) -> Unit,
    onCategory: (Category) -> Unit,
) {
    GlassScaffold(topBar = { GlassTopBar("Storage", onBack = onBack) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (volumes == null) {
                item { Text("Reading storage…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            }
            volumes.orEmpty().forEachIndexed { index, info ->
                item(key = "vol:" + info.volume.path) { VolumeCard(info, index == 0, onBrowse, onCategory) }
                if (info.folders.isNotEmpty()) {
                    item(key = "hdr:" + info.volume.path) {
                        Text(
                            "Folders in ${info.volume.label}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp, top = 6.dp),
                        )
                    }
                    item(key = "dirs:" + info.volume.path) {
                        Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(22.dp)).padding(vertical = 6.dp)) {
                            info.folders.forEach { f -> FolderRow(f) { onBrowse(f.path) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VolumeCard(info: VolumeInfo, internal: Boolean, onBrowse: (String) -> Unit, onCategory: (Category) -> Unit) {
    val v = info.volume
    val used = (v.total - v.free).coerceAtLeast(0)
    Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(22.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (internal) Icons.Filled.PhoneAndroid else Icons.Filled.SdCard, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(v.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${formatSize(used)} used of ${formatSize(v.total)} · ${formatSize(v.free)} free",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val pct = if (v.total > 0) (used * 100 / v.total).toInt() else 0
            Text("$pct%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(14.dp))
        // One bar: each category's share, then Other, then the free space.
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))) {
            val total = v.total.coerceAtLeast(1).toFloat()
            info.slices.forEach { s ->
                val w = s.bytes / total
                if (w > 0.002f) Box(Modifier.weight(w).fillMaxHeight().background(s.color))
            }
            val free = v.free / total
            if (free > 0.002f) Spacer(Modifier.weight(free))
        }
        Spacer(Modifier.height(12.dp))
        info.slices.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .then(if (s.category != null) Modifier.clickable { onCategory(s.category) } else Modifier)
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(s.color))
                Spacer(Modifier.width(10.dp))
                Text(s.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(formatSize(s.bytes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)))
            Spacer(Modifier.width(10.dp))
            Text("Free", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(formatSize(v.free), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = { onBrowse(v.path) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Filled.FolderOpen, null)
            Spacer(Modifier.width(8.dp))
            Text("Browse ${v.label.lowercase()}")
        }
    }
}

@Composable
private fun FolderRow(f: FileItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Folder, null, tint = Color(0xFFF59E0B))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (f.childCount >= 0) Text(
                if (f.childCount == 1) "1 item" else "${f.childCount} items",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
