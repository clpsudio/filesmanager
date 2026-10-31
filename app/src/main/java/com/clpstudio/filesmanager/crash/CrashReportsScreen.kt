package com.clpstudio.filesmanager.crash

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clpstudio.filesmanager.ui.components.ConfirmDialog
import com.clpstudio.filesmanager.ui.components.EmptyState
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.glass.glassPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings → Crash reports: saved reports, each viewable and shareable as text; Clear removes all. */
@Composable
fun CrashReportsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    val reports by produceState<List<CrashReport>?>(null, reload) {
        value = withContext(Dispatchers.IO) { runCatching { CrashLog.list(context) }.getOrDefault(emptyList()) }
    }
    var open by remember { mutableStateOf<CrashReport?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    fun share(r: CrashReport) {
        try {
            context.startActivity(CrashLog.shareIntent(r).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app to share with", Toast.LENGTH_SHORT).show()
        }
    }

    val current = open
    if (current != null) {
        BackHandler { open = null }
        CrashReportView(current, onBack = { open = null }, onShare = { share(current) })
        return
    }

    GlassScaffold(
        topBar = {
            GlassTopBar(
                "Crash reports", onBack = onBack,
                subtitle = reports?.let { if (it.isEmpty()) null else "${it.size} saved on this phone" },
                actions = {
                    if (!reports.isNullOrEmpty()) {
                        IconButton(onClick = { confirmClear = true }) { Icon(Icons.Filled.DeleteSweep, "Clear") }
                    }
                },
            )
        },
    ) { padding ->
        val list = reports
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyState(Icons.Filled.BugReport, "No crash reports", "If Files ever closes unexpectedly, a report is saved here. It stays on this phone until you share it.")
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.file.name }) { r ->
                    Row(
                        Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(18.dp)).clickable { open = r }.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(formatCrashTime(r.time), style = MaterialTheme.typography.titleSmall)
                            Text(r.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { share(r) }) { Icon(Icons.Filled.Share, "Share", tint = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "Clear crash reports?",
            text = "All saved reports are deleted from this phone.",
            confirm = "Clear",
            onConfirm = {
                confirmClear = false
                runCatching { CrashLog.clear(context) }
                reload++
            },
            onDismiss = { confirmClear = false },
            destructive = true,
        )
    }
}

@Composable
private fun CrashReportView(report: CrashReport, onBack: () -> Unit, onShare: () -> Unit) {
    val text by produceState<String?>(null, report) {
        value = withContext(Dispatchers.IO) { runCatching { report.file.readText() }.getOrDefault("(This report could not be read.)") }
    }
    GlassScaffold(
        topBar = {
            GlassTopBar(
                formatCrashTime(report.time), onBack = onBack, subtitle = "Crash report",
                actions = { IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Share") } },
            )
        },
    ) { padding ->
        SelectionContainer {
            Text(
                text.orEmpty(),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                softWrap = false,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 32.dp),
            )
        }
    }
}

internal fun formatCrashTime(millis: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))
