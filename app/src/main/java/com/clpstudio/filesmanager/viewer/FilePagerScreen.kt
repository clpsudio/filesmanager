package com.clpstudio.filesmanager.viewer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.FileOps
import com.clpstudio.filesmanager.data.FileTypes
import com.clpstudio.filesmanager.ui.AppState
import com.clpstudio.filesmanager.ui.components.FileActionDialogs
import com.clpstudio.filesmanager.ui.components.FileThumb
import com.clpstudio.filesmanager.ui.components.MenuRow
import com.clpstudio.filesmanager.ui.components.formatDate
import com.clpstudio.filesmanager.ui.components.formatSize
import com.clpstudio.filesmanager.ui.components.rememberFileActions
import com.clpstudio.filesmanager.ui.glass.glassBar
import com.clpstudio.filesmanager.ui.glass.glassPanel
import android.widget.Toast
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import com.clpstudio.filesmanager.data.Favorites
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.ui.components.TextInputDialog
import kotlinx.coroutines.launch
import java.io.File

/** Which kinds show their content inside the pager; the rest show a preview card with an action. */
private fun FileKind.pagesInline() = this == FileKind.IMAGE || this == FileKind.PDF || this == FileKind.TEXT ||
    this == FileKind.DOCUMENT

/**
 * One viewer for every file, with:
 *  - swipe LEFT/RIGHT: previous / next file of the same list or folder, in its sort order,
 *  - swipe UP: the file-info sheet (name, size, type, date, folder, resolution / duration, actions),
 *  - full screen (hides the system bars) and rotate (landscape / portrait / follow the sensor).
 * Videos, music, APKs and ZIPs show a card with Play / Install / Open.
 */
@Composable
fun FilePagerScreen(
    paths: List<String>,
    start: String,
    onBack: () -> Unit,
    openFull: (FileItem, List<FileItem>) -> Unit,
    showInFolder: (String) -> Unit,
) {
    val context = LocalContext.current
    Favorites.init(context) // idempotent: one volatile read after the first call
    val scope = rememberCoroutineScope()
    // Mutable so Hide / Delete / Rename update the pages in place (the pager moves on to the next file).
    val list = remember(paths) {
        mutableStateListOf<FileItem>().apply {
            addAll(paths.distinct().map { File(it) }.filter { it.isFile }.map { FileItem.of(it) }.ifEmpty { listOf(FileItem.of(File(start))) })
        }
    }
    val startIndex = remember(paths) { list.indexOfFirst { it.path == start }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = startIndex) { list.size }
    val zooms = remember { mutableStateMapOf<Int, ZoomState>() }
    var chrome by remember { mutableStateOf(true) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var orientation by rememberSaveable { mutableStateOf<Int?>(null) }
    var fsRotated by rememberSaveable { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var mdSource by rememberSaveable { mutableStateOf(false) } // Markdown: Preview (default) or Source
    var renaming by remember { mutableStateOf<FileItem?>(null) }
    val actions = rememberFileActions(afterCopyOrMove = {})
    // Swipe zones (ViewerGestures.kt): brightness pill, drag-down-to-close; brightness goes back to the system on exit.
    val pill = remember { LevelPillState() }
    val dismiss = remember { DismissDragState() }
    val dismissPx = with(androidx.compose.ui.platform.LocalDensity.current) { ViewerGestureMath.DISMISS_DP.dp.toPx() }
    ResetBrightnessOnExit()
    // Videos play inline, right in the pager (Media, Recent, Favorites, folders): no "Play" card.
    val video = remember { PagerVideo(context) }
    DisposableEffect(video) { onDispose { video.release() } }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    // Leaving the app with a video playing: Background play (Settings, on by default) keeps the sound going in the
    // media service with its notification; coming back picks it up here at the same spot.
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    val act = context.findActivity()
                    val cur = video.path
                    val videos = list.filter { pagerPageType(it.kind) == PagerPage.VIDEO }
                    val idx = videos.indexOfFirst { it.path == cur }
                    val pipShown = VideoPip.inPip || (android.os.Build.VERSION.SDK_INT >= 24 && act?.isInPictureInPictureMode == true)
                    when {
                        act?.isChangingConfigurations == true -> Unit
                        // Closing the screen or the picture-in-picture window stops the video and its notification (item 33).
                        act?.isFinishing == true || pipShown -> video.closeAll(context)
                        // Leaving the app without picture-in-picture (Auto picture-in-picture off, or not allowed):
                        // Background play keeps the sound going.
                        video.playing && idx >= 0 && Graph.settings.value.backgroundPlay -> video.handOff(context, videos, idx)
                        else -> video.pause()
                    }
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> video.takeBack(context) { p, _, _ ->
                    val i = list.indexOfFirst { it.path == p }
                    if (i >= 0 && i != pager.currentPage) scope.launch { pager.scrollToPage(i) }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // Deleted / moved / renamed anywhere (info sheet, another screen, the video player): the page goes too.
    val fileEvents by com.clpstudio.filesmanager.data.FileEvents.version.collectAsState()
    LaunchedEffect(fileEvents) {
        val gone = list.filter { com.clpstudio.filesmanager.data.FileEvents.isGone(it.path, it.modified) }
        if (gone.isNotEmpty()) {
            list.removeAll(gone)
            zooms.clear()
            if (list.isEmpty()) onBack()
        }
    }
    if (list.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val page = pager.currentPage.coerceIn(0, list.size - 1)
    val current = list[page]
    val isImage = current.kind == FileKind.IMAGE
    val isMedia = isImage || current.kind == FileKind.VIDEO
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Takes [gone] out of the pages (hidden, deleted); leaves the viewer when nothing is left. */
    fun dropPages(gone: Collection<String>) {
        if (gone.isEmpty()) return
        list.removeAll { it.path in gone }
        zooms.clear()
        AppState.refresh() // the list behind the viewer updates too
        if (list.isEmpty()) onBack()
    }

    val isVideo = pagerPageType(current.kind) == PagerPage.VIDEO
    val pwEntries = com.clpstudio.filesmanager.data.rememberPasswordEntries(current)
    LaunchedEffect(current.path, isVideo) {
        if (isVideo) video.load(current, Graph.settings.value) else video.stop()
    }
    // When a video is done (Off, or after "Play N times"): the next video in this list, skipping photos and other files.
    val playlist = remember { com.clpstudio.filesmanager.ui.ViewerSession.playlist }
    video.playlist = playlist
    val nextVideo = remember(list.toList(), page) { com.clpstudio.filesmanager.ui.VideoOpen.nextVideo(list.map { pagerPageType(it.kind) == PagerPage.VIDEO }, page) }
    video.onNext = nextVideo?.let { n ->
        when {
            n == page -> ({ video.restart() }) // the only video: from the start again
            n < page -> ({ scope.launch { pager.scrollToPage(n) } }) // end of the list: back to the first video
            else -> ({ scope.launch { pager.animateScrollToPage(n) } })
        }
    }
    LaunchedEffect(current.path, isVideo) {
        while (isVideo) { video.tick(); kotlinx.coroutines.delay(250) }
    }
    // Leaving the app while a video plays: picture-in-picture (item 34); only the video shows in the small window.
    VideoPipEffect(
        video, isVideo, hasPrevNext = list.size > 1,
        onNext = { com.clpstudio.filesmanager.ui.VideoOpen.nextVideo(list.map { pagerPageType(it.kind) == PagerPage.VIDEO }, page)?.let { n -> scope.launch { pager.scrollToPage(n) } } },
        onPrevious = {
            val isV = list.map { pagerPageType(it.kind) == PagerPage.VIDEO }
            (page - 1 downTo 0).firstOrNull { isV[it] }?.let { n -> scope.launch { pager.scrollToPage(n) } }
        },
    )
    LaunchedEffect(VideoPip.inPip) { chrome = !VideoPip.inPip }
    // Video: the controls hide by themselves while it plays (tap shows them again).
    LaunchedEffect(chrome, video.playing, current.path, VideoSpeed.menuOpen) {
        if (isVideo && chrome && video.playing && !fullscreen && !VideoSpeed.menuOpen) { kotlinx.coroutines.delay(3500); chrome = false }
    }

    ImmersiveEffect(fullscreen || ((isImage || isVideo) && !chrome))
    OrientationEffect(orientation)
    LaunchedEffect(pager.currentPage) { if (!isImage && !isVideo) chrome = true }

    // Side-zone swipes on a video page move the pager by hand (its own scrolling is off on video pages).
    var pagerWidth by remember { mutableStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val pageGestures = remember(pager) {
        object : VideoPageGestureCallbacks {
            var applied = 0f
            var startPage = -1
            override fun onPageDrag(dx: Float) {
                if (startPage < 0) startPage = pager.currentPage
                val p = startPage
                val off = VideoGestureMath.pageOffset(dx, hasPrev = p > 0, hasNext = p < pager.pageCount - 1)
                pager.dispatchRawDelta(-(off - applied))
                applied = off
            }
            override fun onPageEnd(dx: Float, velocityPxS: Float) {
                applied = 0f
                val p = if (startPage >= 0) startPage else pager.currentPage
                startPage = -1
                val d = VideoGestureMath.pageDecision(dx, pagerWidth, velocityPxS, density, hasPrev = p > 0, hasNext = p < pager.pageCount - 1)
                scope.launch { pager.animateScrollToPage((p + d).coerceIn(0, (pager.pageCount - 1).coerceAtLeast(0))) }
            }
            override fun onInfo() { info = true }
            override fun onDismiss() { onBack() }
        }
    }

    val dark = isMedia
    val dismissing = ViewerGestureMath.dismissProgress(dismiss.value, dismissPx)
    Box(Modifier.fillMaxSize().background(if (dark) Color.Black.copy(alpha = 1f - dismissing) else paperDeskColor())) {
        HorizontalPager(
            state = pager,
            key = { list.getOrNull(it)?.path ?: "#$it" },
            beyondViewportPageCount = 1,
            // A video page handles its own drags (brightness / volume / timeline / sides = other file): see videoPageGestures.
            userScrollEnabled = zooms[pager.currentPage]?.zoomed != true && !isVideo,
            modifier = Modifier.fillMaxSize().onSizeChanged { pagerWidth = it.width.toFloat() },
        ) { index ->
            // The list shrinks on Hide / Delete; a stale page index must not crash the pager.
            val item = list.getOrNull(index) ?: return@HorizontalPager
            val uri = remember(item.path) { Uri.fromFile(File(item.path)) }
            val zoom = zooms.getOrPut(index) { ZoomState() }
            val swipe = Modifier.swipeUpToOpen(enabled = !zoom.zoomed) { info = true }
            val top = if (fullscreen) 24.dp else 100.dp
            // Paper pages end above the bottom action bar so their page counter stays visible.
            val paper = if (chrome && !fullscreen) swipe.navigationBarsPadding().padding(bottom = 64.dp) else swipe
            when (item.kind) {
                FileKind.IMAGE -> Box(
                    Modifier.fillMaxSize()
                        .photoSwipeGestures(
                            zoomed = { zoom.zoomed }, pill = pill, dismiss = dismiss,
                            onInfo = { info = true }, onDismiss = onBack,
                        )
                        .graphicsLayer {
                            if (index == pager.currentPage) {
                                translationY = dismiss.value
                                val s = 1f - 0.15f * ViewerGestureMath.dismissProgress(dismiss.value, dismissPx)
                                scaleX = s
                                scaleY = s
                            }
                        },
                ) { ZoomableImage(uri, zoom) { chrome = !chrome } }
                FileKind.VIDEO -> InlineVideoPage(
                    item, isCurrent = index == pager.currentPage, video = video, onTap = { chrome = !chrome },
                    gestures = pageGestures, pill = pill, dismiss = dismiss,
                    modifier = Modifier.graphicsLayer {
                        if (index == pager.currentPage) {
                            translationY = dismiss.value
                            val s = 1f - 0.15f * ViewerGestureMath.dismissProgress(dismiss.value, dismissPx)
                            scaleX = s
                            scaleY = s
                        }
                    },
                )
                FileKind.PDF -> PdfContent(uri, item.name, paper, topPadding = top)
                FileKind.TEXT -> if (Markdown.isMarkdown(item.name)) MarkdownContent(uri, mdSource, paper, topPadding = top) else TextContent(uri, paper, topPadding = top)
                FileKind.DOCUMENT -> OfficePage(item, paper, top) { openFull(item, list.toList()) }
                else -> if (com.clpstudio.filesmanager.data.DesignPreview.isDesign(item.name)) {
                    DesignPage(File(item.path), item.name, zoom, swipe, topPadding = top) { chrome = !chrome }
                } else PreviewCard(item, swipe) { openFull(item, list.toList()) }
            }
        }

        AnimatedVisibility(chrome && !fullscreen, enter = fadeIn(), exit = fadeOut()) {
            ViewerTopBar(
                title = current.name,
                // Media: "3 of 144"; documents say "files" so it doesn't read like the PDF's page number.
                subtitle = if (isMedia) "${page + 1} of ${list.size}" else "${page + 1} of ${list.size} file" + (if (list.size == 1) "" else "s"),
                onBack = onBack,
                titleColor = if (isMedia) Color.Unspecified else MaterialTheme.colorScheme.primary,
            ) {
                FavoriteButton(current.path)
                if (Markdown.isMarkdown(current.name)) {
                    androidx.compose.material3.TextButton(onClick = { mdSource = !mdSource }) { Text(if (mdSource) "Preview" else "Source") }
                }
                if (isImage) {
                    IconButton(onClick = {
                        orientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }) { Icon(Icons.Filled.ScreenRotation, "Rotate") }
                } else {
                    if (isVideo) {
                        // Hand over to the full player (picture-in-picture, background play), same list.
                        IconButton(onClick = {
                            // Picture-in-picture needs its own window: the player opens straight into the small window
                            // at the same spot and speed.
                            val at = video.position
                            video.pause()
                            val media = list.filter { it.kind == FileKind.VIDEO || it.kind == FileKind.IMAGE }
                            VideoPlayerActivity.startFiles(context, media, media.indexOfFirst { it.path == current.path }.coerceAtLeast(0), positionMs = at, pip = true)
                        }) { Icon(Icons.Filled.PictureInPictureAlt, "Open in video player (picture-in-picture)") }
                    }
                    IconButton(onClick = { info = true }) { Icon(Icons.Filled.Info, "File info") }
                }
                IconButton(onClick = {
                    fullscreen = true; chrome = false
                    // A video goes full screen in this same view: bars hidden, turned to landscape when the video is wide.
                    if (isVideo && video.isWide() && orientation == null) { orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE; fsRotated = true }
                }) { Icon(Icons.Filled.Fullscreen, "Full screen") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        val file = File(current.path)
                        val entries = viewerMenuEntries(
                            kind = current.kind,
                            onEdit = { PhotoActions.edit(context, file) },
                            onSetAs = { PhotoActions.setAs(context, file) },
                            onHide = {
                                if (PhotoActions.hide(context, file) != null) {
                                    Toast.makeText(context, "Hidden. Turn on Show hidden files to see it.", Toast.LENGTH_LONG).show()
                                    dropPages(listOf(current.path))
                                } else {
                                    Toast.makeText(context, "Couldn't hide it", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onInfo = { info = true },
                            hidden = com.clpstudio.filesmanager.data.HiddenNames.isHidden(current.path),
                            onUnhide = {
                                if (com.clpstudio.filesmanager.data.HiddenOps.unhide(context, file) != null) {
                                    Toast.makeText(context, "Unhidden", Toast.LENGTH_SHORT).show()
                                    dropPages(listOf(current.path))
                                } else {
                                    Toast.makeText(context, "Couldn't unhide it", Toast.LENGTH_SHORT).show()
                                }
                            },
                        ) + listOfNotNull(
                            // Only a real file on storage can be renamed (not one shared in by another app).
                            if (File(current.path).exists()) ViewerMenuEntry(Icons.Filled.DriveFileRenameOutline, "Rename") { renaming = current } else null,
                            ViewerMenuEntry(Icons.Filled.Share, "Share") { actions.share(listOf(current)) },
                            ViewerMenuEntry(Icons.AutoMirrored.Filled.OpenInNew, "Open with another app") { actions.openWith(current) },
                            ViewerMenuEntry(Icons.Filled.Folder, "Show in folder") { showInFolder(current.parentPath) },
                            if (orientation != null) ViewerMenuEntry(Icons.Filled.ScreenLockRotation, "Auto-rotate (follow the phone)") { orientation = null } else null,
                            // Only the one that applies: Add for a PDF without a password, Remove for a protected one.
                            if (com.clpstudio.filesmanager.data.PasswordEntry.ADD in pwEntries) ViewerMenuEntry(Icons.Filled.Lock, "Add password") { actions.pdfLockTarget = current } else null,
                            if (com.clpstudio.filesmanager.data.PasswordEntry.REMOVE in pwEntries) ViewerMenuEntry(Icons.Filled.LockOpen, "Remove password") { actions.pdfUnlockTarget = current } else null,
                            ViewerMenuEntry(Icons.Filled.Delete, "Delete") { actions.deleteTargets = listOf(current) },
                        )
                        entries.forEach { e -> MenuRow(e.icon, e.label) { menu = false; e.onClick() } }
                    }
                }
            }
        }
        if (fullscreen) {
            IconButton(
                onClick = { fullscreen = false; chrome = true; if (fsRotated) { orientation = null; fsRotated = false } },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).glassBar(RoundedCornerShape(24.dp)),
            ) { Icon(Icons.Filled.FullscreenExit, "Exit full screen") }
        }
        // Every page: Share / Favorite / Info / Delete; photos and videos also get the filmstrip. Hidden with the top bar.
        // A video adds one seek row and one control row on a scrim above it, and play / pause in the middle.
        AnimatedVisibility(chrome && !fullscreen && dismissing == 0f && isVideo, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            VideoCenterButtons(
                playing = video.playing,
                hasPrev = page > 0,
                hasNext = page < list.size - 1,
                onPrevious = { scope.launch { pager.animateScrollToPage(page - 1) } },
                onPlayPause = { video.playPause() },
                onNext = { scope.launch { pager.animateScrollToPage(page + 1) } },
                showPrevNext = list.size > 1,
            )
        }
        AnimatedVisibility(chrome && !fullscreen && dismissing == 0f, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            if (isVideo) {
                val favs by Favorites.paths.collectAsState()
                VideoBottomBlock(
                    items = list,
                    current = page,
                    onSelect = { scope.launch { pager.animateScrollToPage(it) } },
                    favorite = current.path in favs,
                    onShare = { actions.share(listOf(current)) },
                    onFavorite = { Favorites.toggle(current.path) },
                    onInfo = { info = true },
                    onDelete = { actions.deleteTargets = listOf(current) },
                    video = {
                        VideoSeekRow(video.position, video.duration, onSeek = { ms, _ -> video.seek(ms) })
                        VideoControlRow(
                            muted = video.muted, speed = video.speed, loop = video.loop, fill = video.fill, loopHint = video.loopHint,
                            onMute = video::toggleMute, onSpeed = video::changeSpeed,
                            onLoop = { video.changeLoop(!video.loop) }, onFit = { video.fill = !video.fill },
                        )
                    },
                )
            } else {
                ViewerBottomBar(
                    items = list,
                    current = page,
                    onSelect = { scope.launch { pager.animateScrollToPage(it) } },
                    onShare = { actions.share(listOf(current)) },
                    onDelete = { actions.deleteTargets = listOf(current) },
                    showStrip = isMedia && list.size > 1,
                    onInfo = { info = true },
                )
            }
        }
        LevelPillHost(pill)
    }
    if (info) {
        val target = remember(current.path) { InfoTarget.of(File(current.path)) }
        FileInfoSheet(
            target,
            listOf(
                SheetAction(Icons.Filled.Share, "Share") { actions.share(listOf(current)) },
                SheetAction(Icons.AutoMirrored.Filled.OpenInNew, "Open with") { actions.openWith(current) },
                SheetAction(Icons.Filled.Folder, "Show in folder") { info = false; showInFolder(current.parentPath) },
                SheetAction(Icons.Filled.Delete, "Delete") { info = false; actions.deleteTargets = listOf(current) },
            ),
        ) { info = false }
    }
    renaming?.let { item ->
        com.clpstudio.filesmanager.ui.components.RenameFileDialog(
            name = item.name,
            isDir = item.isDir,
            onRename = { name ->
                try {
                    val renamed = com.clpstudio.filesmanager.data.RenameOps.rename(context, File(item.path), name)
                    Favorites.onMoved(item.path, renamed.absolutePath)
                    val i = list.indexOfFirst { it.path == item.path }
                    if (i >= 0) list[i] = FileItem.of(renamed)
                    AppState.refresh()
                    renaming = null
                    null
                } catch (e: Exception) {
                    e.message ?: "Couldn't rename it"
                }
            },
            onDismiss = { renaming = null },
        )
    }
    FileActionDialogs(actions, onDeleted = {
        val gone = list.map { it.path }.filter { !File(it).exists() }
        Favorites.onDeleted(gone)
        dropPages(gone)
    })
}

/** One row of the viewer's ⋮ menu. */
@Immutable
data class ViewerMenuEntry(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** The photo-specific rows of the viewer menu: Edit and Set as (photos only), then Hide and Info. */
fun viewerMenuEntries(
    kind: FileKind, onEdit: () -> Unit, onSetAs: () -> Unit, onHide: () -> Unit, onInfo: () -> Unit,
    hidden: Boolean = false, onUnhide: (() -> Unit)? = null,
): List<ViewerMenuEntry> =
    listOfNotNull(
        if (kind == FileKind.IMAGE) ViewerMenuEntry(Icons.Filled.Edit, "Edit", onEdit) else null,
        if (kind == FileKind.IMAGE) ViewerMenuEntry(Icons.Filled.Wallpaper, "Set as", onSetAs) else null,
        // A hidden file gets Unhide (never Hide); a normal one gets Hide.
        if (hidden && onUnhide != null) ViewerMenuEntry(Icons.Filled.Visibility, "Unhide", onUnhide)
        else if (!hidden) ViewerMenuEntry(Icons.Filled.VisibilityOff, "Hide", onHide) else null,
        ViewerMenuEntry(Icons.Filled.Info, "Info", onInfo),
    )

/** Video / music / APK / ZIP / other inside the pager: thumbnail, details and the main action. */
@Composable
fun PreviewCard(item: FileItem, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val context = LocalContext.current
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(28.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FileThumb(item, size = 160.dp, corner = 20.dp, modifier = Modifier.size(160.dp))
            Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.MiddleEllipsis)
            Text("${formatSize(item.size)} · ${formatDate(item.modified)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            val (icon, label) = when (item.kind) {
                FileKind.VIDEO, FileKind.AUDIO -> Icons.Filled.PlayArrow to "Play"
                FileKind.APK -> Icons.Filled.Android to "View app details"
                FileKind.ARCHIVE, FileKind.FONT -> Icons.Filled.Folder to "Open"
                else -> Icons.AutoMirrored.Filled.OpenInNew to "Open"
            }
            if (item.kind == FileKind.DOCUMENT && item.extension != "epub") {
                // .doc / .xls / .ppt / OpenDocument …: no in-app viewer, so the main action is another app.
                Text(
                    com.clpstudio.filesmanager.viewer.office.OfficeFormats.noPreviewMessage(item.name),
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                )
                Button(onClick = {
                    val f = File(item.path)
                    FileOps.openWithOtherApp(context, FileOps.uriFor(context, f), FileTypes.mimeOf(f.name))
                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.AutoMirrored.Filled.OpenInNew, null); Text("  Open with another app") }
            } else Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Icon(icon, null); Text("  $label") }
            if (item.kind == FileKind.APK && File(item.path).extension.equals("apk", true)) {
                OutlinedButton(onClick = { com.clpstudio.filesmanager.ui.Opener.installApk(context, File(item.path)) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.InstallMobile, null); Text("  Install")
                }
            }
            if (item.kind == FileKind.OTHER) {
                OutlinedButton(onClick = {
                    val f = File(item.path)
                    FileOps.openWithOtherApp(context, FileOps.uriFor(context, f), FileTypes.mimeOf(f.name))
                }, modifier = Modifier.fillMaxWidth()) { Text("Open with another app") }
            }
            Text("Swipe up for details · swipe sideways for the next file", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
