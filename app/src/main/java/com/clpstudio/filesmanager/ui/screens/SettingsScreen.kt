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
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import com.clpstudio.filesmanager.data.DoubleTapSkip
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Edit
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
fun SettingsScreen(onBack: () -> Unit, onBin: () -> Unit, onDiagnostics: () -> Unit = {}, onLogs: () -> Unit = {}, onCrashReports: () -> Unit = {}, onFolderFilters: () -> Unit = {}, onAppLock: () -> Unit = {}, onVaultSettings: () -> Unit = {}) {
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
        onBlockScreenshots = { scope.launch { Graph.settingsRepo.setBlockScreenshots(it) } },
        onAllowExtensionChange = { scope.launch { Graph.settingsRepo.setAllowExtensionChange(it) } },
        onFileView = { scope.launch { Graph.settingsRepo.setFileView(it) } },
        onDiagnostics = onDiagnostics,
        onLogs = onLogs,
        onCrashReports = onCrashReports,
        onBackgroundPlay = { scope.launch { Graph.settingsRepo.setBackgroundPlay(it) } },
        onAutoPip = { scope.launch { Graph.settingsRepo.setAutoPip(it) } },
        onHiddenInSearch = { scope.launch { Graph.settingsRepo.setHiddenInSearch(it) } },
        onDoubleTapSkip = { scope.launch { Graph.settingsRepo.setDoubleTapSkip(it) } },
        onLoop = { mode, times -> scope.launch { Graph.settingsRepo.setLoop(mode, times) } },
        onDateGrouping = { scope.launch { Graph.settingsRepo.setDateGrouping(it) } },
        onDefaultSort = { by, asc -> scope.launch { Graph.settingsRepo.setDefaultSort(by, asc) } },
        onFolderFilters = onFolderFilters,
        onDuck = { scope.launch { Graph.settingsRepo.setDuckForNotifications(it) } },
        onShowSdCard = { scope.launch { Graph.settingsRepo.setShowSdCard(it) } },
        onAppLock = onAppLock,
        onVaultSettings = onVaultSettings,
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
    onBlockScreenshots: (Boolean) -> Unit = {},
    onAllowExtensionChange: (Boolean) -> Unit = {},
    onFileView: (com.clpstudio.filesmanager.data.FileView) -> Unit = {},
    onDiagnostics: () -> Unit = {},
    onLogs: () -> Unit = {},
    onCrashReports: () -> Unit = {},
    onBackgroundPlay: (Boolean) -> Unit = {},
    onAutoPip: (Boolean) -> Unit = {},
    onHiddenInSearch: (Boolean) -> Unit = {},
    onDoubleTapSkip: (Int) -> Unit = {},
    onLoop: (com.clpstudio.filesmanager.data.LoopMode, Int) -> Unit = { _, _ -> },
    onDateGrouping: (com.clpstudio.filesmanager.data.DateGrouping) -> Unit = {},
    onDefaultSort: (com.clpstudio.filesmanager.data.SortBy, Boolean) -> Unit = { _, _ -> },
    onFolderFilters: () -> Unit = {},
    onDuck: (Boolean) -> Unit = {},
    onShowSdCard: (Boolean) -> Unit = {},
    onAppLock: () -> Unit = {},
    onVaultSettings: () -> Unit = {},
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
            if (settings.showHidden) item {
                SwitchCard(Icons.Filled.Search, "Show hidden files in search", "Search results also list hidden files and folders. Off: search skips them even while browsing shows them.", settings.hiddenInSearch, onHiddenInSearch)
            }
            item {
                SwitchCard(Icons.Filled.Edit, "Allow changing file extension", "Off: Rename changes only the name and keeps the type (photo + .jpg). On: the whole name can be edited, and changing the extension asks first.", settings.allowExtensionChange, onAllowExtensionChange)
            }
            item {
                SwitchCard(Icons.Filled.SdCard, "Show SD card", "SD card files appear in Photos, Videos, Audios, Documents, Downloads, Recent and search together with phone files. Off hides them everywhere.", settings.showSdCard, onShowSdCard)
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
            item { Header("Sorting & grouping") }
            item { DefaultSortCard(settings.defaultSortBy, settings.defaultSortAscending, onDefaultSort) }
            item { GroupByCard(settings.dateGrouping, onDateGrouping) }
            item { FolderFiltersRow(onFolderFilters) }
            item { Header("Video player") }
            item {
                SwitchCard(Icons.Filled.Headphones, "Background play", "Videos keep playing with the screen off or in other apps, with controls in the notification. Close the player to stop.", settings.backgroundPlay, onBackgroundPlay)
            }
            item {
                SwitchCard(Icons.Filled.PictureInPictureAlt, "Auto picture-in-picture", "Going Home while a video plays shows it in a small window over other apps.", settings.autoPip, onAutoPip)
            }
            item { LoopVideosCard(settings.loopMode, settings.loopTimes, onChange = onLoop) }
            item {
                SwitchCard(
                    Icons.AutoMirrored.Filled.VolumeDown, "Lower volume for notifications",
                    "Videos and music get quieter for a moment while a notification sounds. Calls always pause them; they play again 2 seconds after the call.",
                    settings.duckForNotifications, onDuck,
                )
            }
            item { DoubleTapSkipCard(settings.doubleTapSkipSeconds, onDoubleTapSkip) }
            // Audio player: its own loop (item 26), never shared with the video one.
            item { Header("Audio player") }
            item {
                LoopVideosCard(
                    settings.audioLoopMode, settings.audioLoopTimes,
                    onChange = { mode, times -> Graph.appScope.launch { Graph.settingsRepo.setAudioLoop(mode, times) } },
                    title = "Audio loop",
                    description = "Audio player. Off plays each song once, then the next. N times plays it N times, then the next. Forever repeats the song. After the last song it starts again from the first.",
                    eachLabel = "Play each song",
                )
            }
            item { Header("Security") }
            item {
                SwitchCard(Icons.Filled.Lock, "Hide content while switching apps", "Your files don't show in the app switcher (Recents).", settings.hideInRecents, onHideInRecents)
            }
            item {
                SwitchCard(Icons.Filled.Lock, "Block screenshots & screen recording", "Screenshots, screen recordings and the app switcher show a blank screen. Vault screens are always blocked.", settings.blockScreenshots, onBlockScreenshots)
            }
            item { com.clpstudio.filesmanager.ui.security.SecuritySettingsRows(onAppLock, onVaultSettings) }
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
                Card(Modifier.clickable(onClick = onLogs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RowIcon(Icons.Filled.Description)
                        Column(Modifier.weight(1f)) {
                            Text("Logs", style = MaterialTheme.typography.titleSmall)
                            Text("App log with errors and crashes. Share it to report a bug on GitHub, or delete it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** Settings → Video player → Loop videos: Off / Forever (default) / N more times (1–10). */
@Composable
fun LoopVideosCard(
    mode: com.clpstudio.filesmanager.data.LoopMode,
    times: Int,
    title: String = "Loop videos",
    description: String = "Forever plays the same video again and again. N times plays it N times, then the next video. Off plays the next video.",
    eachLabel: String = "Play each video",
    onChange: (com.clpstudio.filesmanager.data.LoopMode, Int) -> Unit,
) {
    val modes = com.clpstudio.filesmanager.data.LoopMode.entries
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(Icons.Filled.Repeat)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(com.clpstudio.filesmanager.data.LoopPolicy.label(mode, times), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.size(10.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val labels = listOf("Off", "Forever", "N times")
            modes.forEachIndexed { i, m ->
                SegmentedButton(selected = mode == m, onClick = { onChange(m, times) }, shape = SegmentedButtonDefaults.itemShape(i, modes.size)) { Text(labels[i]) }
            }
        }
        if (mode == com.clpstudio.filesmanager.data.LoopMode.TIMES) {
            Spacer(Modifier.size(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(eachLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                androidx.compose.material3.FilledTonalIconButton(
                    onClick = { onChange(mode, com.clpstudio.filesmanager.data.LoopPolicy.clampTimes(times - 1)) },
                    enabled = times > com.clpstudio.filesmanager.data.LoopPolicy.MIN_TIMES,
                ) { Icon(Icons.Filled.Remove, "Fewer") }
                Text(
                    "$times times",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                androidx.compose.material3.FilledTonalIconButton(
                    onClick = { onChange(mode, com.clpstudio.filesmanager.data.LoopPolicy.clampTimes(times + 1)) },
                    enabled = times < com.clpstudio.filesmanager.data.LoopPolicy.MAX_TIMES,
                ) { Icon(Icons.Filled.Add, "More") }
            }
        }
    }
}

/** Settings → "Group files by": headers in lists sorted by date (Daily / Weekly / Monthly / Quarterly / Yearly / None). */
@Composable
fun GroupByCard(value: com.clpstudio.filesmanager.data.DateGrouping, onChange: (com.clpstudio.filesmanager.data.DateGrouping) -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(Icons.Filled.CalendarMonth)
            Column(Modifier.weight(1f)) {
                Text("Group files by", style = MaterialTheme.typography.titleSmall)
                Text("Only when a list is sorted by Date: headers like \"October 2026\". In select mode tick a header to select that group.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.size(10.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            val order = listOf(
                com.clpstudio.filesmanager.data.DateGrouping.DAY, com.clpstudio.filesmanager.data.DateGrouping.WEEK,
                com.clpstudio.filesmanager.data.DateGrouping.MONTH, com.clpstudio.filesmanager.data.DateGrouping.QUARTER,
                com.clpstudio.filesmanager.data.DateGrouping.YEAR, com.clpstudio.filesmanager.data.DateGrouping.NONE,
            )
            order.forEach { g ->
                androidx.compose.material3.FilterChip(
                    selected = g == value, onClick = { onChange(g) }, label = { Text(g.label) },
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = androidx.compose.ui.graphics.Color.White,
                    ),
                )
            }
        }
    }
}

/** Settings → Default sort: Date / Name / Size / Type and the direction (Date, newest first by default). */
@Composable
fun DefaultSortCard(by: com.clpstudio.filesmanager.data.SortBy, ascending: Boolean, onChange: (com.clpstudio.filesmanager.data.SortBy, Boolean) -> Unit) {
    val opts = listOf(
        com.clpstudio.filesmanager.data.SortBy.DATE to "Date", com.clpstudio.filesmanager.data.SortBy.NAME to "Name",
        com.clpstudio.filesmanager.data.SortBy.SIZE to "Size", com.clpstudio.filesmanager.data.SortBy.TYPE to "Type",
    )
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(Icons.AutoMirrored.Filled.Sort)
            Column(Modifier.weight(1f)) {
                Text("Default sort", style = MaterialTheme.typography.titleSmall)
                Text("Every list and folder opens sorted like this. The sort menu on a screen still changes it there.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.size(10.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            opts.forEachIndexed { i, (s, label) ->
                SegmentedButton(selected = by == s, onClick = { onChange(s, ascending) }, shape = SegmentedButtonDefaults.itemShape(i, opts.size)) { Text(label) }
            }
        }
        Spacer(Modifier.size(8.dp))
        val (descLabel, ascLabel) = when (by) {
            com.clpstudio.filesmanager.data.SortBy.DATE -> "Newest first" to "Oldest first"
            com.clpstudio.filesmanager.data.SortBy.SIZE -> "Largest first" to "Smallest first"
            else -> "Z to A" to "A to Z"
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = !ascending, onClick = { onChange(by, false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(descLabel) }
            SegmentedButton(selected = ascending, onClick = { onChange(by, true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(ascLabel) }
        }
    }
}

/** Settings → Folder filters: opens its own screen. */
@Composable
fun FolderFiltersRow(onClick: () -> Unit) {
    Card(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(Icons.Filled.FilterList)
            Column(Modifier.weight(1f)) {
                Text("Folder filters", style = MaterialTheme.typography.titleSmall)
                Text("Choose which chips (Camera, Screenshots, WhatsApp...) show above your lists, and their order.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
