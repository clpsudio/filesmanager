package com.clpstudio.filesmanager.ui.components

import androidx.compose.material.icons.filled.Slideshow
import com.clpstudio.filesmanager.data.distinctByPath
import com.clpstudio.filesmanager.data.uniqueSections

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import com.clpstudio.filesmanager.data.DateSections
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.FileView
import com.clpstudio.filesmanager.ui.glass.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** "31 Oct 11:09" (with the year when it isn't this year). */
fun formatShortDateTime(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    val thisYear = Calendar.getInstance().get(Calendar.YEAR) == c.get(Calendar.YEAR)
    return SimpleDateFormat(if (thisYear) "d MMM HH:mm" else "d MMM yyyy", Locale.getDefault()).format(Date(millis))
}

/** Width / height of photos and videos, read once (EXIF / metadata rotation applied). */
object MediaRatio {
    private val cache = ConcurrentHashMap<String, Float>()
    fun cached(item: FileItem): Float? = cache[item.path]

    suspend fun of(item: FileItem): Float = cache[item.path] ?: withContext(Dispatchers.IO) {
        val r = runCatching {
            when (item.kind) {
                FileKind.IMAGE -> {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(item.path, o)
                    var w = o.outWidth
                    var h = o.outHeight
                    val rot = runCatching { ExifInterface(item.path).rotationDegrees }.getOrDefault(0)
                    if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                    if (w > 0 && h > 0) w.toFloat() / h else 1f
                }
                FileKind.VIDEO -> {
                    val m = MediaMetadataRetriever()
                    try {
                        m.setDataSource(item.path)
                        var w = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        var h = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        val rot = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                        if (w > 0 && h > 0) w.toFloat() / h else 16f / 9f
                    } finally {
                        m.release()
                    }
                }
                FileKind.PDF, FileKind.DOCUMENT, FileKind.TEXT -> 0.75f
                else -> 1f
            }
        }.getOrDefault(1f).coerceIn(0.4f, 2.5f)
        cache[item.path] = r
        r
    }
}

/** Selection mark on a tile: a filled check when selected, an empty ring in selection mode. */
@Composable
private fun SelectMark(selected: Boolean, selectionMode: Boolean, modifier: Modifier) {
    if (selected) {
        Icon(
            Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
            modifier = modifier.size(22.dp).background(Color.White, CircleShape),
        )
    } else if (selectionMode) {
        Icon(Icons.Outlined.Circle, null, tint = Color.White, modifier = modifier.size(22.dp).background(Color(0x33000000), CircleShape))
    }
}

/** Dark bottom gradient with white text lines, drawn over a tile. */
@Composable
private fun NameOverlay(lines: List<String>, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
            .padding(start = 7.dp, end = 7.dp, top = 18.dp, bottom = 5.dp),
    ) {
        lines.forEachIndexed { i, l ->
            Text(
                l, color = if (i == 0) Color.White else Color.White.copy(alpha = 0.8f),
                fontSize = if (i == 0) 11.5.sp else 10.sp, lineHeight = 13.sp,
                fontWeight = if (i == 0) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
            )
        }
    }
}

private fun sizeLine(item: FileItem): String = if (item.isDir) {
    if (item.childCount >= 0) "${item.childCount} item" + (if (item.childCount == 1) "" else "s") else ""
} else formatSize(item.size)

/** Square tile with the name on the thumbnail (Grid view). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridTile(item: FileItem, selected: Boolean, selectionMode: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.padding(2.dp).fillMaxWidth().aspectRatio(1f)
            .pressScale(interaction)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = {}),
    ) {
        ThumbFill(item, corner = 12.dp)
        NameOverlay(listOf(item.name), Modifier.align(Alignment.BottomCenter))
        if (selected) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)))
        SelectMark(selected, selectionMode, Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

/** Large tile in a staggered grid, keeping the photo's real shape, with name, date and size. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LargeTile(item: FileItem, selected: Boolean, selectionMode: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val ratio by produceState(MediaRatio.cached(item) ?: if (item.kind == FileKind.VIDEO) 16f / 9f else 1f, item.path) { value = MediaRatio.of(item) }
    Box(
        modifier.padding(3.dp).fillMaxWidth().aspectRatio(ratio)
            .pressScale(interaction)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = {}),
    ) {
        ThumbFill(item, corner = 14.dp)
        NameOverlay(listOf(item.name, formatShortDateTime(item.modified) + " · " + sizeLine(item)), Modifier.align(Alignment.BottomCenter))
        if (selected) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)))
        SelectMark(selected, selectionMode, Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}

/** List row: thumbnail, name, "31 Oct 11:09" under it, size on the right. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(item: FileItem, selected: Boolean, selectionMode: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.fillMaxWidth()
            .pressScale(interaction, 0.98f)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = {})
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FileThumb(item, 48.dp, corner = 10.dp)
            if (selected || selectionMode) SelectMark(selected, selectionMode, Modifier.align(Alignment.BottomEnd).padding(0.dp).size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(formatShortDateTime(item.modified), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        Text(sizeLine(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 14.dp, bottom = 6.dp),
    )
}

/** The folder a zip was just extracted to (etc.) gets a soft accent glow for a few seconds. */
@Composable
private fun highlightModifier(path: String): Modifier =
    if (com.clpstudio.filesmanager.ui.AppState.highlight == path)
        Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
    else Modifier

/** Column count for the width: Grid ~128dp tiles (3 on a phone, 6 on a tablet), Large 2/3/4. */
fun columnsFor(view: FileView, widthDp: Float): Int = when (view) {
    FileView.GRID -> (widthDp / 128f).toInt().coerceAtLeast(3)
    FileView.LARGE -> when { widthDp < 600 -> 2; widthDp < 1000 -> 3; else -> 4 }
    FileView.LIST -> 1
}

/**
 * Files in the chosen view (Grid / Large / List), with long-press drag selection and, when
 * [sections] is on (date sort), Today / Yesterday / This week / This month / Older headers.
 * [onClick] gets taps (in selection mode the screen toggles; outside it opens).
 */
@Composable
fun FileCollection(
    items: List<FileItem>,
    view: FileView,
    selection: SelectionState,
    onClick: (FileItem) -> Unit,
    contentPadding: PaddingValues,
    sections: Boolean = false,
    modifier: Modifier = Modifier,
    initialIndex: Int = 0,
    onFirstVisible: (Int) -> Unit = {},
) {
    // Unique paths and section labels: duplicate keys crash lazy grids (see data/UniqueKeys.kt).
    val unique = remember(items) { items.distinctByPath() }
    val groups = remember(unique, sections) { uniqueSections(if (sections) DateSections.group(unique) else listOf("" to unique)) }
    val keys = remember(groups) { groups.flatMap { g -> g.second.map { it.path } } } // in drawn order, for drag-select ranges
    val mode = selection.active
    // "Real ratio" is for photos and videos only; any other file falls back to Grid.
    val realRatioOk = remember(items) { items.all { it.kind == FileKind.IMAGE || it.kind == FileKind.VIDEO } }
    val shown = if (view == FileView.LARGE && !realRatioOk) FileView.GRID else view
    BoxWithConstraints(modifier.fillMaxSize()) {
        val cols = columnsFor(shown, maxWidth.value)
        when (shown) {
            FileView.GRID -> {
                val state = rememberLazyGridState(initialIndex)
                LaunchedEffect(state.firstVisibleItemIndex) { onFirstVisible(state.firstVisibleItemIndex) }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(cols), state = state, contentPadding = contentPadding,
                    modifier = Modifier.fillMaxSize().dragToSelect(remember(state) { state.hitTester() }, keys, selection),
                ) {
                    for ((label, list) in groups) {
                        if (label.isNotEmpty()) item(key = "§$label", span = { GridItemSpan(maxLineSpan) }, contentType = "h") { SectionHeader(label) }
                        items(list.size, key = { list[it].path }, contentType = { "t" }) { i ->
                            val f = list[i]
                            GridTile(f, selection.isSelected(f.path), mode, { onClick(f) }, highlightModifier(f.path))
                        }
                    }
                }
            }
            FileView.LARGE -> {
                val state = rememberLazyStaggeredGridState(initialIndex)
                LaunchedEffect(state.firstVisibleItemIndex) { onFirstVisible(state.firstVisibleItemIndex) }
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(cols), state = state, contentPadding = contentPadding,
                    modifier = Modifier.fillMaxSize().dragToSelect(remember(state) { state.hitTester() }, keys, selection),
                ) {
                    for ((label, list) in groups) {
                        if (label.isNotEmpty()) item(key = "§$label", span = StaggeredGridItemSpan.FullLine, contentType = "h") { SectionHeader(label) }
                        items(list.size, key = { list[it].path }, contentType = { "t" }) { i ->
                            val f = list[i]
                            LargeTile(f, selection.isSelected(f.path), mode, { onClick(f) }, highlightModifier(f.path))
                        }
                    }
                }
            }
            FileView.LIST -> {
                val state = rememberLazyListState(initialIndex)
                LaunchedEffect(state.firstVisibleItemIndex) { onFirstVisible(state.firstVisibleItemIndex) }
                LazyColumn(
                    state = state, contentPadding = contentPadding,
                    modifier = Modifier.fillMaxSize().dragToSelect(remember(state) { state.hitTester() }, keys, selection),
                ) {
                    for ((label, list) in groups) {
                        if (label.isNotEmpty()) item(key = "§$label", contentType = "h") { SectionHeader(label) }
                        items(list.size, key = { list[it].path }, contentType = { "r" }) { i ->
                            val f = list[i]
                            ListRow(f, selection.isSelected(f.path), mode, { onClick(f) }, highlightModifier(f.path))
                        }
                    }
                }
            }
        }
    }
}

/** The "▶ Play N" button as drawn (for previews). */
@Composable
fun PlaySelectedButtonPreview(n: Int) {
    androidx.compose.material3.TextButton(onClick = {}) {
        Icon(androidx.compose.material.icons.Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
        Spacer(Modifier.width(2.dp))
        Text("Play $n")
    }
}

/** "Play slideshow" in a list's top bar when the list has photos or videos: plays them all, in the list's order. */
@Composable
fun PlayAllButton(items: List<FileItem>?, opener: com.clpstudio.filesmanager.ui.Opener) {
    val playable = remember(items) { items.orEmpty().filter { !it.isDir && (it.kind == FileKind.IMAGE || it.kind == FileKind.VIDEO) } }
    if (playable.isEmpty()) return
    androidx.compose.material3.IconButton(onClick = { opener.playSelected(playable) }) {
        Icon(androidx.compose.material.icons.Icons.Filled.Slideshow, "Play slideshow")
    }
}

/** "▶ Play N" in the selection bar when the selection has photos, videos or audio. */
@Composable
fun PlaySelectedButton(selected: List<FileItem>, opener: com.clpstudio.filesmanager.ui.Opener) {
    val playable = remember(selected) { selected.filter { it.kind == FileKind.IMAGE || it.kind == FileKind.VIDEO || it.kind == FileKind.AUDIO } }
    if (playable.isEmpty()) return
    androidx.compose.material3.TextButton(onClick = { opener.playSelected(playable) }) {
        Icon(androidx.compose.material.icons.Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
        Spacer(Modifier.width(2.dp))
        Text("Play ${playable.size}")
    }
}
