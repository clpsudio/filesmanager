package com.clpstudio.filesmanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.LocationFilter
import com.clpstudio.filesmanager.data.SdCards
import com.clpstudio.filesmanager.data.VolumeRules
import com.clpstudio.filesmanager.ui.AppState

/** What a list needs to know about SD cards: card roots and whether the location chip shows. */
@Immutable
data class SdCardUiState(val removableRoots: List<String>, val showChips: Boolean)

/** Re-read when a card goes in / out (refresh tick) or "Show SD card" switches. */
@Composable
fun rememberSdCardState(): SdCardUiState {
    val context = LocalContext.current
    val settings by Graph.settings.collectAsState()
    val tick = AppState.refreshTick
    return remember(tick, settings.showSdCard) {
        val all = runCatching { SdCards.all(context) }.getOrDefault(emptyList())
        SdCardUiState(
            removableRoots = all.filter { it.removable }.map { it.path },
            showChips = VolumeRules.showLocationChips(all, settings.showSdCard),
        )
    }
}

/** "All / Phone / SD card" chips above a category list. */
@Composable
fun LocationChips(selected: LocationFilter, onSelect: (LocationFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LocationFilter.entries.forEach { f ->
            FilterChip(
                selected = selected == f,
                onClick = { onSelect(f) },
                label = { Text(f.label) },
                leadingIcon = when (f) {
                    LocationFilter.PHONE -> ({ Icon(Icons.Filled.Smartphone, null, Modifier.padding(0.dp)) })
                    LocationFilter.SD_CARD -> ({ Icon(Icons.Filled.SdCard, null) })
                    LocationFilter.ALL -> null
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}
