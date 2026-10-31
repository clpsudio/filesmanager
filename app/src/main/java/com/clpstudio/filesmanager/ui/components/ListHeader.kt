package com.clpstudio.filesmanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.FileView
import com.clpstudio.filesmanager.data.SortBy
import com.clpstudio.filesmanager.data.TypeChip

fun FileView.icon() = when (this) {
    FileView.GRID -> Icons.Filled.GridView
    FileView.LARGE -> Icons.Filled.Dashboard
    FileView.LIST -> Icons.AutoMirrored.Filled.ViewList
}

private val sortLabels = mapOf(SortBy.NAME to "Name", SortBy.DATE to "Date", SortBy.SIZE to "Size", SortBy.TYPE to "Type")

/** Type chips with counts; the chosen one is filled with the accent blue. */
@Composable
fun TypeChipsRow(chips: List<Pair<TypeChip, Int>>, chosen: String, onChoose: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        items(chips, key = { it.first.id }) { (chip, n) ->
            val on = chip.id == chosen
            FilterChip(
                selected = on,
                onClick = { onChoose(chip.id) },
                label = { Text(if (chip.id == "ALL") "${chip.label} $n" else "${chip.label} $n", fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = Color.White,
                ),
                border = if (on) null else FilterChipDefaults.filterChipBorder(enabled = true, selected = false),
            )
        }
    }
}

/**
 * "128 files · 3.4 GB" on the left; "File view ▾" and the sort ("Date ↓") on the right.
 */
@Composable
fun ViewSortRow(
    summary: String,
    view: FileView,
    onView: (FileView) -> Unit,
    sortBy: SortBy,
    ascending: Boolean,
    onSort: (SortBy, Boolean) -> Unit,
    extraMenu: @Composable (() -> Unit)? = null,
) {
    var viewMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(summary, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
        Box {
            TextButton(onClick = { viewMenu = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(view.icon(), null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(view.label)
                Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp))
            }
            DropdownMenu(viewMenu, { viewMenu = false }) {
                FileView.entries.forEach { v ->
                    DropdownMenuItem(
                        text = { Text(v.label) }, leadingIcon = { Icon(v.icon(), null) },
                        trailingIcon = { if (v == view) Icon(Icons.Filled.Check, null) },
                        onClick = { viewMenu = false; onView(v) },
                    )
                }
            }
        }
        Box {
            TextButton(onClick = { sortMenu = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(sortLabels.getValue(sortBy))
                Icon(if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward, null, Modifier.size(16.dp))
            }
            DropdownMenu(sortMenu, { sortMenu = false }) {
                SortBy.entries.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(sortLabels.getValue(s)) },
                        trailingIcon = { if (s == sortBy) Icon(if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward, null) },
                        onClick = {
                            sortMenu = false
                            // Tapping the current sort flips the direction.
                            onSort(s, if (s == sortBy) !ascending else s == SortBy.NAME || s == SortBy.TYPE)
                        },
                    )
                }
                if (extraMenu != null) { HorizontalDivider(); extraMenu() }
            }
        }
    }
}
