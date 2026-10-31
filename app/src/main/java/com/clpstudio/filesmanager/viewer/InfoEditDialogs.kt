package com.clpstudio.filesmanager.viewer

import android.content.Context
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.FileEvents
import com.clpstudio.filesmanager.data.FileOpException
import com.clpstudio.filesmanager.data.FileOps
import com.clpstudio.filesmanager.data.InfoEdits
import com.clpstudio.filesmanager.data.StoragePaths
import com.clpstudio.filesmanager.ui.AppState
import com.clpstudio.filesmanager.ui.components.FolderPickerDialog
import com.clpstudio.filesmanager.ui.components.TextInputDialog
import com.clpstudio.filesmanager.ui.glass.DialogGlassEffect
import com.clpstudio.filesmanager.ui.glass.dialogContainerColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The info sheet rows that can be edited (tap the row or its pencil). */
enum class InfoEdit { NAME, DATE, FOLDER }

/** Which edits [path] allows: a real file or folder on storage, not a storage root. */
fun editableFor(path: String?): Set<InfoEdit> {
    val f = path?.let(::File) ?: return emptySet()
    if (!f.exists() || f.parentFile == null) return emptySet()
    val roots = runCatching { StoragePaths.volumes(Graph.app).map { it.path } }.getOrDefault(emptyList())
    if (f.absolutePath in roots) return emptySet()
    // Hidden files (dot-files, dot-folders, .nomedia media, the Hide folder): no rename, date or move — only Delete permanently.
    if (com.clpstudio.filesmanager.data.DeleteRules.isHidden(f.absolutePath, roots + StoragePaths.primary)) return emptySet()
    return setOf(InfoEdit.NAME, InfoEdit.DATE, InfoEdit.FOLDER)
}

@Stable
class InfoEditState {
    var open by mutableStateOf<InfoEdit?>(null)
    /** The picked day (midnight UTC, as the date picker gives it) while the time dialog is up. */
    var pickedDay by mutableStateOf<Long?>(null)
}

private fun toast(context: Context, text: String) =
    Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()

private fun isJpeg(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "jpe")

/**
 * Rename / date & time / folder dialogs for the info sheet. [onChanged] gets the file's new
 * location (or the same file after a date change) so the sheet can show the new details.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoEditDialogs(state: InfoEditState, path: String?, onChanged: (File) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = path?.let(::File) ?: return
    when (state.open) {
        InfoEdit.NAME -> com.clpstudio.filesmanager.ui.components.RenameFileDialog(
            name = file.name,
            isDir = file.isDirectory,
            onRename = { typed -> renameFile(context, file, typed, onChanged) },
            onDismiss = { state.open = null },
        )
        InfoEdit.DATE -> {
            val day = state.pickedDay
            if (day == null) {
                val picker = rememberDatePickerState(initialSelectedDateMillis = InfoEdits.pickerDateOf(file.lastModified()))
                DatePickerDialog(
                    onDismissRequest = { state.open = null },
                    colors = DatePickerDefaults.colors(containerColor = dialogContainerColor()),
                    confirmButton = {
                        TextButton(enabled = picker.selectedDateMillis != null, onClick = { state.pickedDay = picker.selectedDateMillis }) { Text("Next") }
                    },
                    dismissButton = { TextButton(onClick = { state.open = null }) { Text("Cancel") } },
                ) {
                    DatePicker(state = picker, title = { Text("Date", Modifier.padding(start = 24.dp, top = 16.dp)) })
                }
            } else {
                val (h, m) = remember(file) { InfoEdits.timeOf(file.lastModified()) }
                val timeState = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = DateFormat.is24HourFormat(context))
                val canExif = !file.isDirectory && isJpeg(file.name)
                var exif by remember { mutableStateOf(canExif) }
                val close = { state.pickedDay = null; state.open = null }
                AlertDialog(
                    onDismissRequest = close,
                    containerColor = dialogContainerColor(),
                    title = { Text("Time") },
                    text = {
                        DialogGlassEffect()
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TimePicker(state = timeState)
                            if (canExif) Row(
                                Modifier.fillMaxWidth().clickable { exif = !exif },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = exif, onCheckedChange = { exif = it })
                                Text("Also change the photo's taken date", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val ms = InfoEdits.compose(day, timeState.hour, timeState.minute)
                            val withExif = canExif && exif
                            close()
                            scope.launch {
                                val msg = withContext(Dispatchers.IO) { applyDate(context, file, ms, withExif) }
                                toast(context, msg)
                                onChanged(file)
                            }
                        }) { Text("Save") }
                    },
                    dismissButton = { TextButton(onClick = { state.pickedDay = null }) { Text("Back") } },
                )
            }
        }
        InfoEdit.FOLDER -> FolderPickerDialog(
            start = file.parentFile ?: File(StoragePaths.primary),
            title = "Move to",
            confirm = "Move here",
            onPick = { dir ->
                state.open = null
                moveFile(context, file, dir, onChanged)
            },
            onDismiss = { state.open = null },
        )
        null -> Unit
    }
}

/** Rename through the app's one rename path (lists, favourites, playlists and the media index follow). */
private fun renameFile(context: Context, file: File, typed: String, onChanged: (File) -> Unit): String? = try {
    val renamed = com.clpstudio.filesmanager.data.RenameOps.rename(context, file, typed)
    if (renamed != file) { AppState.refresh(); onChanged(renamed) }
    null
} catch (e: FileOpException) {
    e.message ?: "Couldn't rename it"
}

/** Sets the file's date (and, for JPEG photos when asked, the EXIF taken date). Returns the toast. */
internal fun applyDate(context: Context, file: File, ms: Long, exif: Boolean): String {
    var exifDone = false
    if (exif) {
        // EXIF first: saving it rewrites the file, which would reset the date set below.
        exifDone = runCatching {
            val e = ExifInterface(file.absolutePath)
            val text = InfoEdits.exifDate(ms)
            val offset = InfoEdits.exifOffset(ms)
            e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, text)
            e.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, text)
            e.setAttribute(ExifInterface.TAG_DATETIME, text)
            e.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
            e.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offset)
            e.setAttribute(ExifInterface.TAG_OFFSET_TIME, offset)
            e.saveAttributes()
            true
        }.getOrDefault(false)
    }
    val dateDone = runCatching { file.setLastModified(ms) }.getOrDefault(false) && InfoEdits.dateApplied(ms, file.lastModified())
    if (dateDone || exifDone) {
        FileOps.scan(context, listOf(file.absolutePath))
        FileEvents.added(listOf(file.absolutePath)) // lists re-sort by the new date
        AppState.refresh()
    }
    return when {
        dateDone && exifDone -> "Date and photo's taken date changed"
        dateDone && exif -> "Date changed. Couldn't change the photo's taken date"
        dateDone -> "Date changed"
        exifDone -> "Photo's taken date changed. Can't change the file date for this file"
        else -> "Can't change the date for this file"
    }
}

/** Moves the file with the app's move operation (progress, "name (1)" on a clash). */
private fun moveFile(context: Context, file: File, dir: File, onChanged: (File) -> Unit) {
    if (file.parentFile?.absolutePath == dir.absolutePath) {
        toast(context, "It's already in this folder")
        return
    }
    if (file.isDirectory && (dir.absolutePath + "/").startsWith(file.absolutePath + "/")) {
        toast(context, "Can't put a folder inside itself")
        return
    }
    AppState.runOperation(context, "Moving") { report ->
        val moved = FileOps.transfer(context, listOf(file), dir, move = true, onProgress = report).first()
        withContext(Dispatchers.Main) { onChanged(moved) }
        val renamed = if (moved.name != file.name) " as ${moved.name}" else ""
        "Moved to ${StoragePaths.relative(dir.absolutePath)}$renamed" to emptyList()
    }
}
