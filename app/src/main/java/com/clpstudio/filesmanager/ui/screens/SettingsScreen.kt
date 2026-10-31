package com.clpstudio.filesmanager.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.FastForward
import com.clpstudio.filesmanager.data.DoubleTapSkip
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.BuildConfig
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.R
import com.clpstudio.filesmanager.data.AppSettings
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.data.DefaultView
import com.clpstudio.filesmanager.data.FileView
import androidx.compose.material.icons.automirrored.filled.ViewList
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.glass.glassPanel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit, onBin: () -> Unit, onDiagnostics: () -> Unit = {}, onCrashReports: () -> Unit = {}) {
    val settings by Graph.settings.collectAsState()
    val scope = rememberCoroutineScope()
    SettingsContent(
        settings = settings,
        onBack = onBack,
        onTheme = { scope.launch { Graph.settingsRepo.setTheme(it) } },
        onGlass = { scope.launch { Graph.settingsRepo.setLiquidGlass(it) } },
        onHidden = { scope.launch { Graph.settingsRepo.setShowHidden(it) } },
        onBin = onBin,
        onDefaultView = { scope.launch { Graph.settingsRepo.setDefaultView(it) } },
        onHideInRecents = { scope.launch { Graph.settingsRepo.setHideInRecents(it) } },
        onFileView = { scope.launch { Graph.settingsRepo.setFileView(it) } },
        onDiagnostics = onDiagnostics,
        onCrashReports = onCrashReports,
        onBackgroundPlay = { scope.launch { Graph.settingsRepo.setBackgroundPlay(it) } },
        onAutoPip = { scope.launch { Graph.settingsRepo.setAutoPip(it) } },
        onDoubleTapSkip = { scope.launch { Graph.settingsRepo.setDoubleTapSkip(it) } },
    )
}

@Composable
fun SettingsContent(
    settings: AppSettings,
    onBack: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onGlass: (Boolean) -> Unit,
    onHidden: (Boolean) -> Unit,
    onBin: () -> Unit,
    onDefaultView: (DefaultView) -> Unit = {},
    onHideInRecents: (Boolean) -> Unit = {},
    onFileView: (com.clpstudio.filesmanager.data.FileView) -> Unit = {},
    onDiagnostics: () -> Unit = {},
    onCrashReports: () -> Unit = {},
    onBackgroundPlay: (Boolean) -> Unit = {},
    onAutoPip: (Boolean) -> Unit = {},
    onDoubleTapSkip: (Int) -> Unit = {},
) {
    GlassScaffold(topBar = { GlassTopBar("Settings", onBack = onBack) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { Header("Appearance") }
            item {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RowIcon(Icons.Filled.Palette)
                        Text("Theme", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.size(10.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val modes = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
                        modes.forEachIndexed { i, (m, label) ->
                            SegmentedButton(selected = settings.theme == m, onClick = { onTheme(m) }, shape = SegmentedButtonDefaults.itemShape(i, modes.size)) { Text(label) }
                        }
                    }
                }
            }
            item {
                SwitchCard(Icons.Filled.AutoAwesome, "Liquid glass", "Frosted glass bars, blur and soft highlights. Off = normal flat look.", settings.liquidGlass, onGlass)
            }
            item { Header("Files") }
            item {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RowIcon(Icons.AutoMirrored.Filled.ViewList)
                        Column(Modifier.weight(1f)) {
                            Text("Default view", style = MaterialTheme.typography.titleSmall)
                            Text("How Recent, Photos, Videos, Audios, Documents and Downloads open.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.size(10.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val views = listOf(DefaultView.ALL_FILES to "All files", DefaultView.FOLDERS to "Folders")
                        views.forEachIndexed { i, (v, label) ->
                            SegmentedButton(selected = settings.defaultView == v, onClick = { onDefaultView(v) }, shape = SegmentedButtonDefaults.itemShape(i, views.size)) { Text(label) }
                        }
                    }
                }
            }
            item {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RowIcon(Icons.AutoMirrored.Filled.ViewList)
                        Column(Modifier.weight(1f)) {
                            Text("File view", style = MaterialTheme.typography.titleSmall)
                            Text("How files look in every category and folder. Real ratio keeps each photo's shape (photos and videos only).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.size(10.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val views = FileView.entries.map { it to it.label }
                        views.forEachIndexed { i, (v, label) ->
                            SegmentedButton(selected = settings.fileView == v, onClick = { onFileView(v) }, shape = SegmentedButtonDefaults.itemShape(i, views.size)) { Text(label) }
                        }
                    }
                }
            }
            item {
                SwitchCard(Icons.Filled.Visibility, "Show hidden files", "Shows dot-files, dot-folders and folders with .nomedia. Vault items are never shown here.", settings.showHidden, onHidden)
            }
            item {
                Card(Modifier.clickable(onClick = onBin)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RowIcon(Icons.Filled.Delete)
                        Column(Modifier.weight(1f)) {
                            Text("Bin", style = MaterialTheme.typography.titleSmall)
                            Text("Deleted files are kept 30 days, then removed automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { Header("Video player") }
            item {
                SwitchCard(Icons.Filled.Headphones, "Background play", "Videos keep playing with the screen off or in other apps, with controls in the notification. Close the player to stop.", settings.backgroundPlay, onBackgroundPlay)
            }
            item {
                SwitchCard(Icons.Filled.PictureInPictureAlt, "Auto picture-in-picture", "Going Home while a video plays shows it in a small window over other apps.", settings.autoPip, onAutoPip)
            }
            item { DoubleTapSkipCard(settings.doubleTapSkipSeconds, onDoubleTapSkip) }
            item { Header("Security") }
            item {
                SwitchCard(Icons.Filled.Lock, "Hide content while switching apps", "Your files don't show in the app switcher (Recents).", settings.hideInRecents, onHideInRecents)
            }
            item { InfoCard(Icons.Filled.Lock, "App lock", "PIN (4–10 digits) or password (8–72 characters). Coming in the next update.") }
            item { InfoCard(Icons.Filled.Shield, "Vault", "Hardware-encrypted private vault with its own PIN or password. Coming in the next update.") }
            item { Header("About") }
            item {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.logo_full), null, Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Files", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
                            Text("by CLP Studio · works fully offline, no internet permission", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Card(Modifier.clickable(onClick = onDiagnostics)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RowIcon(Icons.Filled.BugReport)
                        Column(Modifier.weight(1f)) {
                            Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
                            Text("What Files can see on this phone, and its error log (kept on the phone).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Card(Modifier.clickable(onClick = onCrashReports)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RowIcon(Icons.Filled.ReportProblem)
                        Column(Modifier.weight(1f)) {
                            Text("Crash reports", style = MaterialTheme.typography.titleSmall)
                            Text("Saved if Files ever closes unexpectedly. View, share with us, or clear.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp, top = 8.dp))
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(22.dp)).then(modifier).padding(16.dp)) { content() }
}

@Composable
private fun RowIcon(icon: ImageVector) {
    Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.width(14.dp))
}

@Composable
private fun SwitchCard(icon: ImageVector, title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.clickable { onChange(!checked) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(icon)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, text: String) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(icon)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Settings → Video player → Double-tap skip: 5 / 10 / 15 / 20 / 30 s chips + Custom (1–60 s). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DoubleTapSkipCard(seconds: Int, onChange: (Int) -> Unit) {
    var custom by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(Icons.Filled.FastForward)
            Column(Modifier.weight(1f)) {
                Text("Double-tap skip", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Double-tap the left or right side of a video to go back or forward.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(DoubleTapSkip.label(seconds), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.size(10.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DoubleTapSkip.PRESETS.forEach { p ->
                androidx.compose.material3.FilterChip(
                    selected = seconds == p,
                    onClick = { onChange(p) },
                    label = { Text("$p s" + if (p == DoubleTapSkip.DEFAULT) " (default)" else "") },
                )
            }
            androidx.compose.material3.FilterChip(
                selected = !DoubleTapSkip.isPreset(seconds),
                onClick = { custom = true },
                label = { Text(if (DoubleTapSkip.isPreset(seconds)) "Custom" else "Custom · ${seconds} s") },
            )
        }
    }
    if (custom) {
        var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(seconds.toString()) }
        val value = DoubleTapSkip.parse(text)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { custom = false },
            title = { Text("Double-tap skip") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { t -> text = t.filter { it.isDigit() }.take(2) },
                    label = { Text("Seconds (1–60)") },
                    singleLine = true,
                    isError = value == null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    supportingText = { if (value == null) Text("Enter a number from 1 to 60") },
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(enabled = value != null, onClick = { value?.let(onChange); custom = false }) { Text("Save") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { custom = false }) { Text("Cancel") } },
        )
    }
}
