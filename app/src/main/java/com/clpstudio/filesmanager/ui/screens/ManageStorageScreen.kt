package com.clpstudio.filesmanager.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.clpstudio.filesmanager.data.ManageStorageData
import com.clpstudio.filesmanager.data.ManageStorageRepository
import com.clpstudio.filesmanager.data.StorageKind
import com.clpstudio.filesmanager.data.StorageMath
import com.clpstudio.filesmanager.data.StoragePaths
import com.clpstudio.filesmanager.data.StorageVolume
import com.clpstudio.filesmanager.data.VolumeReport
import com.clpstudio.filesmanager.ui.AppState
import com.clpstudio.filesmanager.ui.components.ConfirmDialog
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.components.formatSize
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.glass.glassPanel

/** Our palette for the usage bar: one colour per row, blue first. */
fun storageKindColor(k: StorageKind): Color = when (k) {
    StorageKind.VIDEOS -> Color(0xFF1E6FF0)
    StorageKind.APKS -> Color(0xFF14B8A6)
    StorageKind.DOCUMENTS -> Color(0xFFF6B800)
    StorageKind.APPS -> Color(0xFF22C55E)
    StorageKind.OTHER -> Color(0xFFEC4899)
    StorageKind.ARCHIVES -> Color(0xFF8B5CF6)
    StorageKind.AUDIO -> Color(0xFFF97316)
    StorageKind.IMAGES -> Color(0xFF38BDF8)
    StorageKind.SYSTEM -> Color(0xFF94A3B8)
    StorageKind.BIN -> Color(0xFFEF4444)
}

/** Cleanup screen modes, carried in the route. */
object CleanupMode {
    const val DUPLICATES = "dup"
    const val LARGE = "large"
    fun suggestion(t: com.clpstudio.filesmanager.data.SuggestionType) = "sugg_${t.name}"
}

/** "…" while loading, "—" when it can't be known, else the size. */
private fun sizeOrDots(v: Long?, loaded: Boolean): String = when {
    v != null -> formatSize(v)
    !loaded -> "…"
    else -> "—"
}

/**
 * Tapping "Internal storage" on Home lands here: our own Manage storage screen, computed on the
 * device. It never opens the system Files app or the Settings storage page (both can be removed
 * or disabled).
 */
@Composable
fun ManageStorageScreen(
    onBack: () -> Unit,
    onBrowse: (String) -> Unit,
    onKind: (StorageKind, String) -> Unit,
    onCleanup: (String, String) -> Unit,
    onApps: () -> Unit,
    onBin: () -> Unit,
    onUnused: () -> Unit = onApps,
) {
    val context = LocalContext.current
    // Item 36: the permission is live (resume + app-op watcher), not read from the last storage report.
    val usageAccess by com.clpstudio.filesmanager.data.UsageAccess.granted.collectAsState()
    LaunchedEffect(Unit) { com.clpstudio.filesmanager.data.UsageAccess.watch(context) }
    val state by ManageStorageRepository.state().collectAsState()
    val dups by ManageStorageRepository.duplicates.state.collectAsState()
    val vols = remember { StoragePaths.volumes(context) }
    LaunchedEffect(Unit) { if (state.loaded) ManageStorageRepository.refresh() else ManageStorageRepository.ensureLoaded() }
    var firstTick by remember { mutableStateOf(true) }
    val fileVersion by com.clpstudio.filesmanager.data.FileEvents.version.collectAsState()
    LaunchedEffect(AppState.refreshTick) { if (firstTick) firstTick = false else ManageStorageRepository.scheduleRefresh(300) }
    // Files deleted / moved anywhere: recompute once things settle (a burst = one recompute).
    var firstVersion by remember { mutableStateOf(true) }
    LaunchedEffect(fileVersion) { if (firstVersion) firstVersion = false else ManageStorageRepository.scheduleRefresh(1_500) }
    // Back from the Usage access page: read sizes again.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        val now = com.clpstudio.filesmanager.data.UsageAccess.check(context)
        // Also when the last report (or one still running) read it before the change.
        val had = state.value?.usageAccess
        if (had != null && had != now) ManageStorageRepository.refresh()
        onPauseOrDispose { }
    }
    ManageStorageContent(
        data = state.value?.let { if (usageAccess && !it.usageAccess) it.copy(usageAccess = true, unusedApps = null) else it },
        volumes = vols,
        duplicates = dups.value,
        onBack = onBack,
        onBrowse = onBrowse,
        onKind = onKind,
        onCleanup = onCleanup,
        onApps = onApps,
        onBin = onBin,
        onUnused = onUnused,
        onUsageSettings = { com.clpstudio.filesmanager.data.UsageAccess.openSettings(context) },
    )
}

@Composable
fun ManageStorageContent(
    data: ManageStorageData?,
    volumes: List<StorageVolume>,
    duplicates: Map<String, List<DuplicateSet>>?,
    onBack: () -> Unit,
    onBrowse: (String) -> Unit,
    onKind: (StorageKind, String) -> Unit,
    onCleanup: (String, String) -> Unit,
    onApps: () -> Unit,
    onBin: () -> Unit,
    onUsageSettings: () -> Unit,
    onUnused: () -> Unit = onApps,
    initiallyExpanded: Boolean = false,
    initiallyMoreSuggestions: Boolean = false,
) {
    var info by remember { mutableStateOf<StorageKind?>(null) }
    var usageExplainer by remember { mutableStateOf(false) }
    var moreSuggestions by rememberSaveable { mutableStateOf(initiallyMoreSuggestions) }
    val reports = data?.volumes
    val primaryVol = reports?.firstOrNull()?.volume ?: volumes.firstOrNull()
    val primary = reports?.firstOrNull()

    GlassScaffold(topBar = { GlassTopBar(title = "Manage storage", onBack = onBack) }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val side = ((maxWidth - 840.dp) / 2).coerceAtLeast(16.dp)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = side, end = side, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (primaryVol != null) item("browse") {
                    NavRow(
                        icon = { Icon(Icons.Filled.FolderOpen, null, tint = MaterialTheme.colorScheme.primary) },
                        title = "Browse internal storage",
                        subtitle = "All folders and files",
                        trailing = null,
                        onClick = { onBrowse(primaryVol.path) },
                    )
                }
                val shown = reports?.map { it.volume to it } ?: volumes.map { it to null }
                shown.forEachIndexed { i, (vol, rep) ->
                    item("vol_${vol.path}") {
                        VolumeCard(
                            volume = vol,
                            report = rep,
                            isPrimary = i == 0,
                            loaded = data != null,
                            duplicates = duplicates?.get(vol.path),
                            initiallyExpanded = initiallyExpanded,
                            onKind = { k ->
                                when (k) {
                                    StorageKind.APPS, StorageKind.APKS -> onApps()
                                    StorageKind.BIN -> onBin()
                                    StorageKind.SYSTEM, StorageKind.OTHER -> info = k
                                    else -> onKind(k, vol.path)
                                }
                            },
                            onDuplicates = { onCleanup(CleanupMode.DUPLICATES, vol.path) },
                            onLarge = { onCleanup(CleanupMode.LARGE, vol.path) },
                        )
                    }
                    if (i == 0) {
                        val sugg = primary?.suggestions.orEmpty()
                        if (sugg.isNotEmpty()) {
                            item("sugg_title") {
                                Text("Suggestions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
                            }
                            val visible = if (moreSuggestions) sugg else sugg.take(2)
                            visible.chunked(2).forEachIndexed { r, pair ->
                                item("sugg_row_$r") {
                                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        pair.forEach { s ->
                                            SuggestionCard(
                                                text = StorageMath.suggestionText(s, formatSize(s.size)),
                                                action = StorageMath.suggestionAction(s.type),
                                                modifier = Modifier.weight(1f),
                                                onClick = { onCleanup(CleanupMode.suggestion(s.type), vol.path) },
                                            )
                                        }
                                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                            if (sugg.size > 2) item("sugg_more") {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    MoreToggle(if (moreSuggestions) "View less" else "View more", moreSuggestions) { moreSuggestions = !moreSuggestions }
                                }
                            }
                        }
                        item("rows") {
                            Column(Modifier.fillMaxWidth().glassPanel()) {
                                NavRow(
                                    icon = { Icon(Icons.Filled.Delete, null, tint = storageKindColor(StorageKind.BIN)) },
                                    title = "Recycle bin",
                                    subtitle = "Files sitting in the Bin",
                                    trailing = sizeOrDots(primary?.binBytes, data != null),
                                    onClick = onBin,
                                    panel = false,
                                )
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                NavRow(
                                    icon = { Icon(Icons.Filled.Apps, null, tint = storageKindColor(StorageKind.APPS)) },
                                    title = "Unused apps",
                                    subtitle = "Apps you haven't used in the past 30 days",
                                    trailing = when {
                                        data == null -> "…"
                                        data.unusedApps != null -> data.unusedApps.toString()
                                        data.usageAccess -> "…"
                                        else -> "—"
                                    },
                                    onClick = { if (data?.usageAccess == true) onUnused() else usageExplainer = true },
                                    panel = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    info?.let { k ->
        val text = if (k == StorageKind.SYSTEM) {
            "Space used by Android itself, app caches the system keeps, and anything this app can't read. It can't be cleaned from here."
        } else {
            "Files that aren't videos, images, audio, documents, installation files or compressed files, for example app folders, backups and downloads without a known type. Browse internal storage to see them."
        }
        ConfirmDialog(title = k.label, text = text, confirm = "OK", onConfirm = {}, onDismiss = { info = null })
    }
    if (usageExplainer) {
        ConfirmDialog(
            title = "Usage access",
            text = "To see app sizes and which apps you haven't used in 30 days, allow Usage access for Files. Android keeps this on your device; nothing is sent anywhere.",
            confirm = "Open settings",
            onConfirm = { usageExplainer = false; onUsageSettings() },
            onDismiss = { usageExplainer = false },
        )
    }
}

@Composable
private fun VolumeCard(
    volume: StorageVolume,
    report: VolumeReport?,
    isPrimary: Boolean,
    loaded: Boolean,
    duplicates: List<DuplicateSet>?,
    initiallyExpanded: Boolean,
    onKind: (StorageKind) -> Unit,
    onDuplicates: () -> Unit,
    onLarge: () -> Unit,
) {
    var expanded by rememberSaveable(volume.path) { mutableStateOf(initiallyExpanded) }
    val usage = report?.breakdown
    val sizes = usage?.sizes
    val left = StorageKind.leftColumn.filter { isPrimary || it in volumeKinds }
    val right = StorageKind.rightColumn.filter { isPrimary || it in volumeKinds }
    val rows = maxOf(left.size, right.size)
    val shownRows = if (expanded) rows else minOf(2, rows)
    Column(Modifier.fillMaxWidth().glassPanel().animateContentSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text(volume.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${StorageMath.percentUsed(volume.total, volume.free)}% used",
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${formatSize((volume.total - volume.free).coerceAtLeast(0))} / ${formatSize(volume.total)}",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        UsageBar(volume.total, volume.free, sizes)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)) {
                left.take(shownRows).forEach { k -> KindRow(k, sizeOrDots(sizes?.get(k), loaded && report != null), clickable = true) { onKind(k) } }
            }
            Column(Modifier.weight(1f)) {
                right.take(shownRows).forEach { k -> KindRow(k, sizeOrDots(sizes?.get(k), loaded && report != null), clickable = true) { onKind(k) } }
            }
        }
        if (rows > 2) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                MoreToggle(if (expanded) "Show less" else "Show more", expanded) { expanded = !expanded }
            }
        }
        if (isPrimary) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val dupBytes = duplicates?.sumOf { it.reclaimable }
                CleanTile("Duplicate files", if (duplicates == null) "…" else formatSize(dupBytes ?: 0), Modifier.weight(1f), onDuplicates)
                CleanTile("Large files", if (report == null) "…" else formatSize(report.largeSize), Modifier.weight(1f), onLarge)
            }
        }
    }
}

/** A volume other than the primary one has no apps / system / bin share. */
private val volumeKinds = setOf(
    StorageKind.VIDEOS, StorageKind.APKS, StorageKind.DOCUMENTS, StorageKind.OTHER,
    StorageKind.ARCHIVES, StorageKind.AUDIO, StorageKind.IMAGES,
)

@Composable
private fun UsageBar(total: Long, free: Long, sizes: Map<StorageKind, Long?>?) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Row(
        Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(track),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (total <= 0) return@Row
        val used = (total - free).coerceAtLeast(0)
        val parts = if (sizes == null) {
            // Still computing: one blue segment for the used share.
            listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) to used)
        } else {
            (StorageKind.leftColumn + StorageKind.rightColumn).sortedBy { barOrder.indexOf(it) }
                .mapNotNull { k -> sizes[k]?.takeIf { it > 0 }?.let { storageKindColor(k) to it } }
        }
        val partsSum = parts.sumOf { it.second }.coerceAtLeast(1)
        // The bar shows used space; categories are scaled into it so rounding never overflows.
        val scale = if (partsSum > used && used > 0) used.toDouble() / partsSum else 1.0
        parts.forEach { (c, v) ->
            val w = (v * scale / total).toFloat()
            if (w > 0.002f) Box(Modifier.weight(w).fillMaxHeight14().background(c))
        }
        val rest = (1f - parts.sumOf { it.second * scale / total }.toFloat()).coerceAtLeast(0f)
        if (rest > 0.002f) Spacer(Modifier.weight(rest))
    }
}

private fun Modifier.fillMaxHeight14() = this.height(14.dp)

private val barOrder = listOf(
    StorageKind.VIDEOS, StorageKind.IMAGES, StorageKind.AUDIO, StorageKind.DOCUMENTS, StorageKind.APKS,
    StorageKind.ARCHIVES, StorageKind.APPS, StorageKind.OTHER, StorageKind.BIN, StorageKind.SYSTEM,
)

@Composable
private fun KindRow(k: StorageKind, size: String, clickable: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(10.dp))
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(storageKindColor(k)))
        Spacer(Modifier.width(12.dp))
        Text(k.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(size, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun MoreToggle(label: String, open: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CleanTile(title: String, size: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(2.dp))
        Text(size, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SuggestionCard(text: String, action: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.fillMaxHeight().heightIn(min = 132.dp).glassPanel(RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        FilledTonalButton(onClick = onClick, modifier = Modifier.padding(top = 14.dp).align(Alignment.End)) { Text(action) }
    }
}

@Composable
private fun NavRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    trailing: String?,
    onClick: () -> Unit,
    panel: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().then(if (panel) Modifier.glassPanel() else Modifier).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
}
