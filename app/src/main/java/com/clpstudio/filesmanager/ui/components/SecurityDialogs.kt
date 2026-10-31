package com.clpstudio.filesmanager.ui.components

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.StoragePaths
import com.clpstudio.filesmanager.ui.glass.DialogGlassEffect
import com.clpstudio.filesmanager.ui.glass.dialogContainerColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

const val MIN_PASSWORD_LENGTH = 4

/** A password field with a show/hide eye. */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    visible: Boolean,
    onToggleVisible: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        leadingIcon = { Icon(Icons.Filled.Lock, null) },
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (visible) "Hide password" else "Show password")
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/** Checks a new password typed twice. Returns an error message, or null when it's fine. */
fun newPasswordError(password: String, repeat: String): String? = when {
    password.length < MIN_PASSWORD_LENGTH -> "At least $MIN_PASSWORD_LENGTH characters"
    password != repeat -> "The passwords don't match"
    else -> null
}

/** Compress / Compress with password: the ZIP name, and the password twice when locking. */
@Composable
fun CompressDialog(
    defaultName: String,
    withPassword: Boolean,
    onConfirm: (name: String, password: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val form = rememberCompressForm(defaultName)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        icon = { Icon(Icons.Filled.Lock.takeIf { withPassword } ?: Icons.Filled.Folder, null) },
        title = { Text(if (withPassword) "Locked zip" else "Compress") },
        text = {
            DialogGlassEffect()
            CompressFields(form, withPassword)
        },
        confirmButton = {
            TextButton(onClick = { form.submit(withPassword)?.let { (n, p) -> onConfirm(n, p) } }) { Text(if (withPassword) "Lock & compress" else "Compress") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

class CompressForm(defaultName: String) {
    var name by mutableStateOf(defaultName)
    var pass by mutableStateOf("")
    var repeat by mutableStateOf("")
    var visible by mutableStateOf(false)
    var tried by mutableStateOf(false)

    /** Validates; returns (zip name, password or null) when everything is fine. */
    fun submit(withPassword: Boolean): Pair<String, String?>? {
        tried = true
        val error = if (withPassword) newPasswordError(pass, repeat) else null
        val nameError = name.trim().removeSuffix(".zip").isBlank() || name.contains('/')
        if (error != null || nameError) return null
        val n = name.trim().let { if (it.endsWith(".zip", ignoreCase = true)) it else "$it.zip" }
        return n to if (withPassword) pass else null
    }
}

@Composable
fun rememberCompressForm(defaultName: String) = remember { CompressForm(defaultName) }

@Composable
fun CompressFields(form: CompressForm, withPassword: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = form.name, onValueChange = { form.name = it }, label = { Text("Zip name") }, singleLine = true,
            isError = form.tried && form.name.trim().removeSuffix(".zip").isBlank(), modifier = Modifier.fillMaxWidth(),
        )
        if (withPassword) {
            Text("Protected with AES-256. Without the password nobody can open the files inside.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PasswordField(form.pass, { form.pass = it }, "Password", visible = form.visible, onToggleVisible = { form.visible = !form.visible },
                error = if (form.tried && form.pass.length < MIN_PASSWORD_LENGTH) "At least $MIN_PASSWORD_LENGTH characters" else null)
            PasswordField(form.repeat, { form.repeat = it }, "Repeat password", visible = form.visible, onToggleVisible = { form.visible = !form.visible },
                error = if (form.tried && form.pass.length >= MIN_PASSWORD_LENGTH && form.pass != form.repeat) "The passwords don't match" else null)
        }
    }
}

/** Asks for the password of a locked ZIP or PDF. */
@Composable
fun EnterPasswordDialog(
    fileName: String,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Password needed",
    confirm: String = "Open",
    checking: Boolean = false,
    secondary: String? = null,
    onSecondary: (String) -> Unit = {},
) {
    var pass by remember(error) { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        containerColor = dialogContainerColor(),
        icon = { Icon(Icons.Filled.Lock, null) },
        title = { Text(title) },
        text = {
            DialogGlassEffect()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("\"$fileName\" is protected. Enter its password.", maxLines = 3, overflow = TextOverflow.Ellipsis)
                PasswordField(pass, { pass = it }, "Password", visible = visible, onToggleVisible = { visible = !visible }, error = if (checking) null else error)
                if (checking) CheckingPassword()
            }
        },
        confirmButton = {
            Row {
                if (secondary != null) TextButton(enabled = pass.isNotEmpty() && !checking, onClick = { onSecondary(pass) }) { Text(secondary) }
                TextButton(enabled = pass.isNotEmpty() && !checking, onClick = { onConfirm(pass) }) { Text(confirm) }
            }
        },
        dismissButton = { TextButton(enabled = !checking, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Checking password…" with an indeterminate bar, while the check runs in the background. */
@Composable
fun CheckingPassword() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Checking password…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** Save as a copy or replace the original (PDF password actions). */
@Composable
private fun SaveModeChooser(copyName: String, replace: Boolean, onReplace: (Boolean) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable { onReplace(false) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !replace, onClick = { onReplace(false) })
            Text("Save as a copy: $copyName", style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.fillMaxWidth().clickable { onReplace(true) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = replace, onClick = { onReplace(true) })
            Text("Replace the original", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** "Add password to PDF": the password twice (with the eye), copy or replace. The owner password is the same password. */
@Composable
fun PdfLockDialog(
    fileName: String,
    onConfirm: (password: String, replace: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var pass by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var replace by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    val error = newPasswordError(pass, repeat)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        icon = { Icon(Icons.Filled.Lock, null) },
        title = { Text("Add password to PDF") },
        text = {
            DialogGlassEffect()
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("AES-256. The password is needed to open the PDF.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PasswordField(pass, { pass = it }, "Password to open", visible = visible, onToggleVisible = { visible = !visible },
                    error = if (tried && pass.length < MIN_PASSWORD_LENGTH) "At least $MIN_PASSWORD_LENGTH characters" else null)
                PasswordField(repeat, { repeat = it }, "Repeat password", visible = visible, onToggleVisible = { visible = !visible },
                    error = if (tried && pass.length >= MIN_PASSWORD_LENGTH && pass != repeat) "The passwords don't match" else null)
                SaveModeChooser(File(fileName).nameWithoutExtension + "_locked.pdf", replace) { replace = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { tried = true; if (error == null) onConfirm(pass, replace) }) { Text("Add password") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Remove password": the current password, copy or replace. */
@Composable
fun PdfUnlockDialog(
    fileName: String,
    error: String?,
    onConfirm: (password: String, replace: Boolean) -> Unit,
    onDismiss: () -> Unit,
    checking: Boolean = false,
) {
    var pass by remember(error) { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var replace by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        icon = { Icon(Icons.Filled.Lock, null) },
        title = { Text("Remove password") },
        text = {
            DialogGlassEffect()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PasswordField(pass, { pass = it }, "Current password", visible = visible, onToggleVisible = { visible = !visible }, error = if (checking) null else error)
                SaveModeChooser(File(fileName).nameWithoutExtension.removeSuffix("_locked") + "_unlocked.pdf", replace) { replace = it }
                if (checking) CheckingPassword()
            }
        },
        confirmButton = { TextButton(enabled = pass.isNotEmpty() && !checking, onClick = { onConfirm(pass, replace) }) { Text("Remove password") } },
        dismissButton = { TextButton(enabled = !checking, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picks a destination folder ("Extract to..."). */
@Composable
fun FolderPickerDialog(
    start: File,
    title: String,
    confirm: String,
    onPick: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    var dir by remember { mutableStateOf(start) }
    val folders by produceState<List<File>>(emptyList(), dir) {
        value = withContext(Dispatchers.IO) {
            dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().sortedBy { it.name.lowercase() }
        }
    }
    val roots = remember { StoragePaths.volumes(com.clpstudio.filesmanager.Graph.app).map { it.path } }
    val atRoot = dir.absolutePath in roots
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogContainerColor(),
        title = { Text(title) },
        text = {
            DialogGlassEffect()
            Column {
                Text(StoragePaths.relative(dir.absolutePath), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.StartEllipsis)
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    if (!atRoot && dir.parentFile != null) item {
                        Row(Modifier.fillMaxWidth().clickable { dir = dir.parentFile!! }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.ArrowUpward, null)
                            Text("  Up", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    if (atRoot && roots.size > 1) items(roots.filter { it != dir.absolutePath }) { r ->
                        Row(Modifier.fillMaxWidth().clickable { dir = File(r) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            Text("  " + StoragePaths.relative(r), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    items(folders, key = { it.absolutePath }) { f ->
                        Row(Modifier.fillMaxWidth().clickable { dir = f }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            Text("  " + f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(dir) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

