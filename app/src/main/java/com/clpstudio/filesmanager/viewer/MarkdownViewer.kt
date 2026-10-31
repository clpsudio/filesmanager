package com.clpstudio.filesmanager.viewer

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clpstudio.filesmanager.ui.components.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Markdown files: rendered (Preview) by default; Source shows the plain text. Fully offline, no libraries. */
object Markdown {
    /** Bigger files open as Source so the renderer can't freeze the phone. */
    const val MAX_PREVIEW_BYTES = 2L * 1024 * 1024

    fun isMarkdown(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "mdown", "mkd")
}

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    /** One list line; [number] for ordered lists, [checked] for task lists (null = plain item). */
    data class ListItem(val depth: Int, val number: Int?, val checked: Boolean?, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val lang: String, val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>, val align: List<TextAlign>) : MdBlock
    data class Image(val alt: String, val src: String) : MdBlock
    data object Rule : MdBlock
}

/** A small CommonMark-style block parser: enough for READMEs, notes and docs. Pure (unit-tested). */
object MarkdownParser {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val rule = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val bullet = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val ordered = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val task = Regex("^\\[([ xX])]\\s+(.*)$")
    private val fence = Regex("^\\s{0,3}(```|~~~)\\s*([\\w+#.-]*).*$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$")
    private val image = Regex("^\\s*!\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)\\s*$")

    fun parse(text: String): List<MdBlock> {
        val lines = text.replace("\r\n", "\n").replace('\t', ' ').split('\n')
        val out = ArrayList<MdBlock>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
            para.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val f = fence.matchEntire(line)
            if (f != null) {
                flush()
                val marker = f.groupValues[1]
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(marker)) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i]); i++
                }
                out += MdBlock.Code(f.groupValues[2], code.toString())
                i++
                continue
            }
            if (line.isBlank()) { flush(); i++; continue }
            val h = heading.matchEntire(line)
            if (h != null) { flush(); out += MdBlock.Heading(h.groupValues[1].length, h.groupValues[2]); i++; continue }
            // Setext headings (Title / ===== or ----- under a paragraph line).
            if (para.isNotBlank() && i > 0 && line.trim().let { t -> t.length >= 2 && (t.all { it == '=' } || t.all { it == '-' }) }) {
                val level = if (line.trim().startsWith("=")) 1 else 2
                val title = para.toString().trim(); para.clear()
                out += MdBlock.Heading(level, title); i++; continue
            }
            if (rule.matches(line)) { flush(); out += MdBlock.Rule; i++; continue }
            val im = image.matchEntire(line)
            if (im != null) { flush(); out += MdBlock.Image(im.groupValues[1], im.groupValues[2]); i++; continue }
            if (line.contains('|') && i + 1 < lines.size && tableSep.matches(lines[i + 1])) {
                flush()
                val header = cells(line)
                val align = cells(lines[i + 1]).map { c ->
                    val t = c.trim()
                    when {
                        t.startsWith(":") && t.endsWith(":") -> TextAlign.Center
                        t.endsWith(":") -> TextAlign.End
                        else -> TextAlign.Start
                    }
                }
                i += 2
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) { rows += cells(lines[i]); i++ }
                out += MdBlock.Table(header, rows, align)
                continue
            }
            if (line.trimStart().startsWith(">")) {
                flush()
                val q = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    if (q.isNotEmpty()) q.append('\n')
                    q.append(lines[i].trimStart().removePrefix(">").removePrefix(" ")); i++
                }
                out += MdBlock.Quote(q.toString())
                continue
            }
            val b = bullet.matchEntire(line)
            val o = if (b == null) ordered.matchEntire(line) else null
            if (b != null || o != null) {
                flush()
                val m = b ?: o!!
                val depth = m.groupValues[1].length / 2
                var body = m.groupValues[3]
                var checked: Boolean? = null
                task.matchEntire(body)?.let { t -> checked = t.groupValues[1] != " "; body = t.groupValues[2] }
                out += MdBlock.ListItem(depth, o?.groupValues?.get(2)?.toIntOrNull(), checked, body)
                i++
                continue
            }
            if (line.startsWith("    ") && para.isEmpty()) {
                val code = StringBuilder()
                while (i < lines.size && (lines[i].startsWith("    ") || lines[i].isBlank())) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i].removePrefix("    ")); i++
                }
                out += MdBlock.Code("", code.toString().trimEnd())
                continue
            }
            if (para.isNotEmpty()) para.append(if (line.endsWith("  ")) "\n" else " ")
            para.append(line.trim())
            i++
        }
        flush()
        return out
    }

    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    private val inlineToken = Regex(
        "(`+)(.+?)\\1" +                                   // 1,2 code
            "|!\\[([^\\]]*)]\\(([^)\\s]+)[^)]*\\)" +          // 3,4 image
            "|\\[([^\\]]+)]\\(([^)\\s]+)[^)]*\\)" +           // 5,6 link
            "|(\\*\\*|__)(.+?)\\7" +                         // 7,8 bold
            "|~~(.+?)~~" +                                  // 9 strike
            "|(?<![\\w*])([*_])(?!\\s)(.+?)(?<!\\s)\\10(?![\\w*])" + // 10,11 italic
            "|<?(https?://[^\\s<>)]+)>?",                    // 12 autolink
    )

    /** Inline markdown to styled text; links carry a URL that opens only when tapped. */
    fun inline(src: String, codeBg: Color, link: Color): AnnotatedString = buildAnnotatedString {
        fun go(s: String) {
            var pos = 0
            for (m in inlineToken.findAll(s)) {
                if (m.range.first > pos) append(unescape(s.substring(pos, m.range.first)))
                val g = m.groupValues
                when {
                    g[2].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 14.sp)) { append(" ${g[2]} ") }
                    g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("[image: ${g[3].ifBlank { "picture" }}]") }
                    g[6].isNotEmpty() -> withLink(LinkAnnotation.Url(g[6], TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) { go(g[5]) }
                    g[8].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { go(g[8]) }
                    g[9].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { go(g[9]) }
                    g[11].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { go(g[11]) }
                    g[12].isNotEmpty() -> withLink(LinkAnnotation.Url(g[12], TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) { append(g[12]) }
                    else -> append(m.value)
                }
                pos = m.range.last + 1
            }
            if (pos < s.length) append(unescape(s.substring(pos)))
        }
        go(src)
    }

    private fun unescape(s: String) = s.replace(Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!|~>])"), "$1")
}

/** Reads a Markdown file (up to [Markdown.MAX_PREVIEW_BYTES]); null text = too big for Preview. */
private suspend fun readMarkdown(context: android.content.Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    val size = runCatching {
        if (uri.scheme == "file") File(uri.path!!).length()
        else context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }.getOrDefault(-1L)
    if (size > Markdown.MAX_PREVIEW_BYTES) return@withContext null
    context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
}

/**
 * A rendered Markdown page, theme-aware (dark and light), 16sp body. Falls back to [TextContent] (Source)
 * when [source] is on or the file is over 2 MB.
 */
@Composable
fun MarkdownContent(uri: Uri, source: Boolean, modifier: Modifier = Modifier, topPadding: Dp = 100.dp) {
    val context = LocalContext.current
    val loaded by produceState<Result<String?>?>(null, uri) { value = runCatching { readMarkdown(context, uri) } }
    val r = loaded
    when {
        source -> TextContent(uri, modifier, topPadding)
        r == null -> Box(modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        r.isFailure -> EmptyState(Icons.Filled.Description, "Can't read this file", modifier = modifier)
        r.getOrNull() == null -> Column(modifier.fillMaxSize()) {
            Text(
                "Large file: showing the source.",
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 16.dp, top = topPadding, bottom = 4.dp),
            )
            TextContent(uri, Modifier.weight(1f), topPadding = 8.dp)
        }
        else -> MarkdownDocument(r.getOrThrow()!!, modifier, topPadding, baseDir = uri.takeIf { it.scheme == "file" }?.path?.let { File(it).parentFile })
    }
}

/** Renders already-loaded Markdown [text]; [baseDir] resolves local relative images. */
@Composable
fun MarkdownDocument(text: String, modifier: Modifier = Modifier, topPadding: Dp = 100.dp, baseDir: File? = null) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    val scheme = MaterialTheme.colorScheme
    val codeBg = scheme.onSurface.copy(alpha = 0.08f)
    val link = scheme.primary
    val body = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 25.sp, color = scheme.onSurface)
com.clpstudio.filesmanager.ui.components.DocumentSelection(modifier.fillMaxSize()) {
    LazyColumn(
        modifier.fillMaxSize().background(scheme.background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topPadding, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(blocks) { b ->
            Box(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                when (b) {
                    is MdBlock.Heading -> Column {
                        val size = when (b.level) { 1 -> 28.sp; 2 -> 23.sp; 3 -> 20.sp; 4 -> 18.sp; else -> 16.sp }
                        Text(
                            MarkdownParser.inline(b.text, codeBg, link),
                            style = body.copy(fontSize = size, lineHeight = size * 1.3f, fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(top = if (b.level <= 2) 8.dp else 4.dp),
                        )
                        if (b.level <= 2) HorizontalDivider(Modifier.padding(top = 6.dp), color = scheme.outlineVariant)
                    }
                    is MdBlock.Paragraph -> Text(MarkdownParser.inline(b.text, codeBg, link), style = body)
                    is MdBlock.ListItem -> Row(Modifier.padding(start = (b.depth * 20).dp)) {
                        val marker = when {
                            b.checked == true -> "☑"
                            b.checked == false -> "☐"
                            b.number != null -> "${b.number}."
                            b.depth % 2 == 0 -> "•"
                            else -> "◦"
                        }
                        Text(marker, style = body.copy(color = if (b.checked != null) scheme.primary else scheme.onSurface), modifier = Modifier.width(26.dp))
                        Text(
                            MarkdownParser.inline(b.text, codeBg, link),
                            style = if (b.checked == true) body.copy(color = scheme.onSurfaceVariant, textDecoration = TextDecoration.LineThrough) else body,
                        )
                    }
                    is MdBlock.Quote -> Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).clip(RoundedCornerShape(6.dp)).background(scheme.primary.copy(alpha = 0.06f))) {
                        Box(Modifier.width(4.dp).fillMaxHeight().background(scheme.primary.copy(alpha = 0.6f)))
                        Text(
                            MarkdownParser.inline(b.text, codeBg, link),
                            style = body.copy(color = scheme.onSurfaceVariant, fontStyle = FontStyle.Italic),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    is MdBlock.Code -> Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(codeBg)
                            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(10.dp)).padding(12.dp),
                    ) {
                        if (b.lang.isNotBlank()) Text(b.lang, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        Text(
                            b.text, fontFamily = FontFamily.Monospace, fontSize = 13.5.sp, lineHeight = 19.sp, color = scheme.onSurface,
                            softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                    is MdBlock.Table -> MdTable(b, body, codeBg, link)
                    is MdBlock.Image -> MdImage(b, baseDir, body)
                    MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 6.dp), thickness = 2.dp, color = scheme.outlineVariant)
                }
            }
        }
    }
}
}

@Composable
private fun MdTable(t: MdBlock.Table, body: androidx.compose.ui.text.TextStyle, codeBg: Color, link: Color) {
    val scheme = MaterialTheme.colorScheme
    val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0)
    val cell = body.copy(fontSize = 14.5.sp, lineHeight = 20.sp)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    // Columns share the width when they fit; wider tables scroll sideways.
    val colW = maxOf(110.dp, (maxWidth - 2.dp) / cols.coerceAtLeast(1))
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(Modifier.border(1.dp, scheme.outlineVariant, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))) {
            val all = listOf(t.header) + t.rows
            all.forEachIndexed { r, row ->
                Row(Modifier.background(if (r == 0) scheme.primary.copy(alpha = 0.10f) else if (r % 2 == 0) scheme.onSurface.copy(alpha = 0.03f) else Color.Transparent)) {
                    for (c in 0 until cols) {
                        Text(
                            MarkdownParser.inline(row.getOrElse(c) { "" }, codeBg, link),
                            style = if (r == 0) cell.copy(fontWeight = FontWeight.SemiBold) else cell,
                            textAlign = t.align.getOrElse(c) { TextAlign.Start },
                            modifier = Modifier.width(colW).padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
                if (r < all.size - 1) HorizontalDivider(Modifier.width(colW * cols), color = scheme.outlineVariant)
            }
        }
    }
    }
}

@Composable
private fun MdImage(img: MdBlock.Image, baseDir: File?, body: androidx.compose.ui.text.TextStyle) {
    val local = remember(img.src, baseDir) {
        if (img.src.startsWith("http://") || img.src.startsWith("https://") || baseDir == null) null
        else File(baseDir, Uri.decode(img.src.removePrefix("./"))).takeIf { it.isFile }
    }
    if (local != null) {
        coil3.compose.AsyncImage(
            model = local, contentDescription = img.alt,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
        )
    } else {
        // Remote images are never fetched (the app is offline): alt text in a placeholder.
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(14.dp),
        ) {
            Text("🖼  " + img.alt.ifBlank { "Image" }, style = body.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic))
        }
    }
    Spacer(Modifier.height(2.dp))
}
