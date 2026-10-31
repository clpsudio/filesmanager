package com.clpstudio.filesmanager.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.clpstudio.filesmanager.data.SafAccess

/**
 * Item 1 (F11): Android 8–10 only lets an app write to an SD card after the person picks the card once in the
 * system folder picker. Shown the first time a copy, move, new folder, rename or delete touches the card.
 */
@Composable
fun SafAccessDialog() {
    val context = LocalContext.current
    val root by SafAccess.needed.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val r = SafAccess.needed.value ?: return@rememberLauncherForActivityResult
        val msg = SafAccess.onPicked(context, r, res.data?.data)
        Toast.makeText(context, msg ?: "SD card allowed. Try that again.", Toast.LENGTH_LONG).show()
    }
    val r = root ?: return
    SafAccessDialogContent(
        onAllow = {
            com.clpstudio.filesmanager.security.AppLock.allowExternalScreen()
            runCatching { picker.launch(SafAccess.requestIntent(context, r)) }
                .onFailure { Toast.makeText(context, "The system folder picker isn't available", Toast.LENGTH_LONG).show(); SafAccess.dismiss() }
        },
        onDismiss = { SafAccess.dismiss() },
    )
}

@Composable
fun SafAccessDialogContent(onAllow: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.SdCard, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Allow access to the SD card") },
        text = {
            Text(
                "On this Android version, Files needs your OK once to change files on the SD card.\n\n" +
                    "Next, Android shows the SD card: tap \"Use this folder\" (or \"Select\") at its top level, then Allow.",
            )
        },
        confirmButton = { Button(onClick = onAllow) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
