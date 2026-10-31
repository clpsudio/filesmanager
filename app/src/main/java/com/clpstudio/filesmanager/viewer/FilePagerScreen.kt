package com.clpstudio.filesmanager.viewer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
    val list = remember(paths) { paths.map { File(it) }.filter { it.isFile }.map { FileItem.of(it) }.ifEmpty { listOf(FileItem.of(File(start))) } }
    val startIndex = list.indexOfFirst { it.path == start }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = startIndex) { list.size }
    val zooms = remember { mutableStateMapOf<Int, ZoomState>() }
    var chrome by remember { mutableStateOf(true) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var orientation by rememberSaveable { mutableStateOf<Int?>(null) }
    var info by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val actions = rememberFileActions(afterCopyOrMove = {})
    val current = list.getOrNull(pager.currentPage) ?: list.first()
    val isImage = current.kind == FileKind.IMAGE
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    ImmersiveEffect(fullscreen || (isImage && !chrome))
    OrientationEffect(orientation)
    LaunchedEffect(pager.currentPage) { if (!isImage) chrome = true }

    val dark = isImage || current.kind == FileKind.VIDEO
    Box(Modifier.fillMaxSize().background(if (dark) Color.Black else paperDeskColor())) {
        HorizontalPager(
            state = pager,
            key = { list[it].path },
            beyondViewportPageCount = 1,
            userScrollEnabled = zooms[pager.currentPage]?.zoomed != true,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = list[page]
            val uri = remember(item.path) { Uri.fromFile(File(item.path)) }
            val zoom = zooms.getOrPut(page) { ZoomState() }
            val swipe = Modifier.swipeUpToOpen(enabled = !zoom.zoomed) { info = true }
            val top = if (fullscreen) 24.dp else 100.dp
            when (item.kind) {
                FileKind.IMAGE -> Box(swipe.fillMaxSize()) { ZoomableImage(uri, zoom) { chrome = !chrome } }
                FileKind.PDF -> PdfContent(uri, item.name, swipe, topPadding = top)
                FileKind.TEXT -> TextContent(uri, swipe, topPadding = top)
                FileKind.DOCUMENT -> OfficePage(item, swipe, top) { openFull(item, list) }
                else -> PreviewCard(item, swipe) { openFull(item, list) }
            }
        }

        AnimatedVisibility(chrome && !fullscreen, enter = fadeIn(), exit = fadeOut()) {
            ViewerTopBar(
                title = current.name,
                subtitle = "${pager.currentPage + 1} of ${list.size}",
                onBack = onBack,
                titleColor = if (isImage) Color.Unspecified else MaterialTheme.colorScheme.primary,
            ) {
                IconButton(onClick = { info = true }) { Icon(Icons.Filled.Info, "File info") }
                if (isImage) {
                    IconButton(onClick = {
                        orientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }) { Icon(Icons.Filled.ScreenRotation, "Rotate") }
                }
                IconButton(onClick = { fullscreen = true; chrome = false }) { Icon(Icons.Filled.Fullscreen, "Full screen") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        MenuRow(Icons.Filled.Share, "Share") { menu = false; actions.share(listOf(current)) }
                        MenuRow(Icons.AutoMirrored.Filled.OpenInNew, "Open with another app") { menu = false; actions.openWith(current) }
                        MenuRow(Icons.Filled.Folder, "Show in folder") { menu = false; showInFolder(current.parentPath) }
                        if (orientation != null) MenuRow(Icons.Filled.ScreenLockRotation, "Auto-rotate (follow the phone)") { menu = false; orientation = null }
                        if (current.kind == FileKind.PDF) {
                            MenuRow(Icons.Filled.Lock, "Add password") { menu = false; actions.pdfLockTarget = current }
                            MenuRow(Icons.Filled.LockOpen, "Remove password") { menu = false; actions.pdfUnlockTarget = current }
                        }
                        MenuRow(Icons.Filled.Delete, "Delete") { menu = false; actions.deleteTargets = listOf(current) }
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
        AnimatedVisibility(isImage && chrome && !fullscreen, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier.navigationBarsPadding().padding(16.dp).glassBar(RoundedCornerShape(28.dp)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { actions.share(listOf(current)) }) { Icon(Icons.Filled.Share, "Share") }
                IconButton(onClick = { info = true }) { Icon(Icons.Filled.Info, "Details") }
                IconButton(onClick = { actions.openWith(current) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open with") }
                IconButton(onClick = { actions.deleteTargets = listOf(current) }) { Icon(Icons.Filled.Delete, "Delete") }
            }
        }
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
    FileActionDialogs(actions, onDeleted = { if (list.size <= 1) onBack() else AppState.refresh() })
}

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
