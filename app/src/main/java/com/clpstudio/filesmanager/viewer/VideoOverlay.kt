package com.clpstudio.filesmanager.viewer

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.ui.components.FileThumb
import kotlinx.coroutines.delay
import java.io.File

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
    /** Entry index in [items] (photos and videos when opened from Media). */
    var index by mutableStateOf(0)
    var items by mutableStateOf<List<FileItem>>(emptyList())
    var favorite by mutableStateOf(false)
    var muted by mutableStateOf(false)
    var speed by mutableStateOf(1f)
    var loop by mutableStateOf(false)

    // Playback, refreshed by the activity a few times a second.
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)

    // Timeline scrub (side swipe or the seek bar).
    var scrubbing by mutableStateOf(false)
    var scrubStartMs by mutableLongStateOf(0L)
    var scrubMs by mutableLongStateOf(0L)

    /** Bumped when the video changes: the name fades in at the bottom by the timeline for ~2 s. */
    var nameStamp by mutableIntStateOf(0)
    /** Bumped by any touch on the controls: restarts the auto-hide timer. */
    var touchStamp by mutableIntStateOf(0)

    // Next / previous slide: the current page moves by [pageOffset] px; [incoming] slides in beside it.
    var pageOffset by mutableFloatStateOf(0f)
    var incoming by mutableStateOf<FileItem?>(null)
    /** Shown in place (no offset) until the next video's first frame is on screen. */
    var cover by mutableStateOf<FileItem?>(null)
    /** A photo in the list (Media): shown instead of the player. */
    var photo by mutableStateOf<FileItem?>(null)

    /** Double-tap seek feedback: -1 = back, +1 = forward; [seekStamp] restarts the ripple. */
    var seekSide by mutableIntStateOf(0)
    var seekStamp by mutableIntStateOf(0)
    var seekAmount by mutableIntStateOf(10)

    /** Small note at the top ("Last video", "Loop on"). Never in the middle of the video. */
    var message by mutableStateOf<String?>(null)
    var messageStamp by mutableIntStateOf(0)

    var pip by mutableStateOf(false)
    var pipSupported by mutableStateOf(true)
    var hasPrev by mutableStateOf(false)
    var hasNext by mutableStateOf(false)

    /** Where the controls are (root px), so the swipe layer leaves their touches alone. */
    val controlRects = mutableMapOf<String, Rect>()

    fun isOverControls(x: Float, y: Float): Boolean = controls && !pip && controlRects.values.any { it.contains(androidx.compose.ui.geometry.Offset(x, y)) }

    fun flashName() { nameStamp++ }
    fun note(text: String) { message = text; messageStamp++ }
    fun current(): FileItem? = items.getOrNull(index)
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
    fun playPause() {}
    fun next() {}
    fun previous() {}
    /** Seek bar drag: live position while dragging; [done] on release. */
    fun seekBar(ms: Long, done: Boolean) {}
    fun pip() {}
}

val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)
fun speedLabel(s: Float): String = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().trimEnd('0')) + "x"

private val Shade = Color.Black
private val TabularNums = TextStyle(fontFeatureSettings = "tnum")
private const val FADE_MS = 200

/** Full-screen still of a list entry: the photo itself, or a video's first frame (Coil's video decoder). */
@Composable
fun EntryStill(item: FileItem, modifier: Modifier = Modifier) {
    val model: Any = if (item.path.startsWith("content://")) Uri.parse(item.path) else File(item.path)
    Box(modifier.background(Color.Black)) {
        AsyncImage(model = model, contentDescription = item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

/**
 * Drawn above the video. Nothing here darkens the whole picture: only a soft gradient sits behind the top bar
 * and behind the bottom controls, and both fade in / out in [FADE_MS] ms.
 *  - Top: back · picture-in-picture · rotate · ⋮ (Edit, Hide, Info, Playback speed, Loop, Open with).
 *  - Middle: previous · play / pause · next (small, see-through).
 *  - Bottom: file name, the timeline with times, mute · speed chip · loop · Share ⭐ Info Delete, filmstrip.
 * When the video changes the name fades in at the bottom next to a slim timeline for ~2 s.
 * Side swipes show a time bubble ("1:23 / 4:10", "+0:12"); middle swipes slide the next video in.
 */
@Composable
fun VideoOverlay(state: VideoOverlayState, actions: VideoActions, sheetActions: List<SheetAction>) {
    var menu by remember { mutableStateOf(false) }
    var speedPicker by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var nameVisible by remember { mutableStateOf(false) }

    LaunchedEffect(state.nameStamp) {
        if (state.nameStamp > 0) {
            nameVisible = true
            delay(2200)
            nameVisible = false
        }
    }
    // Auto-hide while playing; any touch on the controls restarts the timer.
    LaunchedEffect(state.controls, state.touchStamp, state.playing, menu, speedMenu, state.scrubbing) {
        if (state.controls && state.playing && !menu && !speedMenu && !state.scrubbing && state.photo == null) {
            delay(3500)
            state.controls = false
        }
    }
    if (state.pip) return // picture-in-picture: the video alone

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        // A photo from the list (Media) in place of the player, moving with the page drag.
        state.photo?.let { p ->
            EntryStill(p, Modifier.fillMaxSize().graphicsLayer { translationX = state.pageOffset })
        }
        // Next / previous sliding in beside the current page.
        state.incoming?.let { inc ->
            val side = if (state.pageOffset < 0) 1f else -1f
            EntryStill(inc, Modifier.fillMaxSize().graphicsLayer { translationX = state.pageOffset + side * widthPx })
        }
        // The new video's still until its first frame is drawn (no black flash after the slide).
        val coverAlpha by animateFloatAsState(if (state.cover != null) 1f else 0f, tween(if (state.cover != null) 0 else 160), label = "cover")
        var lastCover by remember { mutableStateOf<FileItem?>(null) }
        if (state.cover != null) lastCover = state.cover
        if (coverAlpha > 0f) lastCover?.let { EntryStill(it, Modifier.fillMaxSize().alpha(coverAlpha)) }

        SeekRipple(state)

        AnimatedVisibility(state.fast, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(), modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp)) {
            SpeedPill()
        }
        // Top note ("Last video" ...).
        var noteVisible by remember { mutableStateOf(false) }
        LaunchedEffect(state.messageStamp) {
            if (state.messageStamp > 0) { noteVisible = true; delay(1200); noteVisible = false }
        }
        AnimatedVisibility(noteVisible && state.message != null, enter = fadeIn(tween(FADE_MS)), exit = fadeOut(tween(FADE_MS)), modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp)) {
            Text(state.message.orEmpty(), color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp))
        }

        // Time bubble while scrubbing: small, no dimming.
        AnimatedVisibility(state.scrubbing, enter = fadeIn(tween(120)), exit = fadeOut(tween(FADE_MS)), modifier = Modifier.align(Alignment.Center)) {
            ScrubBubble(state.scrubMs, state.duration, state.scrubMs - state.scrubStartMs)
        }

        val showControls = state.controls && !state.fast && !state.scrubbing
        // ---- Top bar on its own soft gradient.
        AnimatedVisibility(showControls, enter = fadeIn(tween(FADE_MS)), exit = fadeOut(tween(FADE_MS)), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Shade.copy(alpha = 0.55f), Shade.copy(alpha = 0.25f), Color.Transparent)))
                    .statusBarsPadding().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 28.dp)
                    .onGloballyPositioned { state.controlRects["top"] = it.boundsInRoot() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = actions::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                if (state.pipSupported && state.photo == null) {
                    IconButton(onClick = actions::pip) { Icon(Icons.Filled.PictureInPictureAlt, "Picture-in-picture", tint = Color.White) }
                }
                IconButton(onClick = actions::rotate) { Icon(Icons.Filled.ScreenRotation, "Rotate", tint = Color.White) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More", tint = Color.White) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; actions.edit() })
                        DropdownMenuItem(text = { Text("Hide") }, leadingIcon = { Icon(Icons.Filled.VisibilityOff, null) }, onClick = { menu = false; actions.hide() })
                        DropdownMenuItem(text = { Text("Info") }, leadingIcon = { Icon(Icons.Filled.Info, null) }, onClick = { menu = false; actions.info() })
                        if (state.photo == null) {
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
                        }
                        if (state.orientationLocked) {
                            DropdownMenuItem(text = { Text("Auto-rotate") }, leadingIcon = { Icon(Icons.Filled.ScreenLockRotation, null) }, onClick = { menu = false; actions.autoRotate() })
                        }
                        DropdownMenuItem(text = { Text("Open with") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }, onClick = { menu = false; actions.openWith() })
                    }
                }
            }
        }
        // ---- Center: previous / play-pause / next. See-through circles, nothing behind them.
        AnimatedVisibility(showControls && state.photo == null, enter = fadeIn(tween(FADE_MS)), exit = fadeOut(tween(FADE_MS)), modifier = Modifier.align(Alignment.Center)) {
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.Filled.SkipPrevious, "Previous", 46.dp, enabled = state.hasPrev) { state.touchStamp++; actions.previous() }
                RoundButton(if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (state.playing) "Pause" else "Play", 62.dp) { state.touchStamp++; actions.playPause() }
                RoundButton(Icons.Filled.SkipNext, "Next", 46.dp, enabled = state.hasNext) { state.touchStamp++; actions.next() }
            }
        }
        // ---- Name + slim timeline after a video change (controls hidden).
        AnimatedVisibility(
            !showControls && (nameVisible || state.scrubbing) && state.info == null,
            enter = fadeIn(tween(250)), exit = fadeOut(tween(400)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Shade.copy(alpha = 0.45f))))
                    .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 14.dp),
            ) {
                if (nameVisible) NameLine(state)
                val pos = if (state.scrubbing) state.scrubMs else state.position
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SlimTimeline(pos, state.duration, Modifier.weight(1f))
                    Text("  " + VideoGestureMath.formatTime(pos) + " / " + VideoGestureMath.formatTime(state.duration), color = Color.White, style = MaterialTheme.typography.labelMedium.merge(TabularNums))
                }
            }
        }
        // ---- Bottom controls on their own soft gradient.
        AnimatedVisibility(showControls, enter = fadeIn(tween(FADE_MS)), exit = fadeOut(tween(FADE_MS)), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Shade.copy(alpha = 0.35f), Shade.copy(alpha = 0.6f))))
                    .navigationBarsPadding().padding(top = 36.dp, bottom = 4.dp)
                    .onGloballyPositioned { state.controlRects["bottom"] = it.boundsInRoot() },
            ) {
                Box(Modifier.padding(horizontal = 16.dp)) { NameLine(state) }
                if (state.photo == null) {
                    SeekRow(state, actions)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { state.touchStamp++; actions.toggleMute() }) {
                            Icon(if (state.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (state.muted) "Unmute" else "Mute", tint = Color.White)
                        }
                        Box {
                            SpeedChip(state.speed) { state.touchStamp++; speedMenu = true }
                            DropdownMenu(speedMenu, { speedMenu = false }) {
                                SPEEDS.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(if (s == 1f) "1x  Normal" else speedLabel(s), fontWeight = if (s == state.speed) FontWeight.Bold else FontWeight.Normal) },
                                        trailingIcon = { if (s == state.speed) Icon(Icons.Filled.Check, null) },
                                        onClick = { speedMenu = false; actions.setSpeed(s) },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        LoopToggle(state.loop) { state.touchStamp++; actions.setLoop(!state.loop) }
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
                } else {
                    ViewerActions(
                        onShare = actions::share, favorite = state.favorite, onFavorite = actions::toggleFavorite,
                        onInfo = actions::info, onDelete = actions::delete, contentColor = Color.White,
                    )
                }
                if (state.items.size > 1) VideoFilmstrip(state.items, state.index) { state.touchStamp++; actions.jumpTo(it) }
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

@Composable
private fun NameLine(state: VideoOverlayState) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
        Text(
            state.title, color = Color.White, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false),
        )
        if (state.items.size > 1) {
            Text("  ${state.index + 1} / ${state.items.size}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium.merge(TabularNums))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekRow(state: VideoOverlayState, actions: VideoActions) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val dur = state.duration.coerceAtLeast(0L)
    val pos = if (state.scrubbing) state.scrubMs else state.position
    val value = dragging ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val shown = if (dragging != null) (dragging!! * dur).toLong() else pos
    val colors = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = Color.White,
        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
        disabledThumbColor = Color.White.copy(alpha = 0.6f),
        disabledActiveTrackColor = Color.White.copy(alpha = 0.6f),
        disabledInactiveTrackColor = Color.White.copy(alpha = 0.25f),
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(VideoGestureMath.formatTime(shown), color = Color.White, style = MaterialTheme.typography.labelMedium.merge(TabularNums))
        Slider(
            value = value,
            onValueChange = { v -> dragging = v; state.touchStamp++; actions.seekBar((v * dur).toLong(), done = false) },
            onValueChangeFinished = { dragging?.let { actions.seekBar((it * dur).toLong(), done = true) }; dragging = null },
            enabled = dur > 0,
            colors = colors,
            // Slim line with a round dot, like the gallery players (no gap, no end marker).
            thumb = { Box(Modifier.size(if (dragging != null) 18.dp else 14.dp).background(Color.White, CircleShape)) },
            track = { s ->
                SliderDefaults.Track(
                    sliderState = s, colors = colors, enabled = dur > 0,
                    drawStopIndicator = null, thumbTrackGapSize = 0.dp,
                    modifier = Modifier.height(4.dp),
                )
            },
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp).height(28.dp),
        )
        Text(VideoGestureMath.formatTime(dur), color = Color.White, style = MaterialTheme.typography.labelMedium.merge(TabularNums))
    }
}

@Composable
fun SlimTimeline(positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    val f = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Color.White))
    }
}

/** "1:23 / 4:10" with the change ("+0:12") under it. Small and see-through: the video stays visible. */
@Composable
fun ScrubBubble(targetMs: Long, durationMs: Long, deltaMs: Long, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Color(0x8C000000), RoundedCornerShape(18.dp)).padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            VideoGestureMath.formatTime(targetMs) + " / " + VideoGestureMath.formatTime(durationMs),
            color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, style = TabularNums,
        )
        Text(VideoGestureMath.formatDelta(deltaMs), color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, style = TabularNums)
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, size: androidx.compose.ui.unit.Dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(0x47000000)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White.copy(alpha = if (enabled) 1f else 0.35f), modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
private fun SpeedChip(speed: Float, onClick: () -> Unit) {
    Box(
        Modifier.height(30.dp).clip(RoundedCornerShape(50)).border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(50))
            .background(if (speed != 1f) Color.White.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(speedLabel(speed), color = Color.White, style = MaterialTheme.typography.labelLarge.merge(TabularNums), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LoopToggle(on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.9f) else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (on) Icons.Filled.RepeatOne else Icons.Filled.Repeat, if (on) "Loop on" else "Loop off", tint = if (on) MaterialTheme.colorScheme.onPrimary else Color.White, modifier = Modifier.size(22.dp))
    }
}

/** Double-tap: a soft glow on that side with "10 s". */
@Composable
private fun BoxScope.SeekRipple(state: VideoOverlayState) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(state.seekStamp) {
        if (state.seekStamp > 0) { visible = true; delay(550); visible = false }
    }
    val forward = state.seekSide > 0
    AnimatedVisibility(
        visible, enter = fadeIn(tween(120)), exit = fadeOut(tween(300)),
        modifier = Modifier.align(if (forward) Alignment.CenterEnd else Alignment.CenterStart).fillMaxHeight().fillMaxWidth(0.34f),
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    if (forward) listOf(Color.Transparent, Color.White.copy(alpha = 0.14f)) else listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
                ),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (forward) Icons.Filled.FastForward else Icons.Filled.FastRewind, null, tint = Color.White, modifier = Modifier.size(34.dp))
                Text("${state.seekAmount} s", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Thumbnails of the list (videos, and photos when opened from Media); the current one is outlined, a tap jumps to it. */
@Composable
fun VideoFilmstrip(items: List<FileItem>, current: Int, onPick: (Int) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(current) { if (current in items.indices) list.animateScrollToItem((current - 3).coerceAtLeast(0)) }
    LazyRow(
        state = list,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(items, key = { i, it -> "$i:${it.path}" }) { i, item -> // the same video can be queued twice
            val on = i == current
            Box(
                Modifier.size(if (on) 52.dp else 42.dp)
                    .then(if (on) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable { onPick(i) },
            ) {
                FileThumb(item, if (on) 52.dp else 42.dp, corner = 8.dp)
                if (item.kind == FileKind.VIDEO && !on) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(16.dp))
                }
            }
        }
    }
}

fun videoSheetActions(onShare: () -> Unit, onOpenWith: () -> Unit, onFolder: (() -> Unit)?): List<SheetAction> = listOfNotNull(
    SheetAction(Icons.Filled.Share, "Share", onShare),
    SheetAction(Icons.AutoMirrored.Filled.OpenInNew, "Open with", onOpenWith),
    onFolder?.let { SheetAction(Icons.Filled.Folder, "Show in folder", it) },
)
