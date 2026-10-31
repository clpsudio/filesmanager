package com.clpstudio.filesmanager.viewer

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileOps
import com.clpstudio.filesmanager.data.FileTypes
import com.clpstudio.filesmanager.ui.components.FileActionDialogs
import com.clpstudio.filesmanager.ui.components.rememberFileActions
import com.clpstudio.filesmanager.ui.glass.glassBar
import android.widget.Toast
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.rememberCoroutineScope
import com.clpstudio.filesmanager.data.Favorites
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.ui.components.MenuRow
import kotlinx.coroutines.launch
import java.io.File

/** Photo viewer: swipe through the folder, pinch / double-tap to zoom. */
@Composable
fun ImageViewerScreen(paths: List<String>, start: String, onBack: () -> Unit) {
    val context = LocalContext.current
    Favorites.init(context) // idempotent: one volatile read after the first call
    val scope = rememberCoroutineScope()
    val list = remember(paths) { paths.distinct().filter { File(it).exists() }.ifEmpty { listOf(start) } } // distinct: pager keys must be unique
    val pager = rememberPagerState(initialPage = list.indexOf(start).coerceAtLeast(0)) { list.size }
    val zooms = remember { mutableStateMapOf<Int, ZoomState>() }
    var chrome by remember { mutableStateOf(true) }
    val actions = rememberFileActions(afterCopyOrMove = {})
    val current = list.getOrNull(pager.currentPage) ?: start

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            key = { list.getOrNull(it) ?: "#$it" },
            beyondViewportPageCount = 1,
            userScrollEnabled = zooms[pager.currentPage]?.zoomed != true,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val path = list.getOrNull(page) ?: return@HorizontalPager
            val zoom = zooms.getOrPut(page) { ZoomState() }
            ZoomableImage(Uri.fromFile(File(path)), zoom) { chrome = !chrome }
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
            ViewerTopBar(
                title = File(current).name,
                subtitle = "${pager.currentPage + 1} of ${list.size}",
                onBack = onBack,
            ) {
                FavoriteButton(current)
                IconButton(onClick = { actions.detailsTarget = FileItem.of(File(current)) }) { Icon(Icons.Filled.Info, "Details") }
                IconButton(onClick = { FileOps.openWithOtherApp(context, FileOps.uriFor(context, File(current)), FileTypes.mimeOf(current)) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open with")
                }
            }
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            val items = remember(list) { list.map { FileItem.of(File(it)) } }
            ViewerBottomBar(
                items = items,
                current = pager.currentPage,
                onSelect = { scope.launch { pager.animateScrollToPage(it) } },
                onShare = { actions.share(listOf(FileItem.of(File(current)))) },
                onDelete = { actions.deleteTargets = listOf(FileItem.of(File(current))) },
            )
        }
    }
    FileActionDialogs(actions, onDeleted = {
        Favorites.onDeleted(listOf(current).filter { !File(it).exists() })
        onBack()
    })
}

@Composable
fun ZoomableImage(uri: Uri, zoom: ZoomState, onTap: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().zoomable(zoom, onTap), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(uri).size(3072).build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * A single image opened from another app: zoom, swipe up for info, full screen and rotate, a star and a ⋮ menu
 * (Edit, Set as, Hide, Info). [path] is the file on storage when known (star and Hide need it); a file:// [uri]
 * gives it too.
 */
@Composable
fun SingleImageViewer(
    uri: Uri,
    name: String,
    onBack: () -> Unit,
    extraActions: @Composable () -> Unit = {},
    path: String? = uri.takeIf { it.scheme == "file" }?.path,
) {
    val context = LocalContext.current
    val zoom = remember { ZoomState() }
    var chrome by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var orientation by remember { mutableStateOf<Int?>(null) }
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val file = path?.let { File(it) }?.takeIf { it.isFile }
    val mime = FileTypes.mimeOf(name).takeIf { it.startsWith("image/") } ?: "image/*"
    ImmersiveEffect(!chrome)
    OrientationEffect(orientation)
    val actions = rememberFileActions(afterCopyOrMove = {})
    val pill = remember { LevelPillState() }
    val dismiss = remember { DismissDragState() }
    val dismissPx = with(androidx.compose.ui.platform.LocalDensity.current) { ViewerGestureMath.DISMISS_DP.dp.toPx() }
    val dismissing = ViewerGestureMath.dismissProgress(dismiss.value, dismissPx)
    ResetBrightnessOnExit()
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f - dismissing))) {
        Box(
            Modifier.fillMaxSize()
                .photoSwipeGestures(zoomed = { zoom.zoomed }, pill = pill, dismiss = dismiss, onInfo = { info = true }, onDismiss = onBack)
                .graphicsLayer {
                    translationY = dismiss.value
                    scaleX = 1f - 0.15f * dismissing
                    scaleY = 1f - 0.15f * dismissing
                },
        ) { ZoomableImage(uri, zoom) { chrome = !chrome } }
        AnimatedVisibility(chrome && dismissing == 0f, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            ViewerActionPanel(
                path = file?.absolutePath,
                onShare = {
                    if (file != null) actions.share(listOf(FileItem.of(file)))
                    else com.clpstudio.filesmanager.data.SafeStart.start(
                        context,
                        android.content.Intent.createChooser(
                            android.content.Intent(android.content.Intent.ACTION_SEND).setType(mime).putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            "Share",
                        ),
                        "This app doesn't allow sharing this photo from here",
                    )
                },
                onInfo = { info = true },
                onDelete = if (file != null) ({ actions.deleteTargets = listOf(FileItem.of(file)) }) else null,
            )
        }
        LevelPillHost(pill)
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
            ViewerTopBar(name, null, onBack) {
                if (file != null) FavoriteButton(file.absolutePath)
                IconButton(onClick = {
                    orientation = if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }) { Icon(Icons.Filled.ScreenRotation, "Rotate") }
                extraActions()
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        val entries = viewerMenuEntries(
                            kind = FileKind.IMAGE,
                            onEdit = { if (file != null) PhotoActions.edit(context, file) else PhotoActions.edit(context, uri, mime) },
                            onSetAs = { if (file != null) PhotoActions.setAs(context, file) else PhotoActions.setAs(context, uri, mime) },
                            onHide = {
                                if (file != null && PhotoActions.hide(context, file) != null) {
                                    Toast.makeText(context, "Hidden. Turn on Show hidden files to see it.", Toast.LENGTH_LONG).show()
                                    onBack()
                                } else {
                                    Toast.makeText(context, "Only photos saved on this device can be hidden", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onInfo = { info = true },
                            hidden = file != null && com.clpstudio.filesmanager.data.HiddenNames.isHidden(file.absolutePath),
                            onUnhide = {
                                if (file != null && com.clpstudio.filesmanager.data.HiddenOps.unhide(context, file) != null) {
                                    Toast.makeText(context, "Unhidden", Toast.LENGTH_SHORT).show()
                                    onBack()
                                } else {
                                    Toast.makeText(context, "Couldn't unhide it", Toast.LENGTH_SHORT).show()
                                }
                            },
                        ).filter { file != null || (it.label != "Hide" && it.label != "Unhide") }
                        entries.forEach { e -> MenuRow(e.icon, e.label) { menu = false; e.onClick() } }
                    }
                }
            }
        }
    }
    if (info) FileInfoSheet(InfoTarget(uri, name, file?.absolutePath ?: uri.takeIf { it.scheme == "file" }?.path), emptyList()) { info = false }
    FileActionDialogs(actions, onDeleted = {
        if (file != null && !file.exists()) { Favorites.onDeleted(listOf(file.absolutePath)); onBack() }
    })
}

@Composable
fun ViewerTopBar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    titleColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    actions: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().glassBar().statusBarsPadding().padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.titleMedium, color = titleColor)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}
