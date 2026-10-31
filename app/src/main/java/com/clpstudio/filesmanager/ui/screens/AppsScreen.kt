package com.clpstudio.filesmanager.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileOps
import com.clpstudio.filesmanager.data.InstalledApp
import com.clpstudio.filesmanager.thumbs.AppIcon
import com.clpstudio.filesmanager.ui.AppState
import com.clpstudio.filesmanager.ui.Opener
import com.clpstudio.filesmanager.ui.components.EmptyState
import com.clpstudio.filesmanager.ui.components.FileActionDialogs
import com.clpstudio.filesmanager.ui.components.FileRow
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.components.MenuRow
import com.clpstudio.filesmanager.ui.components.formatDate
import com.clpstudio.filesmanager.ui.components.formatSize
import com.clpstudio.filesmanager.ui.components.rememberFileActions
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Apps: APK files on storage, downloaded (user) apps, system apps. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("APK files", "Downloaded", "System")
    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = "Apps", onBack = onBack,
                below = {
                    PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                        tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
                    }
                },
            )
        },
    ) { padding ->
        when (tab) {
            0 -> ApkFilesList(padding)
            1 -> InstalledList(system = false, padding = padding)
            else -> InstalledList(system = true, padding = padding)
        }
    }
}

@Composable
private fun ApkFilesList(padding: PaddingValues) {
    val context = LocalContext.current
    val settings by Graph.settings.collectAsState()
    val actions = rememberFileActions(afterCopyOrMove = {})
    val apks by produceState<List<FileItem>?>(null, settings.showHidden, AppState.refreshTick) {
        value = Graph.media.apkFiles(Graph.hiddenFilter(settings.showHidden))
    }
    val list = apks
    when {
        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        list.isEmpty() -> EmptyState(Icons.Filled.Android, "No APK files on this phone", modifier = Modifier.padding(padding))
        else -> LazyColumn(contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 24.dp)) {
            items(list, key = { it.path }) { item ->
                val info by produceState<Pair<String, String>?>(null, item.path) {
                    value = withContext(Dispatchers.IO) { Graph.apps.apkInfo(item.path) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        FileRow(
                            item = item, selected = false, selectionMode = false,
                            onClick = { Opener.installApk(context, File(item.path)) },
                            onLongClick = { actions.detailsTarget = item },
                            extraLine = info?.let { (label, ver) -> if (ver.isNotEmpty()) "$label · v$ver" else label },
                        )
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            MenuRow(Icons.Filled.InstallMobile, "Install") { menu = false; Opener.installApk(context, File(item.path)) }
                            MenuRow(Icons.Filled.Share, "Share") { menu = false; actions.share(listOf(item)) }
                            MenuRow(Icons.Filled.Info, "Details") { menu = false; actions.detailsTarget = item }
                            MenuRow(Icons.Filled.Delete, "Delete") { menu = false; actions.deleteTargets = listOf(item) }
                        }
                    }
                }
            }
        }
    }
    FileActionDialogs(actions)
}

@Composable
private fun InstalledList(system: Boolean, padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    val apps by produceState<List<InstalledApp>?>(null, system, reload) { value = Graph.apps.installed(system) }
    val list = apps
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        reload++
        onPauseOrDispose { }
    }
    when {
        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        list.isEmpty() -> EmptyState(Icons.Filled.Android, "No apps", modifier = Modifier.padding(padding))
        else -> LazyColumn(contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 24.dp)) {
            item(key = "count") {
                Text("${list.size} apps", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
            items(list, key = { it.packageName }) { app ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                        if (app.canLaunch) context.packageManager.getLaunchIntentForPackage(app.packageName)?.let { context.startActivity(it) }
                        else openAppInfo(context, app.packageName)
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(model = AppIcon(app.packageName), contentDescription = null, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOf(app.versionName.takeIf { it.isNotEmpty() }?.let { "v$it" }, formatSize(app.size), formatDate(app.installedAt))
                                .filterNotNull().joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            if (app.canLaunch) MenuRow(Icons.AutoMirrored.Filled.OpenInNew, "Open") {
                                menu = false
                                context.packageManager.getLaunchIntentForPackage(app.packageName)?.let { context.startActivity(it) }
                            }
                            MenuRow(Icons.Filled.Info, "App info") { menu = false; openAppInfo(context, app.packageName) }
                            MenuRow(Icons.Filled.Share, "Share APK") {
                                menu = false
                                scope.launch {
                                    runCatching { Graph.apps.extractApk(app) }.onSuccess { FileOps.share(context, listOf(it)) }
                                        .onFailure { Toast.makeText(context, "Couldn't copy the APK", Toast.LENGTH_SHORT).show() }
                                }
                            }
                            MenuRow(Icons.Filled.SaveAlt, "Save APK to Downloads") {
                                menu = false
                                scope.launch {
                                    runCatching { Graph.apps.extractApk(app) }
                                        .onSuccess { Toast.makeText(context, "Saved to Download/Files Apps/${it.name}", Toast.LENGTH_LONG).show() }
                                        .onFailure { Toast.makeText(context, "Couldn't copy the APK", Toast.LENGTH_SHORT).show() }
                                }
                            }
                            if (!system) MenuRow(Icons.Filled.Delete, "Uninstall") {
                                menu = false
                                context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.packageName)))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun openAppInfo(context: android.content.Context, pkg: String) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
}
