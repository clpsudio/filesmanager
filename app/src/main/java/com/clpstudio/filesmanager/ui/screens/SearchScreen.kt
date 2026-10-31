package com.clpstudio.filesmanager.ui.screens

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
import com.clpstudio.filesmanager.ui.components.EmptyState
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

    GlassScaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().glassBar().statusBarsPadding().padding(bottom = 6.dp)) {
                androidx.compose.foundation.layout.Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    SearchField(
                        value = text, onValueChange = { text = it },
                        placeholder = if (root != null) "Search in " + root.substringAfterLast('/') else "Name or date: 31/10/2026, Oct 2026",
                        autoFocus = true, modifier = Modifier.weight(1f),
                    )
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (parsed.range != null) {
                        item(key = "typed") {
                            InputChip(selected = true, onClick = {}, label = { Text(parsed.range.label()) }, leadingIcon = { Icon(Icons.Filled.Event, null) })
                        }
                    }
                    items(QuickDate.entries.filter { it != QuickDate.ANY }, key = { it.name }) { q ->
                        FilterChip(
                            selected = quick == q && parsed.range == null,
                            onClick = {
                                if (q == QuickDate.CUSTOM) showPicker = true else quick = if (quick == q) QuickDate.ANY else q
                            },
                            label = { Text(if (q == QuickDate.CUSTOM && quick == q && custom != null) custom!!.label() else q.label) },
                        )
                    }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TypeFilter.entries, key = { it.name }) { t ->
                        FilterChip(
                            selected = t in types,
                            onClick = { types = if (t in types) types - t else types + t },
                            label = { Text(t.label) },
                        )
                    }
                }
                if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        },
    ) { padding ->
        when {
            query.isEmpty -> EmptyState(Icons.Filled.Event, "Search by name or date", "Try “invoice”, “31/10/2026”, “Oct 2026”, “today” or “photo 2025”. Tap a chip for quick dates or file types.", Modifier.padding(padding))
            results.isEmpty() && !searching -> EmptyState(Icons.Filled.SearchOff, "No files found", null, Modifier.padding(padding))
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
                        onClick = { opener.open(item, results) }, onLongClick = {},
                        extraLine = StoragePaths.relative(item.parentPath) + " · " + formatDate(item.modified),
                    )
                }
            }
        }
    }

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
