package com.clpstudio.filesmanager.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.FileNames
import com.clpstudio.filesmanager.ui.glass.DialogGlassEffect
import com.clpstudio.filesmanager.ui.glass.dialogContainerColor

/** The rename form's state and rules (kept apart from the dialog window so it can be tested and drawn in place). */
class RenameForm(val name: String, allowExtensionChange: Boolean) {
    val parts = FileNames.split(name)
    val locked = !allowExtensionChange
    var value by mutableStateOf(
        TextFieldValue(if (locked) parts.base else name, TextRange(0, parts.base.length))
    )
    var error by mutableStateOf<String?>(null)
    /** The typed name waiting for "Change the file type?". */
    var confirmTypeChange by mutableStateOf<String?>(null)

    /** The full new name to rename to now, or null (an error to show, or the type-change question first). */
    fun submit(): String? = when {
        value.text.isBlank() -> { error = "Type a name"; null }
        locked -> FileNames.lockedName(value.text, parts.ext)
        FileNames.extensionChanged(name, value.text) -> { confirmTypeChange = value.text.trim(); null }
        else -> value.text.trim()
    }

    /** "Keep .jpg" on the question: the typed name with the original type. */
    fun keepType(typed: String): String = FileNames.split(typed).base + parts.ext
}

/** The name field (suffix shows the locked type). */
@Composable
fun RenameFields(form: RenameForm, focus: FocusRequester? = null, onDone: () -> Unit = {}) {
    OutlinedTextField(
        value = form.value,
        onValueChange = { form.value = it; form.error = null },
        singleLine = true,
        isError = form.error != null,
        suffix = if (form.locked) ({ Text(form.parts.ext, color = MaterialTheme.colorScheme.onSurfaceVariant) }) else null,
        supportingText = {
            when {
                form.error != null -> Text(form.error!!)
                form.locked -> Text("The type (${form.parts.ext}) stays. Settings \u203a Allow changing file extension to change it.")
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier).testTag("renameField"),
    )
}

/**
 * Item 45: rename a file without changing its type by accident.
 * Extension locked (default): only the name is edited, the type shows as a fixed suffix ("photo" + ".jpg").
 * Settings "Allow changing file extension" on: the whole name is editable, and a different type asks first.
 * Folders and files with no type: the whole name, as before. [onRename] gets the full new name, returns an error or null.
 */
@Composable
fun RenameFileDialog(
    name: String,
    isDir: Boolean,
    onRename: (String) -> String?,
    onDismiss: () -> Unit,
    allowExtensionChange: Boolean? = null,
) {
    val allow = allowExtensionChange ?: Graph.settings.collectAsState().value.allowExtensionChange
    val parts = remember(name) { FileNames.split(name) }
    if (isDir || parts.ext.isEmpty()) {
        TextInputDialog(title = "Rename", initial = name, confirm = "Rename", onConfirm = onRename, onDismiss = onDismiss, selectBaseName = !isDir)
        return
    }
    val form = remember(name, allow) { RenameForm(name, allow) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun finish(full: String) {
        val err = onRename(full)
        if (err == null) onDismiss() else form.error = err
    }
    val submit = { form.submit()?.let(::finish); Unit }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        title = { Text("Rename") },
        text = {
            DialogGlassEffect()
            RenameFields(form, focus, onDone = submit)
        },
        confirmButton = { TextButton(onClick = submit) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    form.confirmTypeChange?.let { newName ->
        AlertDialog(
            onDismissRequest = { form.confirmTypeChange = null },
            containerColor = dialogContainerColor(),
            title = { Text("Change the file type?") },
            text = { Text(FileNames.changeWarning(name, newName)) },
            confirmButton = { TextButton(onClick = { form.confirmTypeChange = null; finish(newName) }) { Text("Change") } },
            dismissButton = { TextButton(onClick = { form.confirmTypeChange = null; finish(form.keepType(newName)) }) { Text("Keep ${parts.ext}") } },
        )
    }
}
