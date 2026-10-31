package com.clpstudio.filesmanager.viewer

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.Favorites
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.ui.components.FileThumb
import com.clpstudio.filesmanager.ui.glass.glassBar

private val ThumbSize = 48.dp
private val CurrentSize = 58.dp
private val ThumbGap = 6.dp

/**
 * A strip of small square thumbnails for every page of a viewer. The current one is larger with an accent
 * border and is kept centred; tapping one calls [onSelect] with its index.
 */
@Composable
fun Filmstrip(items: List<FileItem>, current: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().height(CurrentSize + 8.dp)) {
        val side = ((maxWidth - CurrentSize) / 2).coerceAtLeast(0.dp)
        val state = rememberLazyListState(initialFirstVisibleItemIndex = current.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        // contentPadding puts the scrolled-to item at the centre: it starts right after the side padding.
        LaunchedEffect(current, items.size) {
            if (current in items.indices) {
                if (state.layoutInfo.totalItemsCount == 0) state.scrollToItem(current) else state.animateScrollToItem(current)
            }
        }
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = side),
            horizontalArrangement = Arrangement.spacedBy(ThumbGap),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(CurrentSize + 8.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.path }) { index, item ->
                FilmstripThumb(item, selected = index == current) { onSelect(index) }
            }
        }
    }
}

@Composable
private fun FilmstripThumb(item: FileItem, selected: Boolean, onClick: () -> Unit) {
    val size by animateDpAsState(if (selected) CurrentSize else ThumbSize, label = "thumb")
    val shape = RoundedCornerShape(if (selected) 12.dp else 10.dp)
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .then(if (selected) Modifier.border(2.5.dp, accent, shape) else Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), shape))
            .clickable(role = Role.Image, onClickLabel = item.name, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        FileThumb(item, size = if (selected) size - 5.dp else size, corner = if (selected) 10.dp else 10.dp)
    }
}

/** Filled accent star when [path] is a favourite, outline otherwise. */
@Composable
fun FavoriteButton(path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Favorites.init(context) // idempotent: one volatile read after the first call
    val favorites by Favorites.paths.collectAsState()
    val on = path in favorites
    IconButton(onClick = { Favorites.toggle(path) }, modifier = modifier) {
        if (on) {
            Icon(Icons.Filled.Star, "Remove from favourites", tint = FavoriteOn) // same amber as the bottom bar and the video viewer
        } else {
            Icon(Icons.Outlined.StarBorder, "Add to favourites")
        }
    }
}

/**
 * The bottom of every in-app viewer: the [Filmstrip] (photos / videos) and under it the shared
 * [ViewerActions] row: Share, Favorite, Info, Delete. [items] are all pages; [showStrip] false keeps only the actions.
 */
@Composable
fun ViewerBottomBar(
    items: List<FileItem>,
    current: Int,
    onSelect: (Int) -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    showStrip: Boolean = items.size > 1,
    onInfo: (() -> Unit)? = null,
) {
    val item = items.getOrNull(current)
    Column(
        modifier.fillMaxWidth().glassBar().navigationBarsPadding().padding(top = 6.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showStrip) Filmstrip(items, current, onSelect)
        FileViewerActions(path = item?.path, onShare = onShare, onInfo = onInfo, onDelete = onDelete)
    }
}
