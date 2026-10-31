package com.clpstudio.filesmanager.viewer

import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Scale
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.ui.glass.glassBar
import com.clpstudio.filesmanager.ui.glass.glassPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** One slide on screen. [step] counts every slide shown so far (drives the zoom in / zoom out alternation). */
@Immutable
private data class Slide(val step: Int, val item: FileItem)

/**
 * Full-screen slideshow / playlist for selected photos, videos and audio, in order.
 * Photos stay for the chosen time with a very slow Ken Burns zoom and a soft crossfade;
 * videos and audio play to their end, then the show moves on.
 *
 * [playVideo]: null (default) plays videos/audio inline with ExoPlayer. Pass a function to
 * hand them to another player instead; call `onEnded` when it finishes to continue the show.
 * [showOptionsFirst]: open the options sheet before the show starts.
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlideshowScreen(
    items: List<FileItem>,
    onBack: () -> Unit,
    playVideo: ((item: FileItem, onEnded: () -> Unit) -> Unit)? = null,
    showOptionsFirst: Boolean = true,
) {
    var options by remember { mutableStateOf(SlideshowSession.options) }
    val hasPhotos = remember(items) { com.clpstudio.filesmanager.ui.PlayRoute.hasPhotos(items) }
    var plan by remember(items) { mutableStateOf(SlideshowPlan(items, options)) }
    var slide by remember(items) { mutableStateOf(plan.current?.let { Slide(0, it) }) }
    var started by remember { mutableStateOf(!showOptionsFirst) }
    var showOptions by remember { mutableStateOf(showOptionsFirst) }
    var paused by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(false) }
    var touchTick by remember { mutableIntStateOf(0) }
    val playing = started && !paused && !showOptions && !finished

    ImmersiveEffect(true)
    KeepScreenOn()
    BackHandler { onBack() }

    fun showSlide() {
        val cur = plan.current ?: return
        slide = Slide((slide?.step ?: -1) + 1, cur)
    }
    fun goNext(auto: Boolean) {
        if (plan.next()) {
            finished = false
            showSlide()
        } else if (auto) {
            finished = true
            controlsVisible = true
        }
    }
    fun goPrevious() {
        if (plan.previous()) {
            finished = false
            showSlide()
        }
    }
    fun touch() { controlsVisible = true; touchTick++ }

    // Auto-hide controls ~3 s after the last touch while playing.
    LaunchedEffect(controlsVisible, touchTick, playing) {
        if (controlsVisible && playing) {
            delay(3000)
            controlsVisible = false
        }
    }

    // Decode at screen size (a little bigger to cover the 8 % zoom).
    val window = LocalWindowInfo.current.containerSize
    val decode = remember(window) {
        val m = max(window.width, window.height).coerceAtLeast(1)
        IntSize((m * 1.1f).toInt(), (m * 1.1f).toInt())
    }
    val context = LocalContext.current
    // Preload the next photo so the crossfade never waits on decoding.
    LaunchedEffect(slide, decode) {
        val next = plan.peekNext() ?: return@LaunchedEffect
        if (next.kind == FileKind.IMAGE) SingletonImageLoader.get(context).enqueue(slideRequest(context, next, decode))
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val current = slide
        if (current == null) {
            Text(
                "Nothing to play",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Crossfade(
                targetState = current,
                animationSpec = tween(SlideshowPlan.CROSSFADE_MS),
                label = "slide",
            ) { s ->
                val isCurrent = s.step == slide?.step
                when (s.item.kind) {
                    FileKind.IMAGE -> KenBurnsPhoto(
                        item = s.item,
                        step = s.step,
                        seconds = options.seconds,
                        decode = decode,
                        playing = playing,
                        isCurrent = isCurrent,
                        onDone = { goNext(auto = true) },
                    )
                    else -> {
                        if (playVideo != null) {
                            ExternalMedia(s.item, isCurrent && started && !showOptions, playVideo) { goNext(auto = true) }
                        } else {
                            InlineMedia(
                                item = s.item,
                                playing = playing && isCurrent,
                                onEnded = { if (isCurrent) goNext(auto = true) },
                            )
                        }
                    }
                }
            }
        }

        // Gestures: tap toggles controls, swipe left/right = next/previous.
        val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        if (controlsVisible) controlsVisible = false else touch()
                    })
                }
                .pointerInput(Unit) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (abs(total) > swipeThreshold) {
                                if (total < 0) goNext(auto = false) else goPrevious()
                            }
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            total += amount
                        },
                    )
                },
        )

        AnimatedVisibility(
            visible = controlsVisible || finished,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            SlideshowControls(
                position = plan.position + 1,
                total = plan.size,
                name = slide?.item?.name.orEmpty(),
                playing = playing,
                finished = finished,
                hasPrevious = plan.hasPrevious,
                hasNext = plan.hasNext,
                onClose = onBack,
                onPrevious = { touch(); goPrevious() },
                onPlayPause = {
                    touch()
                    if (finished) {
                        plan.restart()
                        finished = false
                        paused = false
                        showSlide()
                    } else {
                        paused = !paused
                    }
                },
                onNext = { touch(); goNext(auto = false) },
                onSettings = { touch(); showOptions = true },
            )
        }
    }

    if (showOptions) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val begin = { chosen: SlideshowOptions ->
            options = chosen
            SlideshowSession.options = chosen
            if (!started) {
                plan = SlideshowPlan(items, chosen)
                slide = plan.current?.let { Slide(0, it) }
            } else {
                // Keep showing the same item; the new order / repeat applies from here on.
                plan = SlideshowPlan(items, chosen, startAt = plan.current)
            }
            finished = false
            started = true
            showOptions = false
        }
        ModalBottomSheet(
            onDismissRequest = { begin(options) },
            sheetState = sheetState,
        ) {
            SlideshowOptionsContent(
                initial = options,
                hasPhotos = hasPhotos,
                startLabel = if (started) "Continue" else "Start",
                onStart = begin,
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }
}

private fun slideRequest(context: android.content.Context, item: FileItem, size: IntSize): ImageRequest =
    ImageRequest.Builder(context)
        .data(Uri.fromFile(File(item.path)))
        .size(size.width, size.height)
        .scale(Scale.FIT)
        .memoryCacheKey("slideshow:${item.path}:${item.modified}:${size.width}")
        .build()

/** One photo with a very slow zoom in or zoom out across its whole time on screen (plus the fade out). */
@Composable
private fun KenBurnsPhoto(
    item: FileItem,
    step: Int,
    seconds: Int,
    decode: IntSize,
    playing: Boolean,
    isCurrent: Boolean,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val kb = remember(step) { SlideshowPlan.kenBurns(step) }
    val showMs = seconds * 1000f
    // The motion keeps going through the outgoing crossfade, so it never freezes on screen.
    val totalMs = showMs + SlideshowPlan.CROSSFADE_MS
    val elapsed = remember(step) { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    val current by rememberUpdatedState(isCurrent)
    val running = playing || !isCurrent

    LaunchedEffect(step, running, totalMs) {
        if (running && elapsed.value < totalMs) {
            val left = (totalMs - elapsed.value).toInt().coerceAtLeast(1)
            elapsed.animateTo(totalMs, tween(left, easing = LinearEasing))
        } else {
            elapsed.stop()
        }
    }
    LaunchedEffect(step, showMs) {
        snapshotFlow { elapsed.value >= showMs }.first { it }
        if (current) done()
    }

    AsyncImage(
        model = remember(item.path, decode) { slideRequest(context, item, decode) },
        contentDescription = item.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            // Only this layer block re-runs each frame; no recomposition.
            val t = EaseInOutSine.transform((elapsed.value / totalMs).coerceIn(0f, 1f))
            val s = kb.startScale + (kb.endScale - kb.startScale) * t
            scaleX = s
            scaleY = s
            val d = -1f + 2f * t
            translationX = kb.driftXDp.dp.toPx() * d
            translationY = kb.driftYDp.dp.toPx() * d
        },
    )
}

/** Video or audio played inline to its end with ExoPlayer, then [onEnded]. */
@OptIn(UnstableApi::class)
@Composable
private fun InlineMedia(item: FileItem, playing: Boolean, onEnded: () -> Unit) {
    val context = LocalContext.current
    val ended by rememberUpdatedState(onEnded)
    val player = remember(item.path) {
        ExoPlayer.Builder(context)
            // Pauses for a call and comes back after it.
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(item.path))))
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) ended()
            }
            override fun onPlayerError(error: PlaybackException) {
                ended() // can't play this one: skip it
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, playing) { player.playWhenReady = playing }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (item.kind == FileKind.VIDEO) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        this.player = player
                    }
                },
                update = { it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AudioCard(item)
        }
    }
}

/** Hands a video/audio to the caller's player; continues when it reports the end. */
@Composable
private fun ExternalMedia(item: FileItem, active: Boolean, playVideo: (FileItem, () -> Unit) -> Unit, onEnded: () -> Unit) {
    val ended by rememberUpdatedState(onEnded)
    LaunchedEffect(item.path, active) {
        if (active) playVideo(item) { ended() }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AudioCard(item) }
}

@Composable
private fun AudioCard(item: FileItem) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(140.dp).glassPanel(RoundedCornerShape(32.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            item.name,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

/** Keeps the screen on while the slideshow is open. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view.keepScreenOn = true
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            view.keepScreenOn = false
        }
    }
}

/** Top bar (close, "3 / 12", name, timer) and bottom pill (previous, play/pause, next). */
@Composable
fun SlideshowControls(
    position: Int,
    total: Int,
    name: String,
    playing: Boolean,
    onClose: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    finished: Boolean = false,
    hasPrevious: Boolean = true,
    hasNext: Boolean = true,
) {
    Box(modifier.fillMaxSize()) {
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().glassBar()
                .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text(
                    if (total > 0) "$position / $total" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Slideshow options") }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp)
                .glassPanel(RoundedCornerShape(36.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(onClick = onPrevious, enabled = hasPrevious) {
                Icon(Icons.Filled.SkipPrevious, "Previous", modifier = Modifier.size(30.dp))
            }
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(60.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            ) {
                Icon(
                    when {
                        finished -> Icons.Filled.Replay
                        playing -> Icons.Filled.Pause
                        else -> Icons.Filled.PlayArrow
                    },
                    when {
                        finished -> "Play again"
                        playing -> "Pause"
                        else -> "Play"
                    },
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(34.dp),
                )
            }
            IconButton(onClick = onNext, enabled = hasNext) {
                Icon(Icons.Filled.SkipNext, "Next", modifier = Modifier.size(30.dp))
            }
        }
    }
}

/** Options sheet body: time per photo (chips + 1–30 s slider), repeat, order, Start. */
@kotlin.OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SlideshowOptionsContent(
    initial: SlideshowOptions,
    onStart: (SlideshowOptions) -> Unit,
    modifier: Modifier = Modifier,
    startLabel: String = "Start",
    /** False when the show has no photos: the "Time per photo" option is hidden. */
    hasPhotos: Boolean = true,
) {
    var seconds by remember { mutableFloatStateOf(initial.seconds.toFloat()) }
    var repeat by remember { mutableStateOf(initial.repeat) }
    var shuffle by remember { mutableStateOf(initial.shuffle) }
    val secs = seconds.toInt().coerceIn(SlideshowOptions.MIN_SECONDS, SlideshowOptions.MAX_SECONDS)

    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
        Text(if (hasPhotos) "Slideshow" else "Playlist", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (hasPhotos) {
        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Timer, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Time per photo", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                "$secs s",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SlideshowOptions.PRESETS.forEach { p ->
                FilterChip(
                    selected = secs == p,
                    onClick = { seconds = p.toFloat() },
                    label = { Text("$p s") },
                )
            }
        }
        Slider(
            value = secs.toFloat(),
            onValueChange = { seconds = kotlin.math.round(it) },
            valueRange = SlideshowOptions.MIN_SECONDS.toFloat()..SlideshowOptions.MAX_SECONDS.toFloat(),
        )
        Row {
            Text("1 s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("30 s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "Videos and audio play to the end.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "Videos and audio play to the end, one after another.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Repeat, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Repeat", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(checked = repeat, onCheckedChange = { repeat = it })
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Shuffle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Order", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !shuffle,
                onClick = { shuffle = false },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("As selected") }
            SegmentedButton(
                selected = shuffle,
                onClick = { shuffle = true },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Shuffle") }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onStart(SlideshowOptions(secs, repeat, shuffle)) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(startLabel)
        }
    }
}
