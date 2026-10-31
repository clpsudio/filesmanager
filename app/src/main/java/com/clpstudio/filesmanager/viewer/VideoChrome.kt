package com.clpstudio.filesmanager.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.ui.glass.glassBar

/*
 * The video controls, laid out like the photo viewer Charan likes: the same glass top bar and
 * bottom action bar + filmstrip, plus only what a video needs: a big play/pause in the middle,
 * one seek row (time · bar · total) and one control row (sound · speed · loop · fit/fill)
 * on a scrim right above the action bar. Shared by the video player and the inline video page
 * of the photo / video pager.
 */

private val Tnum = TextStyle(fontFeatureSettings = "tnum")

/** Middle of the screen: previous · play/pause · next on soft dark circles. */
@Composable
fun VideoCenterButtons(
    playing: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    showPrevNext: Boolean = true,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showPrevNext) CircleIconButton(Icons.Filled.SkipPrevious, "Previous", 48.dp, hasPrev, onPrevious)
        CircleIconButton(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", 68.dp, true, onPlayPause)
        if (showPrevNext) CircleIconButton(Icons.Filled.SkipNext, "Next", 48.dp, hasNext, onNext)
    }
}

@Composable
private fun CircleIconButton(icon: ImageVector, label: String, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(0x8C000000))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White.copy(alpha = if (enabled) 1f else 0.35f), modifier = Modifier.size(size * 0.56f))
    }
}

/** Current time · slim bar with a dot · total time. [onSeek] gets live positions while dragging, done = released. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoSeekRow(positionMs: Long, durationMs: Long, onSeek: (ms: Long, done: Boolean) -> Unit, modifier: Modifier = Modifier, onTouch: () -> Unit = {}) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val dur = durationMs.coerceAtLeast(0L)
    val value = dragging ?: if (dur > 0) (positionMs.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val shown = dragging?.let { (it * dur).toLong() } ?: positionMs
    val accent = MaterialTheme.colorScheme.primary
    val colors = SliderDefaults.colors(
        thumbColor = Color.White, activeTrackColor = accent, inactiveTrackColor = Color.White.copy(alpha = 0.32f),
        disabledThumbColor = Color.White.copy(alpha = 0.6f), disabledActiveTrackColor = Color.White.copy(alpha = 0.6f),
        disabledInactiveTrackColor = Color.White.copy(alpha = 0.25f),
    )
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(VideoGestureMath.formatTime(shown), color = Color.White, style = MaterialTheme.typography.labelLarge.merge(Tnum), modifier = Modifier.width(56.dp))
        Slider(
            value = value,
            onValueChange = { v -> dragging = v; onTouch(); onSeek((v * dur).toLong(), false) },
            onValueChangeFinished = { dragging?.let { onSeek((it * dur).toLong(), true) }; dragging = null },
            enabled = dur > 0,
            colors = colors,
            thumb = { Box(Modifier.size(if (dragging != null) 18.dp else 14.dp).background(Color.White, CircleShape)) },
            track = { s ->
                SliderDefaults.Track(sliderState = s, colors = colors, enabled = dur > 0, drawStopIndicator = null, thumbTrackGapSize = 0.dp, modifier = Modifier.height(4.dp))
            },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(32.dp),
        )
        Text(VideoGestureMath.formatTime(dur), color = Color.White, style = MaterialTheme.typography.labelLarge.merge(Tnum), modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** Sound · speed chip · loop · (space) · fit / fill. */
@Composable
fun VideoControlRow(
    muted: Boolean,
    speed: Float,
    loop: Boolean,
    fill: Boolean,
    onMute: () -> Unit,
    onSpeed: (Float) -> Unit,
    onLoop: () -> Unit,
    onFit: (() -> Unit)?,
    modifier: Modifier = Modifier,
    loopHint: String? = null,
) {
    var speedMenu by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(speedMenu) {
        VideoSpeed.menuOpen = speedMenu
        onDispose { VideoSpeed.menuOpen = false }
    }
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMute) {
            Icon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (muted) "Unmute" else "Mute", tint = Color.White)
        }
        Box {
            Box(
                Modifier.height(32.dp).clip(RoundedCornerShape(50)).border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .background(if (speed != 1f) Color.White.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable { speedMenu = true }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(speedLabel(speed), color = Color.White, style = MaterialTheme.typography.labelLarge.merge(Tnum), fontWeight = FontWeight.SemiBold)
            }
            DropdownMenu(speedMenu, { speedMenu = false }) {
                SPEEDS.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(if (s == 1f) "1x  Normal" else speedLabel(s), fontWeight = if (s == speed) FontWeight.Bold else FontWeight.Normal) },
                        trailingIcon = { if (s == speed) Icon(Icons.Filled.Check, null) },
                        onClick = { speedMenu = false; onSpeed(s) },
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (loop) MaterialTheme.colorScheme.primary else Color.Transparent).clickable(onClick = onLoop),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (loop) Icons.Filled.RepeatOne else Icons.Filled.Repeat, if (loop) "Loop on" else "Loop off", tint = if (loop) MaterialTheme.colorScheme.onPrimary else Color.White, modifier = Modifier.size(22.dp))
        }
        if (loopHint != null) {
            Text("  $loopHint", color = Color.White, style = MaterialTheme.typography.labelLarge.merge(Tnum))
        }
        Spacer(Modifier.weight(1f))
        if (onFit != null) {
            IconButton(onClick = onFit) {
                Icon(if (fill) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, if (fill) "Fit to screen" else "Fill screen", tint = Color.White)
            }
        }
    }
}

/** A pill with the file name, shown above the seek row when the video changes. */
@Composable
fun VideoNameToast(name: String, position: String?, visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(400)), modifier = modifier) {
        Row(
            Modifier.padding(horizontal = 16.dp).background(Color(0xB3000000), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, color = Color.White, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f, fill = false))
            if (position != null) Text("  $position", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium.merge(Tnum))
        }
    }
}

/**
 * The bottom block: [video] controls on a scrim (when given), then the photo viewer's glass panel
 * with the filmstrip (thumbnails only) and Share · Favorite · Info · Delete.
 */
@Composable
fun VideoBottomBlock(
    items: List<FileItem>,
    current: Int,
    onSelect: (Int) -> Unit,
    favorite: Boolean?,
    onShare: () -> Unit,
    onFavorite: (() -> Unit)?,
    onInfo: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
    video: (@Composable () -> Unit)? = null,
    nameToast: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        nameToast?.invoke()
        if (video != null) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.7f))))
                    .padding(top = 28.dp, bottom = 4.dp),
            ) { video() }
        }
        Column(
            Modifier.fillMaxWidth().glassBar().navigationBarsPadding().padding(top = 6.dp, bottom = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (items.size > 1) Filmstrip(items, current, onSelect)
            ViewerActions(onShare = onShare, favorite = favorite, onFavorite = onFavorite, onInfo = onInfo, onDelete = onDelete)
        }
    }
}
