package com.clpstudio.filesmanager.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.ui.Opener
import com.clpstudio.filesmanager.ui.components.FileActions
import com.clpstudio.filesmanager.ui.components.MenuRow
import com.clpstudio.filesmanager.ui.components.SelectionState
import java.io.File

/** What a tap / long-press does in the APK files tab (pure, unit tested). */
object ApkSelectionLogic {
    sealed interface TapResult {
        data class Toggle(val path: String) : TapResult
        data class Open(val path: String) : TapResult
    }

    /** Selecting: a tap toggles. Not selecting: a tap opens the APK preview page (Install / Update / Open are there). */
    fun onTap(selecting: Boolean, path: String): TapResult = if (selecting) TapResult.Toggle(path) else TapResult.Open(path)

    /** The selected APKs in list order, dropping paths that are no longer in the list (deleted / moved). */
    fun selectedItems(list: List<FileItem>, selected: Collection<String>): List<FileItem> {
        val set = selected.toHashSet()
        return list.filter { it.path in set }
    }

    /** Paths still present after the list reloaded. */
    fun prune(selected: List<String>, list: List<FileItem>): List<String> {
        val present = list.mapTo(HashSet()) { it.path }
        return selected.filter { it in present }
    }

    /** Select all, or clear when everything is already selected. */
    fun toggleAll(list: List<FileItem>, selected: Collection<String>): List<String> =
        if (list.isNotEmpty() && selected.size >= list.size && list.all { it.path in selected }) emptyList() else list.map { it.path }

    /** APKs only (Install is offered only when every selected file is an .apk). */
    fun allApks(items: List<FileItem>): Boolean = items.isNotEmpty() && items.all { !it.isDir && it.name.endsWith(".apk", ignoreCase = true) }
}

/** Opens the system install prompt for each APK, one after another (Android stacks the prompts). */
fun installAll(context: Context, items: List<FileItem>) {
    items.forEach { Opener.installApk(context, File(it.path)) }
}

/** Top-bar actions while APK files are selected: Select all, Share, Delete, and ⋮ Install / Move / Copy / Compress. */
@Composable
fun ApkSelectionActions(
    context: Context,
    list: List<FileItem>,
    selection: SelectionState,
    actions: FileActions,
) {
    val selected = ApkSelectionLogic.selectedItems(list, selection.paths)
    var menu by remember { mutableStateOf(false) }
    com.clpstudio.filesmanager.ui.components.SelectAllToggle(list.isNotEmpty() && selection.paths.containsAll(list.map { it.path })) { selection.selectAll(ApkSelectionLogic.toggleAll(list, selection.paths)) }
    IconButton(onClick = { actions.share(selected) }, enabled = selected.isNotEmpty()) { Icon(Icons.Filled.Share, "Share") }
    IconButton(onClick = { actions.deleteTargets = selected }, enabled = selected.isNotEmpty()) { Icon(Icons.Filled.Delete, "Delete") }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(menu, { menu = false }) {
            if (ApkSelectionLogic.allApks(selected)) {
                MenuRow(Icons.Filled.InstallMobile, if (selected.size == 1) "Install" else "Install ${selected.size} apps") {
                    menu = false
                    installAll(context, selected)
                    selection.clear()
                }
            }
            MenuRow(Icons.AutoMirrored.Filled.DriveFileMove, "Move to...") { menu = false; actions.move(selected) }
            MenuRow(Icons.Filled.ContentCopy, "Copy to...") { menu = false; actions.copy(selected) }
            MenuRow(Icons.Filled.FolderZip, "Compress") { menu = false; actions.compress(selected, withPassword = false) }
            MenuRow(Icons.Filled.Lock, "Compress with password") { menu = false; actions.compress(selected, withPassword = true) }
            if (selected.size == 1) MenuRow(Icons.Filled.Info, "Details") { menu = false; actions.detailsTarget = selected[0] }
        }
    }
}
