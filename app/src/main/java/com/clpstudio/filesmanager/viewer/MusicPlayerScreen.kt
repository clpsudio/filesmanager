package com.clpstudio.filesmanager.viewer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import com.clpstudio.filesmanager.playback.PlayerUi
import com.clpstudio.filesmanager.ui.components.formatDuration
import com.clpstudio.filesmanager.ui.glass.GlassBackground
import com.clpstudio.filesmanager.ui.glass.LocalGlass
import com.clpstudio.filesmanager.ui.glass.glassPanel

@Composable
fun MusicPlayerScreen(ui: PlayerUi, onBack: () -> Unit) {
    val context = LocalContext.current
    // Android 13+: ask once for notifications so the player shows its notification and lock-screen controls.
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val p = ui.controller
    MusicPlayerContent(
        ui = ui,
        onBack = onBack,
        onPlayPause = { p?.let { if (it.isPlaying) it.pause() else it.play() } },
        onNext = { p?.seekToNext() },
        onPrevious = { p?.seekToPrevious() },
        onSeek = { p?.seekTo(it) },
        onShuffle = { p?.shuffleModeEnabled = !(p?.shuffleModeEnabled ?: false) },
        onRepeat = {
            p?.repeatMode = when (p?.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        },
        onPick = { p?.seekTo(it, 0) },
    )
}

@Composable
fun MusicPlayerContent(
    ui: PlayerUi,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onPick: (Int) -> Unit,
    artworkOverride: ImageBitmap? = null,
) {
    val art = artworkOverride ?: remember(ui.artwork) {
        ui.artwork?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    var showQueue by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val glass = LocalGlass.current

    Box(Modifier.fillMaxSize()) {
        GlassBackground(Modifier.fillMaxSize())
        if (art != null && glass.enabled) {
            // Blurred album art behind the glass (real blur on Android 12+).
            Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(60.dp), alpha = if (glass.dark) 0.45f else 0.35f)
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Now playing", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                IconButton(onClick = { showQueue = !showQueue }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue") }
            }
            if (showQueue) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().glassPanel(RoundedCornerShape(24.dp)).padding(8.dp)) {
                    itemsIndexed(ui.queue) { i, md ->
                        Text(
                            (md.title ?: md.displayTitle ?: "Track ${i + 1}").toString(),
                            fontWeight = if (i == ui.index) FontWeight.Bold else FontWeight.Normal,
                            color = if (i == ui.index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(i) }.padding(12.dp),
                        )
                    }
                }
            } else {
                Spacer(Modifier.weight(0.4f))
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).padding(12.dp).glassPanel(RoundedCornerShape(32.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (art != null) {
                        Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(32.dp)))
                    } else {
                        Box(
                            Modifier.size(140.dp).clip(CircleShape).background(
                                Brush.linearGradient(listOf(Color(0xFF1E6FF0), Color(0xFF9333EA))),
                            ),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.MusicNote, null, tint = Color.White, modifier = Modifier.size(72.dp)) }
                    }
                }
                Spacer(Modifier.weight(0.3f))
            }
            Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(28.dp)).padding(18.dp)) {
                Text(ui.title.ifEmpty { "Nothing playing" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ui.artist.ifEmpty { "Unknown artist" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.height(8.dp))
                val pos = if (dragging) dragValue else ui.position.toFloat()
                Slider(
                    value = pos.coerceIn(0f, ui.duration.coerceAtLeast(1).toFloat()),
                    onValueChange = { dragging = true; dragValue = it },
                    onValueChangeFinished = { onSeek(dragValue.toLong()); dragging = false },
                    valueRange = 0f..ui.duration.coerceAtLeast(1).toFloat(),
                )
                Row {
                    Text(formatDuration(pos.toLong()), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Text(formatDuration(ui.duration), style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onShuffle) {
                        Icon(Icons.Filled.Shuffle, "Shuffle", tint = if (ui.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(36.dp)) }
                    FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors()) {
                        Icon(if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (ui.isPlaying) "Pause" else "Play", Modifier.size(40.dp))
                    }
                    IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.SkipNext, "Next", Modifier.size(36.dp)) }
                    IconButton(onClick = onRepeat) {
                        Icon(
                            if (ui.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Repeat",
                            tint = if (ui.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
