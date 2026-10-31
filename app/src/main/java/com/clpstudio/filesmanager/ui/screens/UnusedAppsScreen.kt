package com.clpstudio.filesmanager.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.clpstudio.filesmanager.data.UnusedApp
import com.clpstudio.filesmanager.data.UnusedApps
import com.clpstudio.filesmanager.data.UnusedBucket
import com.clpstudio.filesmanager.data.UsageAccess
import com.clpstudio.filesmanager.thumbs.AppIcon
import com.clpstudio.filesmanager.ui.components.EmptyState
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.components.formatDate
import com.clpstudio.filesmanager.ui.glass.GlassScaffold

/**
 * Item 36: only installed apps not used for 30+ days, by how long: 30 days, 2 months, 3 months,
 * 6 months, 1 year, Older. Usage access is detected live: granting it in Settings fills the list.
 */
@Composable
fun UnusedAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val access by UsageAccess.granted.collectAsState()
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { UsageAccess.watch(context) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        UsageAccess.check(context)
        reload++
        onPauseOrDispose { }
    }
    val apps by produceState<List<UnusedApp>?>(null, access, reload) {
        value = if (access) runCatching { UnusedApps.load(context) }.getOrDefault(emptyList()) else null
    }
    UnusedAppsContent(
        access = access,
        apps = apps,
        onBack = onBack,
        onAllow = { UsageAccess.openSettings(context) },
        onOpenInfo = { pkg -> runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))) } },
        onUninstall = { pkg -> runCatching { context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) } },
    )
}

@Composable
fun UnusedAppsContent(
    access: Boolean,
    apps: List<UnusedApp>?,
    onBack: () -> Unit,
    onAllow: () -> Unit,
    onOpenInfo: (String) -> Unit,
    onUninstall: (String) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    GlassScaffold(topBar = { GlassTopBar(title = "Unused apps", onBack = onBack) }) { padding ->
        when {
            !access -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.Apps, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.size(12.dp))
                Text("Allow Usage access", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Files needs Usage access to see which apps you haven't used. Turn it on for Files; this screen fills in by itself when you come back. Nothing leaves your device.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Button(onClick = onAllow) { Text("Open settings") }
            }
            apps == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                val counts = UnusedBucket.entries.associateWith { b -> apps.count { it.bucket == b } }
                val shown = apps.filter { filter == null || it.bucket.name == filter }
                LazyColumn(contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 24.dp)) {
                    item(key = "chips") {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All ${apps.size}") })
                            UnusedBucket.entries.forEach { b ->
                                FilterChip(selected = filter == b.name, onClick = { filter = if (filter == b.name) null else b.name }, label = { Text("${b.label} ${counts[b]}") })
                            }
                        }
                    }
                    item(key = "note") {
                        Text(
                            "Not opened for at least 30 days. Android keeps a limited usage history; apps with no record use their install or update date.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    if (shown.isEmpty()) {
                        item(key = "empty") { EmptyState(Icons.Filled.Apps, if (apps.isEmpty()) "No unused apps" else "No apps in this range") }
                    }
                    var lastBucket: UnusedBucket? = null
                    shown.forEach { app ->
                        if (filter == null && app.bucket != lastBucket) {
                            lastBucket = app.bucket
                            item(key = "h_" + app.bucket.name) {
                                Text(headerFor(app.bucket), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp))
                            }
                        }
                        item(key = app.packageName) { UnusedRow(app, onOpenInfo, onUninstall) }
                    }
                }
            }
        }
    }
}

private fun headerFor(b: UnusedBucket): String = when (b) {
    UnusedBucket.OLDER -> "Older (over 2 years or no record)"
    else -> "Not used for ${b.label}+"
}

@Composable
private fun UnusedRow(app: UnusedApp, onOpenInfo: (String) -> Unit, onUninstall: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onOpenInfo(app.packageName) }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(model = AppIcon(app.packageName), contentDescription = null, modifier = Modifier.size(44.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (app.noRecord) "No usage record · installed ${formatDate(app.installedAt)}" else "Last used ${formatDate(app.lastUsed)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
        IconButton(onClick = { onUninstall(app.packageName) }) { Icon(Icons.Filled.Delete, "Uninstall ${app.label}") }
    }
}
