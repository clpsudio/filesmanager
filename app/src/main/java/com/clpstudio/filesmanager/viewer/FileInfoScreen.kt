package com.clpstudio.filesmanager.viewer

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.FileOps
import com.clpstudio.filesmanager.data.OpenedFile
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.components.formatSize
import com.clpstudio.filesmanager.ui.components.kindColor
import com.clpstudio.filesmanager.ui.components.kindIcon
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.glass.glassPanel
import java.io.File

/** For types without an in-app viewer yet (Office, RAR, 7z, unknown): info, open with, share. */
@Composable
fun FileInfoScreen(
    file: OpenedFile,
    onBack: () -> Unit,
    onViewText: (() -> Unit)? = null,
    onViewHex: (() -> Unit)? = null,
    extra: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val shareUri = file.path?.let { FileOps.uriFor(context, File(it)) } ?: file.uri
    GlassScaffold(topBar = { GlassTopBar(file.name, onBack = onBack) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(20.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(28.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(kindIcon(file.kind), null, tint = kindColor(file.kind), modifier = Modifier.size(72.dp))
                Text(file.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text(
                    listOfNotNull(file.mime, file.size.takeIf { it >= 0 }?.let { formatSize(it) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when (file.kind) {
                        FileKind.DOCUMENT -> "Office preview comes in the next update. Open it with another app for now."
                        FileKind.ARCHIVE -> "This archive type opens in the next update (ZIP files open here already)."
                        else -> "Files can't preview this type yet."
                    },
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Button(onClick = { FileOps.openWithOtherApp(context, shareUri, file.mime, file.name) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
                    Text("  Open with another app")
                }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = file.mime
                        putExtra(Intent.EXTRA_STREAM, shareUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    com.clpstudio.filesmanager.data.SafeStart.start(context, Intent.createChooser(send, "Share"), "This file can't be shared from here")
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Share, null)
                    Text("  Share")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (onViewText != null) TextButton(onClick = onViewText, modifier = Modifier.weight(1f)) { Text("View as text") }
                    if (onViewHex != null) TextButton(onClick = onViewHex, modifier = Modifier.weight(1f)) { Text("Hex view") }
                }
                extra()
            }
        }
    }
}
