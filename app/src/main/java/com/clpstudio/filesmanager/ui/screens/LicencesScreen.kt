package com.clpstudio.filesmanager.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.glass.glassPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings > Open-source licences. Shows the licence texts and notices bundled in the APK
 * (assets/licenses/, copied at build time from the repository's NOTICE,
 * THIRD_PARTY_LICENSES.md and licenses/ folder, so the app and the repository never differ).
 */
object OpenSourceLicences {
    const val DIR = "licenses"

    data class Entry(val title: String, val summary: String, val asset: String)

    val entries = listOf(
        Entry("About these licences", "Files is made of CLP Studio's own code plus third-party components, each under its own licence.", "ABOUT.txt"),
        Entry("FFmpeg (audio decoders)", "LGPL-2.1-or-later. Where to get its source code and how to rebuild or replace it.", "FFMPEG-NOTICE.txt"),
        Entry("GNU Lesser General Public License 2.1", "Full text of the licence FFmpeg is distributed under.", "LGPL-2.1.txt"),
        Entry("av_sscanf (from musl libc)", "MIT licence notice for one FFmpeg file.", "MIT-musl-avsscanf.txt"),
        Entry("Junrar (RAR archives)", "UnRAR licence, with its restriction on re-creating RAR compression.", "UnRAR-junrar-7.5.5.txt"),
        Entry("Apache License 2.0", "Full text. Used by AndroidX, Media3 and its FFmpeg decoder code, Kotlin, Coil and others.", "Apache-2.0.txt"),
        Entry("Notices", "Attribution notices for the components in this app.", "NOTICE"),
        Entry("All third-party components", "Every library in the app with its version, licence and source link.", "THIRD_PARTY_LICENSES.md"),
    )

    fun read(context: Context, asset: String): String =
        runCatching { context.assets.open("$DIR/$asset").bufferedReader().use { it.readText() } }
            .getOrElse { "This text is missing from this build ($DIR/$asset)." }
}

@Composable
fun LicencesScreen(onBack: () -> Unit) {
    var open by remember { mutableStateOf<OpenSourceLicences.Entry?>(null) }
    val current = open
    if (current == null) {
        LicencesListContent(onBack = onBack, onOpen = { open = it })
    } else {
        BackHandler { open = null }
        val context = LocalContext.current
        val text by produceState<String?>(null, current) { value = withContext(Dispatchers.IO) { OpenSourceLicences.read(context, current.asset) } }
        LicenceTextContent(title = current.title, text = text, onBack = { open = null })
    }
}

@Composable
fun LicencesListContent(onBack: () -> Unit, onOpen: (OpenSourceLicences.Entry) -> Unit) {
    GlassScaffold(topBar = { GlassTopBar(title = "Open-source licences", onBack = onBack) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(OpenSourceLicences.entries, key = { it.asset }) { e ->
                Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(22.dp)).clickable { onOpen(e) }.padding(16.dp)) {
                    Text(e.title, style = MaterialTheme.typography.titleSmall)
                    Text(e.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun LicenceTextContent(title: String, text: String?, onBack: () -> Unit) {
    val lines = remember(text) { text?.lines().orEmpty() }
    GlassScaffold(topBar = { GlassTopBar(title = title, onBack = onBack) }) { padding ->
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 24.dp)) {
            items(lines.size) { i -> Text(lines[i], fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp) }
            item { Text(if (text == null) "Loading…" else "", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
