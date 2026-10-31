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
import java.io.File

/** Photo viewer: swipe through the folder, pinch / double-tap to zoom. */
@Composable
fun ImageViewerScreen(paths: List<String>, start: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val list = remember(paths) { paths.filter { File(it).exists() }.ifEmpty { listOf(start) } }
    val pager = rememberPagerState(initialPage = list.indexOf(start).coerceAtLeast(0)) { list.size }
    val zooms = remember { mutableStateMapOf<Int, ZoomState>() }
    var chrome by remember { mutableStateOf(true) }
    val actions = rememberFileActions(afterCopyOrMove = {})
    val current = list.getOrNull(pager.currentPage) ?: start

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            key = { list[it] },
            beyondViewportPageCount = 1,
            userScrollEnabled = zooms[pager.currentPage]?.zoomed != true,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val zoom = zooms.getOrPut(page) { ZoomState() }
            ZoomableImage(Uri.fromFile(File(list[page])), zoom) { chrome = !chrome }
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
            ViewerTopBar(
                title = File(current).name,
                subtitle = "${pager.currentPage + 1} of ${list.size}",
                onBack = onBack,
            )
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            val item = FileItem.of(File(current))
            Row(
                Modifier.navigationBarsPadding().padding(16.dp).glassBar(RoundedCornerShape(28.dp)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { actions.share(listOf(item)) }) { Icon(Icons.Filled.Share, "Share") }
                IconButton(onClick = { actions.detailsTarget = item }) { Icon(Icons.Filled.Info, "Details") }
                IconButton(onClick = { FileOps.openWithOtherApp(context, FileOps.uriFor(context, File(current)), FileTypes.mimeOf(current)) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open with")
                }
                IconButton(onClick = { actions.deleteTargets = listOf(item) }) { Icon(Icons.Filled.Delete, "Delete") }
            }
        }
    }
    FileActionDialogs(actions, onDeleted = onBack)
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

/** A single image opened from another app: zoom, swipe up for info, full screen and rotate. */
@Composable
fun SingleImageViewer(uri: Uri, name: String, onBack: () -> Unit, extraActions: @Composable () -> Unit = {}) {
    val zoom = remember { ZoomState() }
    var chrome by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf(false) }
    var orientation by remember { mutableStateOf<Int?>(null) }
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    ImmersiveEffect(!chrome)
    OrientationEffect(orientation)
    Box(Modifier.fillMaxSize().background(Color.Black).swipeUpToOpen(enabled = !zoom.zoomed) { info = true }) {
        ZoomableImage(uri, zoom) { chrome = !chrome }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
            ViewerTopBar(name, null, onBack) {
                IconButton(onClick = { info = true }) { Icon(Icons.Filled.Info, "File info") }
                IconButton(onClick = {
                    orientation = if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }) { Icon(Icons.Filled.ScreenRotation, "Rotate") }
                extraActions()
            }
        }
    }
    if (info) FileInfoSheet(InfoTarget(uri, name, uri.takeIf { it.scheme == "file" }?.path), emptyList()) { info = false }
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
