package com.clpstudio.filesmanager.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.DateQueryParser
import com.clpstudio.filesmanager.data.DateRange
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.QuickDate
import com.clpstudio.filesmanager.data.SearchQuery
import com.clpstudio.filesmanager.data.StoragePaths
import com.clpstudio.filesmanager.data.TypeFilter
import com.clpstudio.filesmanager.ui.AppState
import com.clpstudio.filesmanager.ui.Opener
import com.clpstudio.filesmanager.ui.components.FileRow
import com.clpstudio.filesmanager.ui.components.SearchField
import com.clpstudio.filesmanager.ui.components.formatDate
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.glass.glassBar
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val chipFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

private fun DateRange.label(): String =
    if (start == endInclusive) chipFmt.format(start) else chipFmt.format(start) + " – " + chipFmt.format(endInclusive)

/** Search by name and/or date (typed or chips), with type filters. Results stream in. */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun SearchScreen(root: String?, initialType: String?, opener: Opener, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by Graph.settings.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var quick by rememberSaveable { mutableStateOf(QuickDate.ANY) }
    var custom by remember { mutableStateOf<DateRange?>(null) }
    var types by remember { mutableStateOf(initialType?.let { t -> TypeFilter.entries.filter { it.name == t }.toSet() } ?: emptySet()) }
    var showPicker by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    val parsed = remember(text) { DateQueryParser.parse(text) }
    val range: DateRange? = parsed.range ?: if (quick == QuickDate.CUSTOM) custom else DateQueryParser.quick(quick, LocalDate.now())
    val query = SearchQuery(name = parsed.nameQuery, range = range, types = types, root = root)

    LaunchedEffect(Unit) {
        snapshotFlow {
            val p = DateQueryParser.parse(text)
            val r = p.range ?: if (quick == QuickDate.CUSTOM) custom else DateQueryParser.quick(quick, LocalDate.now())
            Triple(SearchQuery(name = p.nameQuery, range = r, types = types, root = root), settings.showHidden, AppState.refreshTick)
        }
            .debounce(250)
            .collectLatest { (q, hidden, _) ->
                searching = true
                Graph.search.search(q, Graph.hiddenFilter(hidden), StoragePaths.volumes(context).map { it.path })
                    .collect { results = it }
                searching = false
            }
    }

    SearchContent(
        root = root,
        text = text,
        onText = { text = it },
        typedRangeLabel = parsed.range?.label(),
        quick = quick,
        onQuick = { q -> if (q == QuickDate.CUSTOM) showPicker = true else quick = if (quick == q) QuickDate.ANY else q },
        customLabel = custom?.label(),
        types = types,
        onToggleType = { t -> types = if (t in types) types - t else types + t },
        queryEmpty = query.isEmpty,
        results = com.clpstudio.filesmanager.ui.components.rememberLiveFiles(results).orEmpty(),
        searching = searching,
        onBack = onBack,
        onOpen = { item -> opener.open(item, results) },
    )

    if (showPicker) {
        val state = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val s = state.selectedStartDateMillis
                    val e = state.selectedEndDateMillis ?: s
                    if (s != null && e != null) {
                        custom = DateRange(
                            Instant.ofEpochMilli(s).atZone(ZoneOffset.UTC).toLocalDate(),
                            Instant.ofEpochMilli(e).atZone(ZoneOffset.UTC).toLocalDate(),
                        )
                        quick = QuickDate.CUSTOM
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
        ) {
            Box(Modifier.weight(1f)) {
                DateRangePicker(state = state, title = { Text("Pick dates", Modifier.padding(16.dp)) }, showModeToggle = true)
            }
        }
    }
}

/** The search screen's visuals (state lives in [SearchScreen]); also rendered by screenshot tests. */
@Composable
internal fun SearchContent(
    root: String?,
    text: String,
    onText: (String) -> Unit,
    typedRangeLabel: String?,
    quick: QuickDate,
    onQuick: (QuickDate) -> Unit,
    customLabel: String?,
    types: Set<TypeFilter>,
    onToggleType: (TypeFilter) -> Unit,
    queryEmpty: Boolean,
    results: List<FileItem>,
    searching: Boolean,
    onBack: () -> Unit,
    onOpen: (FileItem) -> Unit,
    autoFocus: Boolean = true,
) {
    val chipColors = searchChipColors()
    GlassScaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().glassBar().statusBarsPadding().padding(bottom = 8.dp)) {
                Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    SearchField(
                        value = text, onValueChange = onText,
                        placeholder = if (root != null) "Search in " + root.substringAfterLast('/') else "Name or date: 31/10/2026, Oct 2026",
                        autoFocus = autoFocus, modifier = Modifier.weight(1f),
                    )
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (typedRangeLabel != null) {
                        item(key = "typed") {
                            InputChip(
                                selected = true, onClick = {}, label = { Text(typedRangeLabel) },
                                leadingIcon = { Icon(Icons.Filled.Event, null, Modifier.size(18.dp)) },
                                colors = InputChipDefaults.inputChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                            )
                        }
                    }
                    items(QuickDate.entries.filter { it != QuickDate.ANY }, key = { it.name }) { q ->
                        val selected = quick == q && typedRangeLabel == null
                        FilterChip(
                            selected = selected,
                            onClick = { onQuick(q) },
                            label = { Text(if (q == QuickDate.CUSTOM && quick == q && customLabel != null) customLabel else q.label) },
                            leadingIcon = if (selected) ({ Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }) else null,
                            colors = chipColors,
                            border = searchChipBorder(selected),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TypeFilter.entries, key = { it.name }) { t ->
                        val selected = t in types
                        FilterChip(
                            selected = selected,
                            onClick = { onToggleType(t) },
                            label = { Text(t.label) },
                            leadingIcon = if (selected) ({ Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }) else null,
                            colors = chipColors,
                            border = searchChipBorder(selected),
                        )
                    }
                }
                if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        },
    ) { padding ->
        when {
            queryEmpty -> SearchHint(
                Icons.Filled.Search, "Search by name or date",
                "Try a name, a date like 31/10/2026 or Oct 2026, or words like today and photo 2025.",
                Modifier.padding(padding),
                examples = listOf("invoice", "today", "Oct 2026"),
                onExample = onText,
            )
            results.isEmpty() && !searching -> SearchHint(
                Icons.Filled.SearchOff, "No files found", "Check the spelling, or clear a filter chip.", Modifier.padding(padding),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "count") {
                    Text(
                        "${results.size} result" + (if (results.size == 1) "" else "s") + if (searching) "…" else "",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                items(results, key = { it.path }, contentType = { it.kind }) { item ->
                    FileRow(
                        item = item, selected = false, selectionMode = false,
                        onClick = { onOpen(item) }, onLongClick = {},
                        extraLine = StoragePaths.relative(item.parentPath) + " · " + formatDate(item.modified),
                    )
                }
            }
        }
    }
}

/** Unselected: elevated fill + readable label; selected: filled accent with white label. */
@Composable
private fun searchChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    labelColor = MaterialTheme.colorScheme.onSurface,
    iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
)

@Composable
private fun searchChipBorder(selected: Boolean) = FilterChipDefaults.filterChipBorder(
    enabled = true, selected = selected,
    borderColor = MaterialTheme.colorScheme.outlineVariant,
    selectedBorderColor = Color.Transparent,
    borderWidth = 1.dp, selectedBorderWidth = 0.dp,
)

/** Empty/no-result state, centred in the space left above the keyboard. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchHint(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    examples: List<String> = emptyList(),
    onExample: (String) -> Unit = {},
) {
    Box(modifier.fillMaxSize().imePadding().padding(horizontal = 32.dp), contentAlignment = BiasAlignment(0f, -0.15f)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 420.dp)) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (examples.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    examples.forEach { ex ->
                        SuggestionChip(
                            onClick = { onExample(ex) },
                            label = { Text(ex) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                labelColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                }
            }
        }
    }
}
