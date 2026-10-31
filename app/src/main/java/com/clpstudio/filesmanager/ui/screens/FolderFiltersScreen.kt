package com.clpstudio.filesmanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.Category
import com.clpstudio.filesmanager.data.ChipConfig
import com.clpstudio.filesmanager.data.TypeFilters
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.glass.glassPanel
import kotlinx.coroutines.launch

/** The categories with chips, in tab order. */
val FolderFilterTabs = listOf(Category.MEDIA, Category.PHOTOS, Category.VIDEOS, Category.AUDIO, Category.DOCUMENTS, Category.RECENT, Category.DOWNLOADS)

private fun tabTitle(c: Category) = if (c == Category.PHOTOS) "Images" else c.title

/** Settings → Folder filters: which chips show above each list, and in what order. */
@Composable
fun FolderFiltersScreen(onBack: () -> Unit) {
    val settings by Graph.settings.collectAsState()
    val scope = rememberCoroutineScope()
    FolderFiltersContent(
        configs = settings.chipConfig,
        onBack = onBack,
        onChange = { cat, cfg -> scope.launch { Graph.settingsRepo.setChipConfig(cat.name, cfg) } },
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FolderFiltersContent(configs: Map<String, ChipConfig>, onBack: () -> Unit, onChange: (Category, ChipConfig?) -> Unit, startTab: Int = 0) {
    val pager = rememberPagerState(initialPage = startTab) { FolderFilterTabs.size }
    val scope = rememberCoroutineScope()
    val current = FolderFilterTabs[pager.currentPage]
    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = "Folder filters",
                subtitle = "Chips above your lists · \"All\" always stays",
                onBack = onBack,
                actions = { TextButton(onClick = { onChange(current, null) }) { Text("Reset") } },
                below = {
                    PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage, containerColor = Color.Transparent, edgePadding = 12.dp) {
                        FolderFilterTabs.forEachIndexed { i, c ->
                            Tab(selected = pager.currentPage == i, onClick = { scope.launch { pager.animateScrollToPage(i) } }, text = { Text(tabTitle(c)) })
                        }
                    }
                },
            )
        },
    ) { padding ->
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val cat = FolderFilterTabs[page]
            val cfg = configs[cat.name]
            val chips = TypeFilters.chipsFor(cat).filter { it.id != TypeFilters.ALL }
            // Shown order: the user's order (hidden ones included, so they can be turned back on).
            val ordered = ChipConfig.apply(TypeFilters.chipsFor(cat), cfg?.copy(hidden = emptySet())).filter { it.id != TypeFilters.ALL }
            val order = ordered.map { it.id }
            val hidden = cfg?.hidden.orEmpty()
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(ordered, key = { _, c -> c.id }) { i, chip ->
                    val on = chip.id !in hidden
                    Row(
                        Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(18.dp)).padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(chip.label, style = MaterialTheme.typography.titleSmall)
                            Text(if (chip.id.startsWith("APP_") || chip.id == "CAMERA" || chip.id == "SCREENSHOTS") "Folder" else "File type",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onChange(cat, ChipConfig(ChipConfig.move(order, chip.id, -1), hidden)) }, enabled = i > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, "Move ${chip.label} up")
                        }
                        IconButton(onClick = { onChange(cat, ChipConfig(ChipConfig.move(order, chip.id, +1), hidden)) }, enabled = i < ordered.size - 1) {
                            Icon(Icons.Filled.KeyboardArrowDown, "Move ${chip.label} down")
                        }
                        Switch(checked = on, onCheckedChange = { show ->
                            onChange(cat, ChipConfig(order, if (show) hidden - chip.id else hidden + chip.id))
                        })
                    }
                }
                if (chips.isEmpty()) item { Text("No filters for this list.") }
            }
        }
    }
}
