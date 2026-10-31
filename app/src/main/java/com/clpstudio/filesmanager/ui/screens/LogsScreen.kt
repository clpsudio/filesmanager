package com.clpstudio.filesmanager.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.clpstudio.filesmanager.BuildConfig
import com.clpstudio.filesmanager.crash.CrashLog
import com.clpstudio.filesmanager.data.Diagnostics
import com.clpstudio.filesmanager.ui.components.ConfirmDialog
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Item 38: Settings > Logs, like Proton's apps. Everything stays on the phone until the person shares it. */
object AppLogsText {
    const val ISSUES_URL = "https://github.com/clpsudio/filesmanager/issues/new"

    /** The text that is shown, copied and shared: device lines, the app log, then saved crash reports. */
    fun build(context: Context): String = buildString {
        CrashLog.deviceLines().forEach { appendLine(it) }
        appendLine()
        appendLine("=== App log ===")
        append(Diagnostics.rolling?.readAll().orEmpty().ifBlank { "(empty)\n" })
        val crashes = CrashLog.list(context)
        if (crashes.isNotEmpty()) {
            appendLine()
            appendLine("=== Crash reports (${crashes.size}) ===")
            crashes.forEach { r -> appendLine(runCatching { r.file.readText() }.getOrDefault("")); appendLine() }
        }
    }

    fun deleteAll(context: Context) {
        Diagnostics.clear()
        CrashLog.clear(context)
    }

    /** Writes the text to the app's cache and shares the file through the system share sheet. */
    fun share(context: Context, text: String) {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "files-logs-${BuildConfig.VERSION_NAME}-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".txt")
        f.writeText(text)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Files ${BuildConfig.VERSION_NAME} logs")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share logs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    val text by produceState<String?>(null, reload) { value = withContext(Dispatchers.IO) { AppLogsText.build(context) } }
    LogsContent(
        text = text,
        onBack = onBack,
        onShare = { text?.let { t -> runCatching { AppLogsText.share(context, t) }.onFailure { Toast.makeText(context, "Couldn't share the logs", Toast.LENGTH_SHORT).show() } } },
        onCopy = {
            text?.let { t ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                // A clipboard entry has to fit in one Binder transaction: keep the newest part.
                cm.setPrimaryClip(ClipData.newPlainText("Files logs", if (t.length > 200_000) t.takeLast(200_000) else t))
                Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
            }
        },
        onDelete = { AppLogsText.deleteAll(context); reload++ },
        onReport = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppLogsText.ISSUES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
    )
}

@Composable
fun LogsContent(
    text: String?,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val lines = remember(text) { text?.lines()?.takeLast(5_000).orEmpty() }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size + 1) }
    GlassScaffold(topBar = { GlassTopBar(title = "Logs", onBack = onBack) }) { padding ->
        LazyColumn(state = listState, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 24.dp)) {
            item(key = "info") {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Text(
                        "Found a bug, error or crash? Share these logs and raise a ticket on GitHub. Logs stay on this phone until you share them; they never include what is inside your files, vault items or PINs.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Button(onClick = onShare, enabled = text != null) { Icon(Icons.Filled.Share, null); Text("  Share logs") }
                        OutlinedButton(onClick = onCopy, enabled = text != null) { Icon(Icons.Filled.ContentCopy, null); Text("  Copy") }
                        OutlinedButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, null); Text("  Delete logs") }
                        OutlinedButton(onClick = onReport) { Icon(Icons.Filled.BugReport, null); Text("  Report on GitHub") }
                    }
                    Text("github.com/clpsudio/filesmanager/issues", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            items(lines.size) { i ->
                Text(lines[i], fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
            item(key = "end") { Text(if (text == null) "Loading…" else "", style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete logs?",
            text = "All app logs and saved crash reports are deleted from this phone. New logs start again from now.",
            confirm = "Delete",
            destructive = true,
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}
