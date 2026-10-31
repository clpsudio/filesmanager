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
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import com.clpstudio.filesmanager.data.Favorites
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

    ImmersiveEffect(fullscreen || (isImage && !chrome))
    OrientationEffect(orientation)
    LaunchedEffect(pager.currentPage) { if (!isImage) chrome = true }

    val dark = isMedia
    val dismissing = ViewerGestureMath.dismissProgress(dismiss.value, dismissPx)
    Box(Modifier.fillMaxSize().background(if (dark) Color.Black.copy(alpha = 1f - dismissing) else paperDeskColor())) {
        HorizontalPager(
            state = pager,
            key = { list.getOrNull(it)?.path ?: "#$it" },
            beyondViewportPageCount = 1,
            userScrollEnabled = zooms[pager.currentPage]?.zoomed != true,
            modifier = Modifier.fillMaxSize(),
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
                FileKind.PDF -> PdfContent(uri, item.name, paper, topPadding = top)
                FileKind.TEXT -> if (Markdown.isMarkdown(item.name)) MarkdownContent(uri, mdSource, paper, topPadding = top) else TextContent(uri, paper, topPadding = top)
                FileKind.DOCUMENT -> OfficePage(item, paper, top) { openFull(item, list.toList()) }
                else -> PreviewCard(item, swipe) { openFull(item, list.toList()) }
            }
        }

        AnimatedVisibility(chrome && !fullscreen, enter = fadeIn(), exit = fadeOut()) {
            ViewerTopBar(
                title = current.name,
                subtitle = "${page + 1} of ${list.size}",
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
                    IconButton(onClick = { info = true }) { Icon(Icons.Filled.Info, "File info") }
                }
                IconButton(onClick = { fullscreen = true; chrome = false }) { Icon(Icons.Filled.Fullscreen, "Full screen") }
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
                        ) + listOfNotNull(
                            ViewerMenuEntry(Icons.Filled.DriveFileRenameOutline, "Rename") { renaming = current },
                            ViewerMenuEntry(Icons.Filled.Share, "Share") { actions.share(listOf(current)) },
                            ViewerMenuEntry(Icons.AutoMirrored.Filled.OpenInNew, "Open with another app") { actions.openWith(current) },
                            ViewerMenuEntry(Icons.Filled.Folder, "Show in folder") { showInFolder(current.parentPath) },
                            if (orientation != null) ViewerMenuEntry(Icons.Filled.ScreenLockRotation, "Auto-rotate (follow the phone)") { orientation = null } else null,
                            if (current.kind == FileKind.PDF) ViewerMenuEntry(Icons.Filled.Lock, "Add password") { actions.pdfLockTarget = current } else null,
                            if (current.kind == FileKind.PDF) ViewerMenuEntry(Icons.Filled.LockOpen, "Remove password") { actions.pdfUnlockTarget = current } else null,
                            ViewerMenuEntry(Icons.Filled.Delete, "Delete") { actions.deleteTargets = listOf(current) },
                        )
                        entries.forEach { e -> MenuRow(e.icon, e.label) { menu = false; e.onClick() } }
                    }
                }
            }
        }
        if (fullscreen) {
            IconButton(
                onClick = { fullscreen = false; chrome = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).glassBar(RoundedCornerShape(24.dp)),
            ) { Icon(Icons.Filled.FullscreenExit, "Exit full screen") }
        }
        // Every page: Share / Favorite / Info / Delete; photos and videos also get the filmstrip. Hidden with the top bar.
        AnimatedVisibility(chrome && !fullscreen && dismissing == 0f, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
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
        TextInputDialog(
            title = "Rename",
            initial = item.name,
            confirm = "Rename",
            onConfirm = { name ->
                try {
                    val renamed = FileOps.rename(context, File(item.path), name)
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
fun viewerMenuEntries(kind: FileKind, onEdit: () -> Unit, onSetAs: () -> Unit, onHide: () -> Unit, onInfo: () -> Unit): List<ViewerMenuEntry> =
    listOfNotNull(
        if (kind == FileKind.IMAGE) ViewerMenuEntry(Icons.Filled.Edit, "Edit", onEdit) else null,
        if (kind == FileKind.IMAGE) ViewerMenuEntry(Icons.Filled.Wallpaper, "Set as", onSetAs) else null,
        ViewerMenuEntry(Icons.Filled.VisibilityOff, "Hide", onHide),
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
                FileKind.APK -> Icons.Filled.InstallMobile to "Install"
                FileKind.ARCHIVE, FileKind.FONT -> Icons.Filled.Folder to "Open"
                else -> Icons.AutoMirrored.Filled.OpenInNew to "Open"
            }
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Icon(icon, null); Text("  $label") }
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
