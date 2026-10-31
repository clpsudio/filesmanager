package com.clpstudio.filesmanager.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.Favorites
import com.clpstudio.filesmanager.ui.glass.glassBar

/** Accent for a starred file (amber reads on photos, video and paper alike). */
val FavoriteOn = Color(0xFFFFC107)

/**
 * The one bottom action row every viewer shares: Share, Favorite, Info, Delete (icon + label).
 * A null callback hides that button (e.g. no Delete / Favorite for a file shared from another app).
 */
@Composable
fun ViewerActions(
    onShare: (() -> Unit)?,
    favorite: Boolean?,
    onFavorite: (() -> Unit)?,
    onInfo: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
    contentColor: Color = LocalContentColor.current,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onShare != null) ViewerActionButton(Icons.Outlined.Share, "Share", contentColor, onShare)
        if (onFavorite != null && favorite != null) {
            ViewerActionButton(
                if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                "Favorite", if (favorite) FavoriteOn else contentColor, onFavorite,
                description = if (favorite) "Remove from favorites" else "Add to favorites",
                labelColor = contentColor,
            )
        }
        if (onInfo != null) ViewerActionButton(Icons.Outlined.Info, "Info", contentColor, onInfo)
        if (onDelete != null) ViewerActionButton(Icons.Outlined.Delete, "Delete", contentColor, onDelete)
    }
}

/** [ViewerActions] for a file on storage: the star follows [Favorites] for [path] (null = no star). */
@Composable
fun FileViewerActions(
    path: String?,
    onShare: (() -> Unit)?,
    onInfo: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
    contentColor: Color = LocalContentColor.current,
) {
    val context = LocalContext.current
    Favorites.init(context)
    val favs by Favorites.paths.collectAsState()
    ViewerActions(
        onShare = onShare,
        favorite = path?.let { it in favs },
        onFavorite = path?.let { p -> { Favorites.toggle(p) } },
        onInfo = onInfo,
        onDelete = onDelete,
        modifier = modifier,
        contentColor = contentColor,
    )
}

/** A glass bar along the bottom with [FileViewerActions] (PDF / text / single photo viewers). */
@Composable
fun ViewerActionPanel(
    path: String?,
    onShare: (() -> Unit)?,
    onInfo: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().glassBar().navigationBarsPadding().padding(vertical = 4.dp)) {
        FileViewerActions(path, onShare, onInfo, onDelete)
    }
}

@Composable
private fun ViewerActionButton(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit, description: String = label, labelColor: Color = tint) {
    Column(
        Modifier
            .widthIn(min = 64.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = labelColor)
    }
}

/**
 * Bottom bar for the standalone viewers (PDF, text) that get a [uri]: Share always, Favorite and Delete when it
 * is a file on storage (Delete goes to the Bin, then [onDeleted] closes the viewer), Info opens the info sheet.
 */
@Composable
fun UriViewerActionPanel(uri: android.net.Uri, mime: String, onInfo: () -> Unit, onDeleted: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val file = remember(uri) { uri.takeIf { it.scheme == "file" }?.path?.let { java.io.File(it) }?.takeIf { it.isFile } }
    val actions = com.clpstudio.filesmanager.ui.components.rememberFileActions(afterCopyOrMove = {})
    ViewerActionPanel(
        path = file?.absolutePath,
        onShare = {
            if (file != null) actions.share(listOf(com.clpstudio.filesmanager.data.FileItem.of(file)))
            else com.clpstudio.filesmanager.data.SafeStart.start(
                context,
                android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).setType(mime).putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Share",
                ),
                "This file can't be shared from here",
            )
        },
        onInfo = onInfo,
        onDelete = if (file != null) ({ actions.deleteTargets = listOf(com.clpstudio.filesmanager.data.FileItem.of(file)) }) else null,
        modifier = modifier,
    )
    com.clpstudio.filesmanager.ui.components.FileActionDialogs(actions, onDeleted = {
        if (file != null && !file.exists()) { Favorites.onDeleted(listOf(file.absolutePath)); onDeleted() }
    })
}
