package com.clpstudio.filesmanager.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.clpstudio.filesmanager.data.Favorites
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.SortBy
import com.clpstudio.filesmanager.ui.AppState
import com.clpstudio.filesmanager.ui.Opener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Every favorited file (any type), in the chosen file view. Missing files are skipped. */
@Composable
fun FavoritesScreen(opener: Opener, onBack: () -> Unit, onPaste: () -> Unit) {
    val paths by Favorites.paths.collectAsState()
    val items by produceState<List<FileItem>?>(null, paths, AppState.refreshTick) {
        value = withContext(Dispatchers.IO) { paths.map { File(it) }.filter { it.exists() }.map { FileItem.of(it, countChildren = true) } }
    }
    FileListScreen(
        title = "Favorites",
        subtitle = null,
        items = items,
        grid = true,
        opener = opener,
        onBack = onBack,
        onSearch = null,
        onPaste = onPaste,
        defaultSort = SortBy.DATE,
    )
}
