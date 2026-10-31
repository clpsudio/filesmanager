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
import com.clpstudio.filesmanager.data.FileNames
import com.clpstudio.filesmanager.ui.glass.DialogGlassEffect
import com.clpstudio.filesmanager.ui.glass.dialogContainerColor

/**
 * The rename form's state and rules (kept apart from the dialog window so it can be tested and drawn in place).
 * The file type can't be changed: only the name is edited and the extension is a fixed suffix.
 */
class RenameForm(val name: String) {
    val parts = FileNames.split(name)
    var value by mutableStateOf(TextFieldValue(parts.base, TextRange(0, parts.base.length)))
    var error by mutableStateOf<String?>(null)

    /** The full new name to rename to now (name + the original extension), or null with an error to show. */
    fun submit(): String? = when {
        value.text.isBlank() -> { error = "Type a name"; null }
        else -> FileNames.lockedName(value.text, parts.ext)
    }
}

/** The name field (suffix shows the locked type). */
@Composable
fun RenameFields(form: RenameForm, focus: FocusRequester? = null, onDone: () -> Unit = {}) {
    OutlinedTextField(
        value = form.value,
        onValueChange = { form.value = it; form.error = null },
        singleLine = true,
        isError = form.error != null,
        suffix = { Text(form.parts.ext, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        supportingText = {
            if (form.error != null) Text(form.error!!) else Text("The file type (${form.parts.ext}) can't be changed.")
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier).testTag("renameField"),
    )
}

/**
 * Item 45: rename a file without ever changing its type: only the name is edited, the type shows as a
 * fixed suffix ("photo" + ".jpg"); a typed ".txt" stays part of the name. Folders and files with no type:
 * the whole name, as before. [onRename] gets the full new name, returns an error or null.
 */
@Composable
fun RenameFileDialog(
    name: String,
    isDir: Boolean,
    onRename: (String) -> String?,
    onDismiss: () -> Unit,
) {
    val parts = remember(name) { FileNames.split(name) }
    if (isDir || parts.ext.isEmpty()) {
        TextInputDialog(title = "Rename", initial = name, confirm = "Rename", onConfirm = onRename, onDismiss = onDismiss, selectBaseName = !isDir)
        return
    }
    val form = remember(name) { RenameForm(name) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = {
        form.submit()?.let { full ->
            val err = onRename(full)
            if (err == null) onDismiss() else form.error = err
        }
        Unit
    }
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
}
