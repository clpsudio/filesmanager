package com.clpstudio.filesmanager.ui.security

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.security.AppLock
import com.clpstudio.filesmanager.security.AskAfter
import com.clpstudio.filesmanager.security.CredentialType
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.glass.glassPanel
import kotlinx.coroutines.launch

@Composable
internal fun SecCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(22.dp)).then(modifier).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
internal fun SecHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp, top = 8.dp))
}

@Composable
internal fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Asks for the current PIN/password; [check] returns null when right, else the message. */
@Composable
fun VerifyDialog(
    title: String,
    type: CredentialType,
    confirm: String,
    check: suspend (String) -> String?,
    onOk: (String) -> Unit,
    onDismiss: () -> Unit,
    text: String? = null,
    destructive: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun go() {
        if (busy || value.isEmpty()) return
        busy = true
        scope.launch {
            val r = check(value)
            busy = false
            if (r == null) onOk(value) else { error = r; value = "" }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium)
                SecretField(value, { value = it; error = null }, type, if (type == CredentialType.PIN) "Current PIN" else "Current password", error = error, onIme = { go() }, enabled = !busy)
            }
        },
        confirmButton = {
            TextButton(onClick = { go() }, enabled = !busy && value.isNotEmpty()) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

private suspend fun appLockCheck(s: String): String? = when (val r = AppLock.check(s)) {
    AppLock.Check.Ok -> null
    is AppLock.Check.Wrong -> r.message
}

/** Settings → App lock. */
@Composable
fun AppLockSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by AppLock.enabledFlow.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var askAfter by remember { mutableStateOf(AppLock.askAfter) }
    var bio by remember { mutableStateOf(AppLock.biometric) }
    var verifyFor by remember { mutableStateOf<String?>(null) } // "change" | "off"
    var changing by remember { mutableStateOf(false) }
    val bioAvailable = remember { AppLock.biometricAvailable(context) }
    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()

    GlassScaffold(topBar = { GlassTopBar("App lock", onBack = onBack) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (!enabled || changing) {
                item {
                    SecCard {
                        Text(if (changing) "New PIN or password" else "Turn on app lock", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Files will ask for it every time it opens, and when you come back after the time below. " +
                                "It's separate from the vault's own PIN.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CredentialSetupForm(
                            confirmLabel = if (changing) "Save" else "Turn on",
                            busy = busy,
                            initialType = if (changing) AppLock.type else CredentialType.PIN,
                            onSubmit = { type, secret ->
                                busy = true
                                scope.launch {
                                    AppLock.set(type, secret)
                                    busy = false
                                    toast(if (changing) "Saved" else "App lock is on")
                                    changing = false
                                }
                            },
                        )
                        if (changing) TextButton(onClick = { changing = false }) { Text("Cancel") }
                    }
                }
            } else {
                item {
                    SecCard {
                        Text("App lock is on", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Unlock with your ${if (AppLock.type == CredentialType.PIN) "PIN" else "password"}" + if (bio && bioAvailable) " or fingerprint." else ".",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { verifyFor = "change" }) { Text("Change PIN or password") }
                            Button(onClick = { verifyFor = "off" }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Text("Turn off") }
                        }
                    }
                }
            }
            item { SecHeader("Ask after") }
            item {
                SecCard {
                    Text("How long Files can stay in the background before it asks again. It always asks when the app starts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AskAfter.entries.forEach { a ->
                        Choice(a.label, askAfter == a) { askAfter = a; AppLock.askAfter = a }
                    }
                }
            }
            item { SecHeader("Fingerprint") }
            item {
                SecCard(if (bioAvailable && enabled) Modifier.clickable { bio = !bio; AppLock.biometric = bio } else Modifier) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Unlock with fingerprint", style = MaterialTheme.typography.titleSmall)
                            Text(
                                when {
                                    android.os.Build.VERSION.SDK_INT < 29 -> "Needs Android 10 or newer. Use your PIN or password."
                                    !bioAvailable -> "No fingerprint is set up on this phone (Settings → Security)."
                                    !enabled -> "Turn on app lock first."
                                    else -> "Your PIN or password always works too."
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = bio && bioAvailable && enabled, onCheckedChange = { bio = it; AppLock.biometric = it }, enabled = bioAvailable && enabled)
                    }
                }
            }
        }
    }

    verifyFor?.let { what ->
        VerifyDialog(
            title = if (what == "off") "Turn off app lock?" else "Change PIN or password",
            type = AppLock.type,
            confirm = if (what == "off") "Turn off" else "Next",
            destructive = what == "off",
            check = ::appLockCheck,
            onOk = {
                verifyFor = null
                if (what == "off") { AppLock.disable(); bio = false; toast("App lock is off") } else changing = true
            },
            onDismiss = { verifyFor = null },
        )
    }
}

/** Settings → Security: the App lock and Vault rows (each opens its own screen). */
@Composable
fun SecuritySettingsRows(onAppLock: () -> Unit, onVaultSettings: () -> Unit) {
    val lockOn by AppLock.enabledFlow.collectAsState()
    val vaultState by com.clpstudio.filesmanager.vault.Vault.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NavRow(androidx.compose.material.icons.Icons.Filled.Lock, "App lock",
            if (lockOn) "On · ${if (AppLock.type == CredentialType.PIN) "PIN" else "password"} · ${AppLock.askAfter.label.lowercase()}" else "Off · PIN (4–10 digits) or password (8–72 characters)",
            onAppLock)
        NavRow(androidx.compose.material.icons.Icons.Filled.Shield, "Vault settings",
            when (vaultState) {
                com.clpstudio.filesmanager.vault.VaultState.NOT_SET_UP -> "Not set up · encrypted private vault with its own PIN or password"
                else -> "PIN or password, auto-lock, delete vault"
            },
            onVaultSettings)
    }
}

@Composable
private fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, onClick: () -> Unit) {
    SecCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
