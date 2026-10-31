package com.clpstudio.filesmanager.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.clpstudio.filesmanager.data.Category
import com.clpstudio.filesmanager.data.ManageStorageRepository
import com.clpstudio.filesmanager.data.StorageKind
import com.clpstudio.filesmanager.data.StorageMath
import com.clpstudio.filesmanager.ui.Opener
import com.clpstudio.filesmanager.ui.components.formatSize
import com.clpstudio.filesmanager.ui.components.rememberLiveFiles

/** One "Manage storage" row as a list on its volume, titled "132 videos (6.55 GB)". */
@Composable
fun StorageKindScreen(kind: StorageKind, volumePath: String, opener: Opener, onBack: () -> Unit, onPaste: () -> Unit) {
    val state by ManageStorageRepository.state().collectAsState()
    LaunchedEffect(Unit) { ManageStorageRepository.ensureLoaded() }
    val report = state.value?.volumes?.firstOrNull { it.volume.path == volumePath }
    val items = rememberLiveFiles(report?.byKind?.get(kind))
    val title = items?.let { StorageMath.listTitle(kind, it.size, formatSize(it.sumOf { f -> f.size })) } ?: kind.label
    FileListScreen(
        title = title,
        subtitle = report?.volume?.label,
        items = items,
        grid = kind == StorageKind.VIDEOS || kind == StorageKind.IMAGES,
        opener = opener,
        onBack = onBack,
        onSearch = null,
        onPaste = onPaste,
        category = when (kind) {
            StorageKind.VIDEOS -> Category.VIDEOS
            StorageKind.IMAGES -> Category.PHOTOS
            StorageKind.AUDIO -> Category.AUDIO
            StorageKind.DOCUMENTS -> Category.DOCUMENTS
            else -> null
        },
        loading = state.loading && items == null,
    )
}
