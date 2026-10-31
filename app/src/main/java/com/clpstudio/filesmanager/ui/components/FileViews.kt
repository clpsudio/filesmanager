package com.clpstudio.filesmanager.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.thumbs.thumbModel
import com.clpstudio.filesmanager.ui.glass.pressScale

fun kindIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.FOLDER -> Icons.Filled.Folder
    FileKind.IMAGE -> Icons.Filled.Image
    FileKind.VIDEO -> Icons.Filled.VideoFile
    FileKind.AUDIO -> Icons.Filled.AudioFile
    FileKind.PDF -> Icons.Filled.PictureAsPdf
    FileKind.TEXT -> Icons.AutoMirrored.Filled.TextSnippet
    FileKind.DOCUMENT -> Icons.Filled.Description
    FileKind.APK -> Icons.Filled.Android
    FileKind.ARCHIVE -> Icons.Filled.FolderZip
    FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

fun kindColor(kind: FileKind): Color = when (kind) {
    FileKind.FOLDER -> Color(0xFF1E6FF0)
    FileKind.IMAGE -> Color(0xFF16A34A)
    FileKind.VIDEO -> Color(0xFFDC2626)
    FileKind.AUDIO -> Color(0xFF9333EA)
    FileKind.PDF -> Color(0xFFE11D48)
    FileKind.TEXT -> Color(0xFF64748B)
    FileKind.DOCUMENT -> Color(0xFF2563EB)
    FileKind.APK -> Color(0xFF65A30D)
    FileKind.ARCHIVE -> Color(0xFFD97706)
    FileKind.OTHER -> Color(0xFF6B7280)
}

/** Real preview when there is one (photo, video frame, album art, PDF page, APK icon), else the type icon. */
@Composable
fun FileThumb(item: FileItem, size: Dp, modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    val shape = RoundedCornerShape(corner)
    val model = remember(item.path, item.modified) { thumbModel(item) }
    val color = kindColor(item.kind)
    Box(
        modifier.size(size).clip(shape).background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        val fallback: @Composable () -> Unit = {
            Icon(kindIcon(item.kind), contentDescription = null, tint = color, modifier = Modifier.size(size * 0.5f))
        }
        fallback()
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = if (item.kind == FileKind.APK) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (item.kind == FileKind.APK) Modifier.padding(size * 0.12f) else Modifier),
            )
            if (item.kind == FileKind.VIDEO) {
                Icon(
                    Icons.Filled.PlayCircle, null, tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size((size * 0.34f).coerceAtMost(28.dp)).align(Alignment.Center),
                )
            }
        }
    }
}

private fun subtitle(item: FileItem): String = if (item.isDir) {
    val n = item.childCount
    (if (n >= 0) "$n item" + (if (n == 1) "" else "s") + " · " else "") + formatDate(item.modified)
} else {
    formatSize(item.size) + " · " + formatDate(item.modified)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    item: FileItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    extraLine: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .pressScale(interaction, 0.98f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FileThumb(item, 48.dp)
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp).align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.surface, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(
                subtitle(item), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
            if (extraLine != null) {
                Text(
                    extraLine, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileTile(
    item: FileItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    showName: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .pressScale(interaction)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            ThumbFill(item)
            if (selected) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)),
                )
                Icon(
                    Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(6.dp).size(22.dp).align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                )
            }
        }
        if (showName) {
            Text(
                item.name, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis, fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ThumbFill(item: FileItem) {
    val model = remember(item.path, item.modified) { thumbModel(item) }
    val color = kindColor(item.kind)
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier.fillMaxSize().clip(shape).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        val fallback: @Composable () -> Unit = {
            Icon(kindIcon(item.kind), null, tint = color, modifier = Modifier.size(40.dp))
        }
        fallback()
        if (model != null) AsyncImage(
            model = model, contentDescription = null,
            contentScale = if (item.kind == FileKind.APK) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.fillMaxSize().then(if (item.kind == FileKind.APK) Modifier.padding(18.dp) else Modifier),
        )
        if (item.kind == FileKind.VIDEO) {
            Icon(Icons.Filled.PlayCircle, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(30.dp))
        }
    }
}
