package com.clpstudio.filesmanager.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.Category
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.StoragePaths
import com.clpstudio.filesmanager.playback.PlayerUi
import com.clpstudio.filesmanager.ui.AppState
import com.clpstudio.filesmanager.ui.components.FileTile
import com.clpstudio.filesmanager.ui.components.SearchLauncher
import com.clpstudio.filesmanager.ui.components.formatSize
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.glass.glassBar
import com.clpstudio.filesmanager.ui.glass.glassPanel
import com.clpstudio.filesmanager.ui.glass.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class HomeTile(val title: String, val icon: ImageVector, val color: Color) {
    RECENT("Recent", Icons.Filled.Schedule, Color(0xFF1E6FF0)),
    PHOTOS("Photos", Icons.Filled.Image, Color(0xFF16A34A)),
    VIDEOS("Videos", Icons.Filled.VideoLibrary, Color(0xFFDC2626)),
    AUDIO("Audios", Icons.Filled.AudioFile, Color(0xFF9333EA)),
    DOCUMENTS("Documents", Icons.Filled.Description, Color(0xFF2563EB)),
    DOWNLOADS("Downloads", Icons.Filled.Download, Color(0xFF0891B2)),
    APPS("Apps", Icons.Filled.Android, Color(0xFF65A30D)),
    INTERNAL("Internal storage", Icons.Filled.Storage, Color(0xFF475569)),
    BIN("Bin", Icons.Filled.Delete, Color(0xFFE11D48)),
    VAULT("Vault", Icons.Filled.Lock, Color(0xFFD97706)),
}

@Immutable
data class HomeState(
    val used: Long = 0,
    val total: Long = 0,
    val counts: Map<HomeTile, String> = emptyMap(),
    val recent: List<FileItem> = emptyList(),
)

@Composable
fun HomeScreen(
    onTile: (HomeTile) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onBrowse: () -> Unit,
    onOpenRecent: (FileItem, List<FileItem>) -> Unit,
    player: PlayerUi,
    onOpenPlayer: () -> Unit,
) {
    val context = LocalContext.current
    val settings by Graph.settings.collectAsState()
    val state by produceState(HomeState(), AppState.refreshTick, settings.showHidden) {
        value = withContext(Dispatchers.IO) {
            val vol = StoragePaths.volumes(context).first()
            val filter = Graph.hiddenFilter(settings.showHidden)
            val recent = runCatching { Graph.media.category(Category.RECENT, filter) }.getOrDefault(emptyList())
            HomeState(used = vol.total - vol.free, total = vol.total, recent = recent.take(20))
        }
        value = withContext(Dispatchers.IO) {
            val filter = Graph.hiddenFilter(settings.showHidden)
            val counts = mutableMapOf<HomeTile, String>()
            fun put(t: HomeTile, n: Int) { counts[t] = "$n item" + if (n == 1) "" else "s" }
            for ((tile, cat) in listOf(
                HomeTile.PHOTOS to Category.PHOTOS, HomeTile.VIDEOS to Category.VIDEOS, HomeTile.AUDIO to Category.AUDIO,
                HomeTile.DOCUMENTS to Category.DOCUMENTS, HomeTile.DOWNLOADS to Category.DOWNLOADS,
            )) {
                runCatching { Graph.media.category(cat, filter) }.getOrNull()?.let { put(tile, it.size) }
            }
            put(HomeTile.RECENT, value.recent.size)
            runCatching { Graph.bin.list() }.getOrNull()?.let { put(HomeTile.BIN, it.size) }
            counts[HomeTile.VAULT] = "Coming soon"
            counts[HomeTile.APPS] = "APKs & installed"
            counts[HomeTile.INTERNAL] = "System storage"
            value.copy(counts = counts)
        }
    }
    HomeContent(state, onTile, onSearch, onSettings, onBrowse, onOpenRecent, player, onOpenPlayer)
}

@Composable
fun HomeContent(
    state: HomeState,
    onTile: (HomeTile) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onBrowse: () -> Unit,
    onOpenRecent: (FileItem, List<FileItem>) -> Unit,
    player: PlayerUi = PlayerUi(),
    onOpenPlayer: () -> Unit = {},
) {
    GlassScaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().glassBar().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Files", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                }
                Spacer(Modifier.height(6.dp))
                SearchLauncher("Search by name or date", onSearch)
            }
        },
        bottomBar = {
            if (player.hasMedia && !player.isVideo) MiniPlayer(player, onOpenPlayer)
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "storage") { StorageCard(state.used, state.total, onBrowse) }
            if (state.recent.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "recentHeader") {
                    SectionTitle("Recent files", onMore = { onTile(HomeTile.RECENT) })
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "recentRow") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.recent, key = { it.path }) { f ->
                            FileTile(f, false, onClick = { onOpenRecent(f, state.recent) }, onLongClick = {}, modifier = Modifier.width(96.dp))
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "catHeader") { SectionTitle("Categories") }
            items(HomeTile.entries, key = { it.name }) { tile ->
                CategoryTile(tile, state.counts[tile], onClick = { onTile(tile) })
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, onMore: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (onMore != null) {
            Text(
                "See all", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onMore).padding(6.dp),
            )
        }
    }
}

@Composable
private fun StorageCard(used: Long, total: Long, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().pressScale(interaction, 0.98f).glassPanel(RoundedCornerShape(28.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Storage, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Browse all files", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (total > 0) "${formatSize(used)} used of ${formatSize(total)}" else "Internal storage",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
        Spacer(Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { if (total > 0) used.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun CategoryTile(tile: HomeTile, subtitle: String?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().pressScale(interaction).glassPanel(RoundedCornerShape(24.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(16.dp),
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(tile.color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(tile.icon, null, tint = tile.color)
        }
        Spacer(Modifier.height(12.dp))
        Text(tile.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            subtitle ?: " ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun MiniPlayer(player: PlayerUi, onOpen: () -> Unit) {
    val art = remember(player.artwork) {
        player.artwork?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)
            .glassBar(RoundedCornerShape(24.dp)).clickable(onClick = onOpen).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF9333EA).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            if (art != null) Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Filled.MusicNote, null, tint = Color(0xFF9333EA))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(player.title.ifEmpty { "Playing" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (player.artist.isNotEmpty()) Text(player.artist, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { player.controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
            Icon(if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (player.isPlaying) "Pause" else "Play")
        }
        IconButton(onClick = { player.controller?.seekToNext() }) { Icon(Icons.Filled.SkipNext, "Next") }
    }
}
