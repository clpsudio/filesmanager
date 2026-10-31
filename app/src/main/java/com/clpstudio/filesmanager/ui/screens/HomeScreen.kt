package com.clpstudio.filesmanager.ui.screens

import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SdCard
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.Category
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.HomeRepository
import com.clpstudio.filesmanager.data.HomeSnapshot
import com.clpstudio.filesmanager.data.Loadable
import com.clpstudio.filesmanager.data.map
import com.clpstudio.filesmanager.playback.PlayerUi
import com.clpstudio.filesmanager.ui.AppState
import com.clpstudio.filesmanager.ui.components.FileTile
import com.clpstudio.filesmanager.ui.components.SearchLauncher
import com.clpstudio.filesmanager.ui.components.formatSize
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.glass.LocalGlass
import com.clpstudio.filesmanager.ui.glass.glassBar
import com.clpstudio.filesmanager.ui.glass.glassPanel
import com.clpstudio.filesmanager.ui.glass.pressScale

enum class HomeTile(val title: String, val icon: ImageVector, val color: Color) {
    RECENT("Recent", Icons.Filled.Schedule, Color(0xFF1E6FF0)),
    FAVORITES("Favorites", Icons.Filled.Star, Color(0xFFF59E0B)),
    PHOTOS("Photos", Icons.Filled.Image, Color(0xFF16A34A)),
    MEDIA("Media", Icons.Filled.PhotoLibrary, Color(0xFFEA580C)),
    VIDEOS("Videos", Icons.Filled.VideoLibrary, Color(0xFFDC2626)),
    AUDIO("Audios", Icons.Filled.AudioFile, Color(0xFF9333EA)),
    DOCUMENTS("Documents", Icons.Filled.Description, Color(0xFF2563EB)),
    DOWNLOADS("Downloads", Icons.Filled.Download, Color(0xFF0891B2)),
    APPS("Apps", Icons.Filled.Android, Color(0xFF65A30D)),
    INTERNAL("Internal storage", Icons.Filled.Storage, Color(0xFF475569)),
    BIN("Bin", Icons.Filled.Delete, Color(0xFFE11D48)),
    HIDDEN("Hidden files", Icons.Filled.VisibilityOff, Color(0xFF64748B)),
    VAULT("Vault", Icons.Filled.Lock, Color(0xFFD97706)),
}

private val homeCategories = listOf(
    HomeTile.PHOTOS to Category.PHOTOS, HomeTile.MEDIA to Category.MEDIA, HomeTile.VIDEOS to Category.VIDEOS, HomeTile.AUDIO to Category.AUDIO,
    HomeTile.DOCUMENTS to Category.DOCUMENTS, HomeTile.DOWNLOADS to Category.DOWNLOADS,
)

@Immutable
data class HomeState(
    val used: Long = 0,
    val total: Long = 0,
    val counts: Map<HomeTile, String> = emptyMap(),
    val recent: List<FileItem> = emptyList(),
    /** Mounted SD cards (Home shows a box for each next to "Device"). */
    val sdCards: List<com.clpstudio.filesmanager.data.StorageBox> = emptyList(),
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
    /** Opens a storage root (the SD card box) in the folder browser. */
    onBrowseRoot: (String) -> Unit = { onBrowse() },
) {
    val context = LocalContext.current
    val settings by Graph.settings.collectAsState()
    // Loading lives in HomeRepository (app scope, keeps the last good values, retries once):
    // leaving Home or an auto-refresh racing the first load can no longer cancel or blank it.
    val showHidden = settings.showHidden
    val tick = AppState.refreshTick
    LaunchedEffect(showHidden, tick) { HomeRepository.onShow(showHidden, tick) }
    val snap by HomeRepository.state(showHidden).collectAsState()
    val favorites by com.clpstudio.filesmanager.data.Favorites.paths.collectAsState()
    val vaultState by com.clpstudio.filesmanager.vault.Vault.state.collectAsState()
    val vaultCount by com.clpstudio.filesmanager.vault.Vault.count.collectAsState()
    val vaultLabel = when (vaultState) {
        com.clpstudio.filesmanager.vault.VaultState.NOT_SET_UP -> "Set up"
        com.clpstudio.filesmanager.vault.VaultState.LOCKED -> "Locked · $vaultCount"
        com.clpstudio.filesmanager.vault.VaultState.UNLOCKED -> "$vaultCount file${if (vaultCount == 1) "" else "s"}"
    }
    val state = remember(snap, favorites.size, vaultLabel) { homeStateOf(snap, favorites.size, vaultLabel) }
    HomeContent(state, onTile, onSearch, onSettings, onBrowse, onOpenRecent, player, onOpenPlayer, showHiddenTile = showHidden, onBrowseRoot = onBrowseRoot)
}

/** Maps the repository snapshot to what Home draws; "…" while a count is still loading, never a blank or a false 0. */
internal fun homeStateOf(snap: HomeSnapshot, favorites: Int, vaultLabel: String = "Set up"): HomeState {
    val counts = mutableMapOf<HomeTile, String>()
    for ((tile, cat) in homeCategories) counts[tile] = HomeRepository.countLabel(snap.counts[cat])
    counts[HomeTile.RECENT] = HomeRepository.countLabel(snap.recent.map { it.size })
    counts[HomeTile.FAVORITES] = HomeRepository.countLabel(Loadable(value = favorites, loaded = true))
    counts[HomeTile.BIN] = HomeRepository.countLabel(snap.bin)
    counts[HomeTile.VAULT] = vaultLabel
    counts[HomeTile.HIDDEN] = "All in one place"
    counts[HomeTile.APPS] = "APKs & installed"
    counts[HomeTile.INTERNAL] = "System storage"
    val (used, total) = snap.storage.value ?: (0L to 0L)
    return HomeState(used = used, total = total, counts = counts, recent = snap.recent.value.orEmpty().take(20), sdCards = snap.sdCards.value.orEmpty())
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
    /** "Hidden files" tile: only while Settings → Show hidden files is on. */
    showHiddenTile: Boolean = false,
    onBrowseRoot: (String) -> Unit = {},
) {
    GlassScaffold(
        topBar = {
            Box(Modifier.fillMaxWidth().glassBar(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = HomeMaxContentWidth + 32.dp).fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Files", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                }
                Spacer(Modifier.height(6.dp))
                SearchLauncher("Search by name or date", onSearch)
            }
            }
        },
        bottomBar = {
            if (player.hasMedia && !player.isVideo) MiniPlayer(player, onOpenPlayer)
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cols = homeGridColumns(maxWidth)
            // Keep the content from stretching edge-to-edge on wide tablets.
            val side = maxOf(16.dp, (maxWidth - HomeMaxContentWidth) / 2)
            // "Hidden files" shows only while Settings → Show hidden files is on.
            val homeTiles = remember(showHiddenTile) { HomeTile.entries.filter { it != HomeTile.HIDDEN || showHiddenTile } }
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                contentPadding = PaddingValues(start = side, end = side, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "storage") {
                    // "Device" and, while a card is mounted, "SD card": used / total, tap opens that root.
                    // Stacked, one full-width box each, so names and sizes never get cut on a narrow phone.
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        StorageCard(if (state.sdCards.isEmpty()) "Browse all files" else "Device storage", Icons.Filled.Storage, state.used, state.total, onBrowse)
                        state.sdCards.forEach { card ->
                            StorageCard(card.label, Icons.Filled.SdCard, card.used, card.total, { onBrowseRoot(card.path) })
                        }
                    }
                }
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
                items(homeTiles, key = { it.name }) { tile ->
                    val sub = when {
                        tile == HomeTile.INTERNAL && state.total > 0 -> formatSize(state.used) + " used"
                        tile == HomeTile.APPS && state.counts[tile] == "APKs & installed" -> "APKs & apps"
                        else -> state.counts[tile]
                    }
                    CategoryTile(tile, sub, onClick = { onTile(tile) })
                }
            }
        }
    }
}

private val HomeMaxContentWidth = 1120.dp

/**
 * 3 category cards per row on phones, 4 on tablets in portrait, 6 on wide tablets/landscape
 * (12 tiles fill every row: 3x4, 4x3, 6x2).
 */
internal fun homeGridColumns(width: Dp): Int = when {
    width < 600.dp -> 3
    width < 960.dp -> 4
    else -> 6
}

/** "1240 items" -> "1,240 items"; other subtitles unchanged. */
internal fun formatCountLabel(subtitle: String?): String? {
    if (subtitle == null) return null
    val m = Regex("""^(\d+) (items?)$""").matchEntire(subtitle) ?: return subtitle
    val n = m.groupValues[1].toLongOrNull() ?: return subtitle
    return java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(n) + " " + m.groupValues[2]
}

/**
 * Bright, saturated icon colours per tile. Dark uses the bright shade (>= 4.5:1 on its tinted
 * square); light uses a deeper shade of the same hue so the glyph stays >= 3:1 on its tint.
 */
internal fun tileAccent(tile: HomeTile, dark: Boolean): Color = when (tile) {
    HomeTile.RECENT -> if (dark) Color(0xFF5AA2FF) else Color(0xFF1D64D8)
    HomeTile.FAVORITES -> if (dark) Color(0xFFFFCC33) else Color(0xFFC98600)
    HomeTile.PHOTOS -> if (dark) Color(0xFF3DDC84) else Color(0xFF128A3E)
    HomeTile.MEDIA -> if (dark) Color(0xFFFF9442) else Color(0xFFD2560A)
    HomeTile.VIDEOS -> if (dark) Color(0xFFFF6464) else Color(0xFFD12A2A)
    HomeTile.AUDIO -> if (dark) Color(0xFFBB86FF) else Color(0xFF8136E6)
    HomeTile.DOCUMENTS -> if (dark) Color(0xFF7896FF) else Color(0xFF2F50E0)
    HomeTile.DOWNLOADS -> if (dark) Color(0xFF33D6F0) else Color(0xFF0A82A0)
    HomeTile.APPS -> if (dark) Color(0xFFA6E635) else Color(0xFF4C8A0A)
    HomeTile.INTERNAL -> if (dark) Color(0xFFA9B6C9) else Color(0xFF526073)
    HomeTile.BIN -> if (dark) Color(0xFFFF667A) else Color(0xFFD21F3C)
    HomeTile.VAULT -> if (dark) Color(0xFFFFB13B) else Color(0xFFC46A00)
    HomeTile.HIDDEN -> if (dark) Color(0xFFB4BFCE) else Color(0xFF55627A)
}

private fun tileLabel(tile: HomeTile): String = when (tile) {
    HomeTile.INTERNAL -> "Internal"
    else -> tile.title
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
private fun StorageCard(title: String, icon: ImageVector, used: Long, total: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier.fillMaxWidth().pressScale(interaction, 0.98f).glassPanel(RoundedCornerShape(28.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (total > 0) "${formatSize(used)} used of ${formatSize(total)}" else "Tap to browse",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
        Spacer(Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { if (total > 0) used.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            strokeCap = StrokeCap.Round,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun CategoryTile(tile: HomeTile, subtitle: String?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val dark = LocalGlass.current.dark
    val accent = tileAccent(tile, dark)
    Column(
        Modifier.fillMaxWidth().height(96.dp).pressScale(interaction).glassPanel(RoundedCornerShape(20.dp))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Icon in a coloured rounded square: the accent as a strong tint behind the bright glyph.
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp))
                .background(accent.copy(alpha = if (dark) 0.20f else 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(tile.icon, null, tint = accent, modifier = Modifier.size(21.dp))
        }
        Column {
            Text(
                tileLabel(tile), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatCountLabel(subtitle) ?: " ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Height the floating mini player takes at the bottom of a screen (lists add it to their bottom padding). */
val MiniPlayerHeight = 88.dp

/**
 * The audio mini player: artwork, name, play/pause, next, close and a thin progress line. Tap = full player.
 * Close stops playback (and its notification) and hides the bar.
 */
@Composable
fun MiniPlayer(
    player: PlayerUi,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    loopMode: com.clpstudio.filesmanager.data.LoopMode = com.clpstudio.filesmanager.playback.AudioLoop.mode,
    onLoop: () -> Unit = { com.clpstudio.filesmanager.playback.AudioLoop.toggle() },
) {
    val art = remember(player.artwork) {
        player.artwork?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    Column(
        modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)
            .glassBar(RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF9333EA).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                if (art != null) Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Filled.MusicNote, null, tint = Color(0xFFB57BFF))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(player.title.ifEmpty { "Playing" }, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (player.artist.isNotEmpty()) Text(player.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { player.controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                Icon(if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (player.isPlaying) "Pause" else "Play")
            }
            IconButton(onClick = { player.controller?.seekToNext() }) { Icon(Icons.Filled.SkipNext, "Next") }
            // Same audio Loop as the full player and the notification (item 30).
            val loopOn = loopMode != com.clpstudio.filesmanager.data.LoopMode.OFF
            IconButton(onClick = onLoop) {
                Icon(
                    if (loopOn) Icons.Filled.RepeatOne else Icons.Filled.Repeat, if (loopOn) "Loop on" else "Loop off",
                    tint = if (loopOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { player.controller?.let { runCatching { it.stop(); it.clearMediaItems() } } }) { Icon(Icons.Filled.Close, "Close player") }
        }
        LinearProgressIndicator(
            progress = { if (player.duration > 0) (player.position.toFloat() / player.duration).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 6.dp).height(2.dp).clip(RoundedCornerShape(1.dp)),
            strokeCap = StrokeCap.Round,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}
