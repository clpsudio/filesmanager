package com.clpstudio.filesmanager.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clpstudio.filesmanager.BuildConfig
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.Category
import com.clpstudio.filesmanager.data.Diagnostics
import com.clpstudio.filesmanager.data.StoragePaths
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → About → Diagnostics: what the app sees on this phone, plus the local error log. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf(Diagnostics.snapshot().reversed()) }
    var checking by remember { mutableStateOf(false) }
    val info = remember { deviceInfo(context) }
    GlassScaffold(
        topBar = {
            GlassTopBar(
                "Diagnostics", onBack = onBack,
                actions = {
                    IconButton(onClick = {
                        val text = (info + "" + Diagnostics.snapshot()).joinToString("\n")
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Files diagnostics", text))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Filled.ContentCopy, "Copy") }
                    IconButton(onClick = { Diagnostics.clear(); log = emptyList() }) { Icon(Icons.Filled.DeleteSweep, "Clear log") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(info) { Text(it, style = MaterialTheme.typography.bodySmall) }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Button(enabled = !checking, onClick = {
                        checking = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val filter = Graph.hiddenFilter()
                                for (cat in Category.entries) {
                                    val t0 = System.currentTimeMillis()
                                    val r = runCatching { Graph.media.category(cat, filter) }
                                    Diagnostics.log("check", "${cat.title}: " + r.fold({ "${it.size} files" }, { "failed: ${it.message}" }) + " in ${System.currentTimeMillis() - t0} ms")
                                }
                            }
                            log = Diagnostics.snapshot().reversed()
                            checking = false
                        }
                    }) { Text(if (checking) "Checking..." else "Check all categories") }
                }
            }
            item { Text("Log (newest first)", style = MaterialTheme.typography.titleSmall) }
            if (log.isEmpty()) item { Text("No entries", style = MaterialTheme.typography.bodySmall) }
            items(log) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}

private fun deviceInfo(context: Context): List<String> {
    val access = if (Build.VERSION.SDK_INT >= 30) "All files access: " + if (Environment.isExternalStorageManager()) "allowed" else "NOT allowed"
    else "Storage permission (Android 8-10)"
    val volumes = if (Build.VERSION.SDK_INT >= 29) runCatching { MediaStore.getExternalVolumeNames(context).joinToString() }.getOrDefault("?") else "external"
    return listOf(
        "Files ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        access,
        "MediaStore volumes: $volumes",
        "Storage: " + StoragePaths.volumes(context).joinToString { "${it.label} ${it.path}" },
    )
}
