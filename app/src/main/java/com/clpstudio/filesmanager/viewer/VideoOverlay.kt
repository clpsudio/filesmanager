package com.clpstudio.filesmanager.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.ui.components.FileThumb

/** The small "2x ▶▶" pill shown while a long press plays the video at double speed. */
@Composable
fun SpeedPill(modifier: Modifier = Modifier, label: String = "2x") {
    Row(
        modifier.background(Color(0xCC000000), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label ▶▶", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

class VideoOverlayState {
    var fast by mutableStateOf(false)
    var controls by mutableStateOf(true)
    var info by mutableStateOf<InfoTarget?>(null)
    var orientationLocked by mutableStateOf(false)
    var title by mutableStateOf("")
    var index by mutableStateOf(0)
    var items by mutableStateOf<List<FileItem>>(emptyList())
    var favorite by mutableStateOf(false)
    var muted by mutableStateOf(false)
    var speed by mutableStateOf(1f)
    var loop by mutableStateOf(false)
}

/** Everything the overlay can ask the player to do. */
interface VideoActions {
    fun back()
    fun info()
    fun rotate()
    fun autoRotate()
    fun toggleFavorite()
    fun edit()
    fun hide()
    fun share()
    fun delete()
    fun openWith()
    fun setSpeed(speed: Float)
    fun setLoop(on: Boolean)
    fun toggleMute()
    fun jumpTo(index: Int)
}

val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)
fun speedLabel(s: Float): String = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().trimEnd('0')) + "x"

/**
 * Drawn above the video: top bar (back, name, ⭐, ⋮ with Edit / Hide / Info / Playback speed / Loop),
 * the 2x pill, and above the player's seek bar a mute button, the filmstrip and Delete / Share / ⭐.
 * [bottomInset] keeps the strip above the player's own controls.
 */
@Composable
fun VideoOverlay(state: VideoOverlayState, actions: VideoActions, sheetActions: List<SheetAction>, bottomInset: androidx.compose.ui.unit.Dp = 112.dp) {
    var menu by remember { mutableStateOf(false) }
    var speedPicker by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(state.fast, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(), modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp)) {
            SpeedPill()
        }
        AnimatedVisibility(state.controls && !state.fast, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent)))
                    .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = actions::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Text(state.title, color = Color.White, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = actions::toggleFavorite) {
                    Icon(if (state.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, "Favorite", tint = if (state.favorite) Color(0xFFFFC107) else Color.White)
                }
                IconButton(onClick = actions::rotate) { Icon(Icons.Filled.ScreenRotation, "Rotate", tint = Color.White) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More", tint = Color.White) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; actions.edit() })
                        DropdownMenuItem(text = { Text("Hide") }, leadingIcon = { Icon(Icons.Filled.VisibilityOff, null) }, onClick = { menu = false; actions.hide() })
                        DropdownMenuItem(text = { Text("Info") }, leadingIcon = { Icon(Icons.Filled.Info, null) }, onClick = { menu = false; actions.info() })
                        DropdownMenuItem(
                            text = { Text("Playback speed") }, leadingIcon = { Icon(Icons.Filled.Speed, null) },
                            trailingIcon = { Text(speedLabel(state.speed), color = MaterialTheme.colorScheme.primary) },
                            onClick = { menu = false; speedPicker = true },
                        )
                        DropdownMenuItem(
                            text = { Column { Text("Loop"); Text(if (state.loop) "Disable looping" else "Enable looping", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                            leadingIcon = { Icon(Icons.Filled.Repeat, null, tint = if (state.loop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                            onClick = { menu = false; actions.setLoop(!state.loop) },
                        )
                        if (state.orientationLocked) {
                            DropdownMenuItem(text = { Text("Auto-rotate") }, leadingIcon = { Icon(Icons.Filled.ScreenLockRotation, null) }, onClick = { menu = false; actions.autoRotate() })
                        }
                        DropdownMenuItem(text = { Text("Open with") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }, onClick = { menu = false; actions.openWith() })
                    }
                }
            }
        }
        AnimatedVisibility(state.controls && !state.fast, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset)) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions::toggleMute) {
                        Icon(if (state.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (state.muted) "Unmute" else "Mute", tint = Color.White)
                    }
                    // The shared viewer bar: Share, Favorite, Info, Delete.
                    ViewerActions(
                        onShare = actions::share,
                        favorite = state.favorite,
                        onFavorite = actions::toggleFavorite,
                        onInfo = actions::info,
                        onDelete = actions::delete,
                        modifier = Modifier.weight(1f),
                        contentColor = Color.White,
                    )
                }
                if (state.items.size > 1) VideoFilmstrip(state.items, state.index, actions::jumpTo)
            }
        }
    }
    if (speedPicker) {
        AlertDialog(
            onDismissRequest = { speedPicker = false },
            title = { Text("Playback speed") },
            text = {
                Column {
                    SPEEDS.forEach { s ->
                        Row(Modifier.fillMaxWidth().clickable { speedPicker = false; actions.setSpeed(s) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = s == state.speed, onClick = { speedPicker = false; actions.setSpeed(s) })
                            Text(if (s == 1f) "1x (Normal)" else speedLabel(s))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { speedPicker = false }) { Text("Close") } },
        )
    }
    state.info?.let { t -> FileInfoSheet(t, sheetActions) { state.info = null } }
}

/** Thumbnails of the playlist; the current one is outlined, a tap jumps to it. */
@Composable
fun VideoFilmstrip(items: List<FileItem>, current: Int, onPick: (Int) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(current) { if (current in items.indices) list.animateScrollToItem((current - 3).coerceAtLeast(0)) }
    LazyRow(
        state = list,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(items, key = { i, it -> "$i:${it.path}" }) { i, item -> // the same video can be queued twice
            val on = i == current
            Box(
                Modifier.size(if (on) 56.dp else 48.dp)
                    .then(if (on) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
                    .clickable { onPick(i) },
            ) {
                FileThumb(item, if (on) 56.dp else 48.dp, corner = 10.dp)
            }
        }
    }
}

fun videoSheetActions(onShare: () -> Unit, onOpenWith: () -> Unit, onFolder: (() -> Unit)?): List<SheetAction> = listOfNotNull(
    SheetAction(Icons.Filled.Share, "Share", onShare),
    SheetAction(Icons.AutoMirrored.Filled.OpenInNew, "Open with", onOpenWith),
    onFolder?.let { SheetAction(Icons.Filled.Folder, "Show in folder", it) },
)
