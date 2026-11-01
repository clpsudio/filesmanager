package com.clpstudio.filesmanager.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
 *
 * Four sections keep the licences apart: CLP Studio's own code (Apache-2.0, decision pending),
 * FFmpeg (LGPL-2.1-or-later), Junrar (UnRAR licence) and the other third-party components.
 */
object OpenSourceLicences {
    const val DIR = "licenses"

    /** [note] is shown above the text, clearly apart from it; the text itself is shown as bundled. */
    data class Entry(val title: String, val summary: String, val asset: String, val note: String? = null)

    data class Section(val heading: String, val intro: String, val entries: List<Entry>, val status: String? = null)

    const val NOT_OSI = "The UnRAR licence is not an OSI-approved open-source licence."

    val sections = listOf(
        Section(
            heading = "CLP Studio's own code",
            status = "Apache-2.0 · decision PENDING",
            intro = "Apache-2.0 is the proposed licence for the code written by CLP Studio. It is not in force yet, " +
                "and it covers only CLP Studio's own code, not FFmpeg, Junrar or the other components below.",
            entries = listOf(
                Entry("About these licences", "Which licence covers which part of Files.", "ABOUT.txt"),
                Entry(
                    "Apache License 2.0 (proposed, PENDING)",
                    "Full text of the licence proposed for CLP Studio's own code. Decision pending.",
                    "Apache-2.0.txt",
                    note = "Licence decision PENDING. If CLP Studio confirms it, this licence will apply only to " +
                        "CLP Studio's own code. FFmpeg and Junrar keep their own licences.",
                ),
            ),
        ),
        Section(
            heading = "FFmpeg (LGPL-2.1-or-later)",
            intro = "The audio decoders. FFmpeg is linked statically into libffmpegJNI.so, which can be " +
                "replaced as a whole. Its source code and exact build configuration are published.",
            entries = listOf(
                Entry("FFmpeg notice", "What is included: version, licence, the 11 audio decoders.", "FFMPEG-NOTICE.txt"),
                Entry(
                    "Source code and build configuration",
                    "Where the corresponding source and the exact configure options are published. Written offer.",
                    "FFMPEG-SOURCE.txt",
                ),
                Entry(
                    "Replacing or relinking FFmpeg",
                    "How to rebuild libffmpegJNI.so with a modified FFmpeg: RELINKING.md and the relink kit.",
                    "FFMPEG-RELINKING.txt",
                ),
                Entry("GNU Lesser General Public License 2.1", "Full text of the licence FFmpeg is distributed under.", "LGPL-2.1.txt"),
                Entry("av_sscanf (from musl libc)", "MIT licence notice for one FFmpeg file, libavutil/avsscanf.c.", "MIT-musl-avsscanf.txt"),
            ),
        ),
        Section(
            heading = "Junrar (UnRAR licence)",
            intro = "Opens RAR archives. $NOT_OSI It may not be used to re-create the RAR compression algorithm.",
            entries = listOf(
                Entry(
                    "UnRAR licence (verbatim)",
                    "\"…cannot be used to re-create the RAR compression algorithm, which is proprietary.\"",
                    "UnRAR-junrar-7.5.5.txt",
                    note = "Junrar 7.5.5 is distributed under the UnRAR licence below, shown verbatim. " +
                        "$NOT_OSI Junrar's code may not be used to develop a RAR (WinRAR) compatible archiver.",
                ),
            ),
        ),
        Section(
            heading = "Other third-party components",
            intro = "AndroidX and Media3 (including its FFmpeg decoder Java and JNI code), Kotlin, Coil and most " +
                "other libraries are under Apache-2.0; a few are MIT, BSD or 0BSD.",
            entries = listOf(
                Entry(
                    "Apache License 2.0",
                    "Full text, for AndroidX, Media3, Kotlin, Coil, Commons Compress, PdfBox-Android, Zip4j and others.",
                    "Apache-2.0.txt",
                ),
                Entry("Notices", "Attribution notices for the components in this app.", "NOTICE"),
                Entry("All third-party components", "Every library in the app with its version, licence and source link.", "THIRD_PARTY_LICENSES.md"),
            ),
        ),
    )

    val entries: List<Entry> get() = sections.flatMap { it.entries }

    fun read(context: Context, asset: String): String =
        runCatching { context.assets.open("$DIR/$asset").bufferedReader().use { it.readText() } }
            .getOrElse { "This text is missing from this build ($DIR/$asset)." }
}

/**
 * Lays out a bundled text for a phone screen. The files are hard-wrapped at about 72-80 columns, which
 * breaks mid-sentence on a narrow screen, so wrapped lines are joined back into paragraphs and the screen
 * wraps them. Only line-break whitespace changes: every other character is kept (tested).
 * Kept as they are: blank-line paragraph breaks, short lines (titles, addresses, signatures), list items,
 * centred lines, and aligned blocks (ASCII art, tables, columns), which scroll sideways instead.
 */
object LicenceText {
    enum class Kind { PROSE, HEADING, PRE }
    data class Block(val kind: Kind, val text: String)

    /** A line shorter than this ended where the author wanted it to, not where the editor wrapped it. */
    private const val WRAPPED_MIN = 50
    private val listMarker = Regex("""^\s*([-*•]|\d+[.)]|[a-zA-Z][.)]|\([a-zA-Z0-9]+\))\s+\S""")
    private val aligned = Regex("""\S {3,}\S""")
    private val underline = Regex("""^\s*([=\-~])\1{2,}\s*$""")
    private val mdHeading = Regex("""^#{1,6}\s+""")

    fun blocks(text: String, markdown: Boolean = false): List<Block> {
        val out = mutableListOf<Block>()
        val group = mutableListOf<String>()
        var fence: MutableList<String>? = null
        fun flushGroup() {
            if (group.isNotEmpty()) out += layout(group.toList(), markdown)
            group.clear()
        }
        for (raw in text.replace("\r\n", "\n").split('\n')) {
            val line = raw.trimEnd()
            if (markdown && line.trimStart().startsWith("```")) {
                val open = fence
                if (open == null) { flushGroup(); fence = mutableListOf(line) } else {
                    open += line; out += Block(Kind.PRE, open.joinToString("\n")); fence = null
                }
                continue
            }
            val inFence = fence
            if (inFence != null) { inFence += line; continue }
            if (line.isBlank()) flushGroup() else group += line
        }
        fence?.let { out += Block(Kind.PRE, it.joinToString("\n")) }
        flushGroup()
        return out
    }

    private fun isPre(lines: List<String>, markdown: Boolean) = lines.any { l ->
        val t = l.trim()
        aligned.containsMatchIn(t) || (markdown && t.startsWith("|")) ||
            (t.length >= 4 && !underline.matches(t) && t.count { it.isLetterOrDigit() } * 3 < t.length)
    }

    private fun layout(lines: List<String>, markdown: Boolean): List<Block> {
        if (isPre(lines, markdown)) return listOf(Block(Kind.PRE, lines.joinToString("\n")))
        val out = mutableListOf<Block>()
        val para = mutableListOf<StringBuilder>()
        fun flush(kind: Kind = Kind.PROSE) {
            if (para.isNotEmpty()) out += Block(kind, para.joinToString("\n"))
            para.clear()
        }
        var prev: String? = null
        for (line in lines) {
            val t = line.trim()
            when {
                markdown && mdHeading.containsMatchIn(t) -> { flush(); out += Block(Kind.HEADING, t.replace(mdHeading, "")); prev = null; continue }
                underline.matches(t) && para.size == 1 && prev != null -> { flush(Kind.HEADING); prev = null; continue }
                underline.matches(t) -> { flush(); out += Block(Kind.PRE, t); prev = null; continue }
            }
            val lead = line.length - line.trimStart().length
            val newLine = prev == null || listMarker.containsMatchIn(line) || prev.length < WRAPPED_MIN || lead >= 8
            if (newLine || para.isEmpty()) para += StringBuilder(t) else para.last().append(' ').append(t)
            prev = t
        }
        flush()
        return out
    }
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
        LicenceTextContent(title = current.title, text = text, onBack = { open = null }, note = current.note, markdown = current.asset.endsWith(".md"))
    }
}

@Composable
private fun LicenceSectionHeader(section: OpenSourceLicences.Section) {
    Column(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 2.dp)) {
        // Same section style as Settings: blue (primary) label.
        Text(
            section.heading, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() },
        )
        section.status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        Text(section.intro, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun LicencesListContent(onBack: () -> Unit, onOpen: (OpenSourceLicences.Entry) -> Unit) {
    GlassScaffold(topBar = { GlassTopBar(title = "Open-source licences", onBack = onBack) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (section in OpenSourceLicences.sections) {
                item(key = "h:${section.heading}") { LicenceSectionHeader(section) }
                items(section.entries, key = { "${section.heading}:${it.asset}" }) { e ->
                    Column(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(22.dp)).clickable { onOpen(e) }.padding(16.dp)) {
                        Text(e.title, style = MaterialTheme.typography.titleSmall)
                        Text(e.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun LicenceTextContent(title: String, text: String?, onBack: () -> Unit, note: String? = null, markdown: Boolean = false) {
    val blocks = remember(text, markdown) { text?.let { LicenceText.blocks(it, markdown) }.orEmpty() }
    GlassScaffold(topBar = { GlassTopBar(title = title, onBack = onBack) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (note != null) {
                item(key = "note") {
                    Text(
                        note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(16.dp)).padding(12.dp),
                    )
                }
            }
            items(blocks.size) { i ->
                val b = blocks[i]
                when (b.kind) {
                    LicenceText.Kind.HEADING -> Text(
                        b.text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
                    )
                    LicenceText.Kind.PROSE -> Text(b.text, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
                    LicenceText.Kind.PRE -> Text(
                        b.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, softWrap = false,
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    )
                }
            }
            if (text == null) item { Text("Loading…", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
