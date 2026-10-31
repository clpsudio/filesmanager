package com.clpstudio.filesmanager.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.clpstudio.filesmanager.data.FileEvents
import com.clpstudio.filesmanager.data.FileItem

/**
 * [items] without files deleted, moved or renamed through the app since they were loaded.
 * Re-filters the moment a deletion happens anywhere (viewer, info sheet, selection, another screen),
 * so a removed file never lingers while a reload is still running.
 */
@Composable
fun rememberLiveFiles(items: List<FileItem>?): List<FileItem>? {
    val version by FileEvents.version.collectAsState()
    return remember(items, version) { items?.let(FileEvents::filter) }
}
