package com.clpstudio.filesmanager.viewer.office

import java.io.File
import java.util.zip.ZipFile
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------------------------
// Model
// ---------------------------------------------------------------------------------------------

enum class DocAlign { START, CENTER, END, JUSTIFY }

sealed interface DocBlock {
    /** A hard break (page break, "page break before", a new section) starts a new page here. */
    val pageBreakBefore: Boolean
}

sealed interface DocRun {
    data class Text(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val sizePt: Float = 11f,
        /** "#RRGGBB", or null for the default (black). */
        val colorHex: String? = null,
        val highlightHex: String? = null,
        /** Link target (URL, or "#bookmark" for an internal anchor). */
        val hyperlink: String? = null,
        /** 1 = superscript, -1 = subscript. */
        val script: Int = 0,
    ) : DocRun

    /** An inline picture; [path] is the zip path, its bytes are in [DocxDocument.images]. */
    data class Image(val path: String, val widthPt: Float, val heightPt: Float, val description: String? = null) : DocRun
}

data class DocParagraph(
    val runs: List<DocRun>,
    val align: DocAlign = DocAlign.START,
    /** 1..9 for headings (Title counts as 1), 0 for body text. */
    val headingLevel: Int = 0,
    /** "•", "1.", "a)", … for list items. */
    val listMarker: String? = null,
    val listLevel: Int = 0,
    val indentStartPt: Float = 0f,
    val firstLinePt: Float = 0f,
    val spaceBeforePt: Float = 0f,
    val spaceAfterPt: Float = 0f,
    override val pageBreakBefore: Boolean = false,
    /** Word's own pagination marker from the last time it laid the file out. */
    val renderedPageBreakBefore: Boolean = false,
    val styleId: String? = null,
) : DocBlock {
    val text: String get() = runs.joinToString("") { (it as? DocRun.Text)?.text ?: "" }
    val hasImage: Boolean get() = runs.any { it is DocRun.Image }
    val maxSizePt: Float get() = runs.maxOfOrNull { (it as? DocRun.Text)?.sizePt ?: 0f }?.takeIf { it > 0f } ?: 11f
}

data class DocTableCell(
    val blocks: List<DocBlock>,
    val colSpan: Int = 1,
    /** Continuation of a vertically merged cell (drawn empty). */
    val mergedContinue: Boolean = false,
    val fillHex: String? = null,
)

data class DocTableRow(val cells: List<DocTableCell>, val header: Boolean = false)

data class DocTable(
    val rows: List<DocTableRow>,
    /** Grid column widths in points (may be empty: then columns are equal). */
    val columnWidthsPt: List<Float> = emptyList(),
    val borders: Boolean = true,
    override val pageBreakBefore: Boolean = false,
) : DocBlock {
    val columnCount: Int get() = max(columnWidthsPt.size, rows.maxOfOrNull { r -> r.cells.sumOf { it.colSpan } } ?: 0)
}

data class DocPageSetup(
    val widthPt: Float = 595.3f,
    val heightPt: Float = 841.9f,
    val marginLeftPt: Float = 72f,
    val marginRightPt: Float = 72f,
    val marginTopPt: Float = 72f,
    val marginBottomPt: Float = 72f,
) {
    val contentWidthPt: Float get() = (widthPt - marginLeftPt - marginRightPt).coerceAtLeast(72f)
    val contentHeightPt: Float get() = (heightPt - marginTopPt - marginBottomPt).coerceAtLeast(72f)
}

data class DocxDocument(
    val blocks: List<DocBlock>,
    /** Zip path -> image bytes (decode with [decodeOfficeImage]). */
    val images: Map<String, ByteArray>,
    val page: DocPageSetup = DocPageSetup(),
    val defaultSizePt: Float = 11f,
    /** Parts that couldn't be read; the rest of the document is still shown. */
    val warnings: List<String> = emptyList(),
    /** True when the document was cut short (too big, or damaged part way through). */
    val truncated: Boolean = false,
) {
    /**
     * Splits the blocks into pages. Hard breaks always start a page. When Word left its own
     * pagination markers they are used; otherwise pages are estimated from the text length,
     * and long tables are split across pages by rows.
     */
    fun pages(): List<List<DocBlock>> {
        val useRendered = blocks.any { (it as? DocParagraph)?.renderedPageBreakBefore == true }
        val pages = ArrayList<MutableList<DocBlock>>()
        pages.add(ArrayList())
        var used = 0f
        val capacity = page.contentHeightPt
        fun newPage() {
            if (pages.last().isNotEmpty()) pages.add(ArrayList())
            used = 0f
        }
        for (b in blocks) {
            val rendered = (b as? DocParagraph)?.renderedPageBreakBefore == true
            if (b.pageBreakBefore || (useRendered && rendered)) newPage()
            if (useRendered) {
                pages.last().add(b)
                continue
            }
            when (b) {
                is DocParagraph -> {
                    val h = estimateHeight(b, page.contentWidthPt)
                    if (used + h > capacity && pages.last().isNotEmpty()) newPage()
                    pages.last().add(b)
                    used += h
                }
                is DocTable -> {
                    var chunk = ArrayList<DocTableRow>()
                    var first = true
                    for (row in b.rows) {
                        val h = estimateRowHeight(b, row, page.contentWidthPt)
                        if (used + h > capacity && (pages.last().isNotEmpty() || chunk.isNotEmpty())) {
                            if (chunk.isNotEmpty()) {
                                pages.last().add(b.copy(rows = chunk, pageBreakBefore = b.pageBreakBefore && first))
                                first = false
                                chunk = ArrayList()
                            }
                            newPage()
                        }
                        chunk.add(row)
                        used += h
                    }
                    if (chunk.isNotEmpty() || b.rows.isEmpty()) pages.last().add(b.copy(rows = chunk, pageBreakBefore = b.pageBreakBefore && first))
                }
            }
        }
        return pages.filter { it.isNotEmpty() }.ifEmpty { listOf(emptyList()) }
    }

    private fun estimateHeight(p: DocParagraph, widthPt: Float): Float {
        val size = p.maxSizePt
        val usable = (widthPt - p.indentStartPt).coerceAtLeast(widthPt / 3)
        val charsPerLine = max(8f, usable / (size * 0.5f))
        val lines = max(1f, ceil(p.text.length / charsPerLine))
        var h = lines * size * 1.2f + p.spaceBeforePt + p.spaceAfterPt
        for (r in p.runs) if (r is DocRun.Image) {
            val scale = if (r.widthPt > usable && r.widthPt > 0f) usable / r.widthPt else 1f
            h += r.heightPt * scale
        }
        return h
    }

    private fun estimateRowHeight(t: DocTable, row: DocTableRow, widthPt: Float): Float {
        val cols = max(1, t.columnCount)
        var maxH = 0f
        for (c in row.cells) {
            val cw = widthPt * c.colSpan / cols
            var h = 4f
            for (b in c.blocks) h += when (b) {
                is DocParagraph -> estimateHeight(b, cw)
                is DocTable -> b.rows.sumOf { estimateRowHeight(b, it, cw).toDouble() }.toFloat()
            }
            maxH = max(maxH, h)
        }
        return max(maxH, 14f)
    }
}

// ---------------------------------------------------------------------------------------------
// Parser
// ---------------------------------------------------------------------------------------------

/**
 * Offline .docx reader: java.util.zip + XmlPullParser, no third-party libraries.
 * Reads word/document.xml, styles.xml, numbering.xml, the relationships and the media parts.
 * Unknown elements are ignored; a damaged file returns what was read before the damage.
 */
object DocxParser {
    private const val MAX_BLOCKS = 50_000
    private const val MAX_CHARS = 8_000_000

    /** Parses [file]. Throws [OfficeParseException] when nothing at all can be read. */
    fun parse(file: File, maxBlocks: Int = MAX_BLOCKS): DocxDocument {
        // Item 11 (F11): the 97–2003 binary format (.doc / .xls / .ppt) goes to its own reader, same viewer.
        if (Cfb.isCfb(file)) return LegacyDoc.read(file)
        val zip = try {
            ZipFile(file)
        } catch (e: Exception) {
            throw OfficeParseException("This file isn't a valid Word document (.docx).", e)
        }
        return zip.use { parse(it, maxBlocks) }
    }

    /** [maxBlocks] stops early (thumbnails only need the first page); the result is then marked truncated. */
    fun parse(zip: ZipFile, maxBlocks: Int = MAX_BLOCKS): DocxDocument {
        val docPath = findMainDocument(zip) ?: throw OfficeParseException("This file isn't a Word document: word/document.xml is missing.")
        val warnings = ArrayList<String>()
        val base = zipDir(docPath)
        val styles = runCatching { zip.parseXml("${base}styles.xml") { readStyles(it) } }
            .onFailure { warnings.add("styles") }.getOrNull() ?: Styles()
        val numbering = runCatching { zip.parseXml("${base}numbering.xml") { readNumbering(it) } }
            .onFailure { warnings.add("numbering") }.getOrNull() ?: Numbering()
        val rels = zip.readRels(relsPathFor(docPath), base)
        val ctx = Ctx(zip, styles, numbering, rels, maxBlocks.coerceIn(1, MAX_BLOCKS))
        val entry = zip.findEntry(docPath)!!
        if (entry.size > OfficeLimits.MAX_XML_BYTES) throw OfficeParseException("This Word document is too large to show.")
        var truncated = false
        try {
            zip.getInputStream(entry).use { input ->
                val x = newXmlReader(input.buffered())
                if (x.toRoot()) {
                    x.children { n -> if (n == "body") ctx.body(x, ctx.out, topLevel = true) }
                }
                if (x.hitEof) {
                    truncated = true
                    warnings.add("document")
                }
            }
        } catch (e: StopParsing) {
            truncated = true
        } catch (e: Exception) {
            if (ctx.out.isEmpty()) throw OfficeParseException("This Word document is damaged and couldn't be read.", e)
            truncated = true
            warnings.add("document")
        }
        return DocxDocument(
            blocks = ctx.out,
            images = ctx.images,
            page = ctx.page,
            defaultSizePt = styles.defaultSizePt,
            warnings = warnings,
            truncated = truncated || ctx.limitHit,
        )
    }

    private fun findMainDocument(zip: ZipFile): String? {
        // The package relationship names the main part; fall back to the usual path.
        val rels = zip.readRels("_rels/.rels", "")
        rels.values.firstOrNull { it.type.endsWith("/officeDocument") && !it.external }?.let { r ->
            if (zip.findEntry(r.target) != null) return r.target
        }
        return if (zip.findEntry("word/document.xml") != null) "word/document.xml" else null
    }

    private class StopParsing : RuntimeException()

    // ---- properties ------------------------------------------------------------------------

    private class RProps(
        var bold: Boolean? = null,
        var italic: Boolean? = null,
        var underline: Boolean? = null,
        var strike: Boolean? = null,
        var sizePt: Float? = null,
        var color: String? = null,
        var colorAuto: Boolean = false,
        var highlight: String? = null,
        var script: Int? = null,
        var caps: Boolean? = null,
        var hidden: Boolean? = null,
        var styleId: String? = null,
    ) {
        fun overlay(o: RProps?): RProps {
            if (o == null) return this
            o.bold?.let { bold = it }
            o.italic?.let { italic = it }
            o.underline?.let { underline = it }
            o.strike?.let { strike = it }
            o.sizePt?.let { sizePt = it }
            if (o.color != null) { color = o.color; colorAuto = false } else if (o.colorAuto) { color = null; colorAuto = true }
            o.highlight?.let { highlight = it }
            o.script?.let { script = it }
            o.caps?.let { caps = it }
            o.hidden?.let { hidden = it }
            return this
        }

        fun copy() = RProps().overlay(this)
    }

    private class PProps(
        var styleId: String? = null,
        var align: DocAlign? = null,
        var numId: String? = null,
        var ilvl: Int? = null,
        var indStart: Float? = null,
        var firstLine: Float? = null,
        var spaceBefore: Float? = null,
        var spaceAfter: Float? = null,
        var pageBreakBefore: Boolean? = null,
        var outline: Int? = null,
        var sectionBreak: Boolean = false,
    ) {
        fun overlay(o: PProps?): PProps {
            if (o == null) return this
            o.align?.let { align = it }
            o.numId?.let { numId = it }
            o.ilvl?.let { ilvl = it }
            o.indStart?.let { indStart = it }
            o.firstLine?.let { firstLine = it }
            o.spaceBefore?.let { spaceBefore = it }
            o.spaceAfter?.let { spaceAfter = it }
            o.pageBreakBefore?.let { pageBreakBefore = it }
            o.outline?.let { outline = it }
            return this
        }
    }

    private class Style(
        val id: String,
        val type: String,
        var name: String = "",
        var basedOn: String? = null,
        var p: PProps = PProps(),
        var r: RProps = RProps(),
        var tableBorders: Boolean? = null,
    )

    private class Styles(
        val byId: Map<String, Style> = emptyMap(),
        val defaultR: RProps = RProps(),
        val defaultP: PProps = PProps(),
        val defaultParagraphStyle: String? = null,
    ) {
        val defaultSizePt: Float get() = defaultR.sizePt ?: 11f

        fun chain(id: String?): List<Style> {
            val out = ArrayList<Style>()
            var cur = id?.let { byId[it] }
            val seen = HashSet<String>()
            while (cur != null && seen.add(cur.id) && out.size < 20) {
                out.add(0, cur)
                cur = cur.basedOn?.let { byId[it] }
            }
            return out
        }
    }

    private fun twipsToPt(v: String?): Float? = v?.toFloatOrNull()?.let { it / 20f }

    private fun readRPr(x: XmlReader): RProps {
        val r = RProps()
        x.children { n ->
            when (n) {
                "rStyle" -> r.styleId = x.attr("val")
                "b" -> r.bold = x.onOff()
                "i" -> r.italic = x.onOff()
                "u" -> r.underline = x.attr("val")?.let { it != "none" } ?: true
                "strike", "dstrike" -> r.strike = x.onOff()
                "sz" -> x.attr("val")?.toFloatOrNull()?.let { if (it > 0) r.sizePt = (it / 2f).coerceIn(1f, 400f) }
                "color" -> {
                    val v = x.attr("val")
                    val hex = normalizeHex(v)
                    if (hex != null) r.color = hex else if (v.equals("auto", true)) r.colorAuto = true
                }
                "highlight" -> r.highlight = x.attr("val")?.let { HIGHLIGHT_COLORS[it] }
                "shd" -> if (r.highlight == null) normalizeHex(x.attr("fill"))?.let { if (it != "#FFFFFF") r.highlight = it }
                "vertAlign" -> r.script = when (x.attr("val")) { "superscript" -> 1; "subscript" -> -1; else -> 0 }
                "caps" -> r.caps = x.onOff()
                "vanish" -> r.hidden = x.onOff()
            }
        }
        return r
    }

    private fun readPPr(x: XmlReader, sectionBreakOut: ((Boolean) -> Unit)? = null): PProps {
        val p = PProps()
        x.children { n ->
            when (n) {
                "pStyle" -> p.styleId = x.attr("val")
                "jc" -> p.align = when (x.attr("val")) {
                    "center" -> DocAlign.CENTER
                    "right", "end" -> DocAlign.END
                    "both", "distribute", "lowKashida", "mediumKashida", "highKashida", "thaiDistribute" -> DocAlign.JUSTIFY
                    else -> DocAlign.START
                }
                "numPr" -> x.children { c ->
                    when (c) {
                        "ilvl" -> p.ilvl = x.attr("val")?.toIntOrNull()?.coerceIn(0, 8)
                        "numId" -> p.numId = x.attr("val")
                    }
                }
                "ind" -> {
                    twipsToPt(x.attr("start") ?: x.attr("left"))?.let { p.indStart = it }
                    val hanging = twipsToPt(x.attr("hanging"))
                    val first = twipsToPt(x.attr("firstLine"))
                    if (hanging != null) p.firstLine = -hanging else if (first != null) p.firstLine = first
                }
                "spacing" -> {
                    twipsToPt(x.attr("before"))?.let { p.spaceBefore = it }
                    twipsToPt(x.attr("after"))?.let { p.spaceAfter = it }
                }
                "pageBreakBefore" -> p.pageBreakBefore = x.onOff()
                "outlineLvl" -> p.outline = x.attr("val")?.toIntOrNull()
                "sectPr" -> {
                    var type = "nextPage"
                    x.children { c -> if (c == "type") type = x.attr("val") ?: "nextPage" }
                    p.sectionBreak = type != "continuous"
                    sectionBreakOut?.invoke(p.sectionBreak)
                }
            }
        }
        return p
    }

    private fun readStyles(x: XmlReader): Styles {
        val map = HashMap<String, Style>()
        var defR = RProps()
        var defP = PProps()
        var defaultPara: String? = null
        if (!x.toRoot()) return Styles()
        x.children { n ->
            when (n) {
                "docDefaults" -> x.children { d ->
                    when (d) {
                        "rPrDefault" -> x.children { c -> if (c == "rPr") defR = readRPr(x) }
                        "pPrDefault" -> x.children { c -> if (c == "pPr") defP = readPPr(x) }
                    }
                }
                "style" -> {
                    val id = x.attr("styleId") ?: return@children
                    val type = x.attr("type") ?: "paragraph"
                    if (x.attr("default").let { it == "1" || it == "true" } && type == "paragraph") defaultPara = id
                    val s = Style(id, type)
                    x.children { c ->
                        when (c) {
                            "name" -> s.name = x.attr("val") ?: ""
                            "basedOn" -> s.basedOn = x.attr("val")
                            "pPr" -> s.p = readPPr(x)
                            "rPr" -> s.r = readRPr(x)
                            "tblPr" -> x.children { t -> if (t == "tblBorders") s.tableBorders = readBorders(x) }
                        }
                    }
                    map[id] = s
                }
            }
        }
        return Styles(map, defR, defP, defaultPara)
    }

    /** True if any border edge is drawn. */
    private fun readBorders(x: XmlReader): Boolean {
        var any = false
        x.children { _ ->
            val v = x.attr("val")
            if (v != null && v != "none" && v != "nil") any = true
        }
        return any
    }

    // ---- numbering -------------------------------------------------------------------------

    private class Lvl(val fmt: String, val text: String, val start: Int, val indStart: Float?, val firstLine: Float?)

    private class Numbering(
        val abstracts: Map<String, Map<Int, Lvl>> = emptyMap(),
        val nums: Map<String, Num> = emptyMap(),
    )

    private class Num(val abstractId: String, val startOverrides: Map<Int, Int>, val lvlOverrides: Map<Int, Lvl>)

    private fun readLvl(x: XmlReader): Lvl {
        var fmt = "decimal"
        var text = "%1."
        var start = 1
        var ind: Float? = null
        var first: Float? = null
        x.children { c ->
            when (c) {
                "numFmt" -> fmt = x.attr("val") ?: "decimal"
                "lvlText" -> text = x.attr("val") ?: ""
                "start" -> start = x.attr("val")?.toIntOrNull() ?: 1
                "pPr" -> {
                    val p = readPPr(x)
                    ind = p.indStart
                    first = p.firstLine
                }
            }
        }
        return Lvl(fmt, text, start, ind, first)
    }

    private fun readNumbering(x: XmlReader): Numbering {
        val abstracts = HashMap<String, Map<Int, Lvl>>()
        val nums = HashMap<String, Num>()
        if (!x.toRoot()) return Numbering()
        x.children { n ->
            when (n) {
                "abstractNum" -> {
                    val id = x.attr("abstractNumId") ?: return@children
                    val lvls = HashMap<Int, Lvl>()
                    x.children { c ->
                        if (c == "lvl") {
                            val il = x.attr("ilvl")?.toIntOrNull() ?: 0
                            lvls[il] = readLvl(x)
                        }
                    }
                    abstracts[id] = lvls
                }
                "num" -> {
                    val id = x.attr("numId") ?: return@children
                    var abs: String? = null
                    val starts = HashMap<Int, Int>()
                    val lvls = HashMap<Int, Lvl>()
                    x.children { c ->
                        when (c) {
                            "abstractNumId" -> abs = x.attr("val")
                            "lvlOverride" -> {
                                val il = x.attr("ilvl")?.toIntOrNull() ?: 0
                                x.children { o ->
                                    when (o) {
                                        "startOverride" -> x.attr("val")?.toIntOrNull()?.let { starts[il] = it }
                                        "lvl" -> lvls[il] = readLvl(x)
                                    }
                                }
                            }
                        }
                    }
                    abs?.let { nums[id] = Num(it, starts, lvls) }
                }
            }
        }
        return Numbering(abstracts, nums)
    }

    private fun bulletChar(text: String): String {
        if (text.isEmpty()) return "•"
        val c = text[0]
        return when {
            c == '\uF0B7' || c == '\u00B7' -> "•"
            c == '\uF0A7' || c == '\uF06E' -> "▪"
            c == '\uF0D8' -> "➢"
            c == '\uF076' -> "❖"
            c == '\uF0FC' -> "✓"
            c == 'o' -> "◦"
            c == '-' -> "–"
            c.code in 0xF000..0xF0FF -> "•"
            else -> text
        }
    }

    internal fun formatNumber(n: Int, fmt: String): String = when (fmt) {
        "lowerLetter" -> letters(n).lowercase()
        "upperLetter" -> letters(n)
        "lowerRoman" -> roman(n).lowercase()
        "upperRoman" -> roman(n)
        "decimalZero" -> if (n in 0..9) "0$n" else n.toString()
        "ordinal" -> "$n" + when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
        "none" -> ""
        else -> n.toString()
    }

    private fun letters(n: Int): String {
        if (n <= 0) return n.toString()
        val ch = 'A' + (n - 1) % 26
        return ch.toString().repeat((n - 1) / 26 + 1)
    }

    private fun roman(n: Int): String {
        if (n <= 0 || n >= 4000) return n.toString()
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n
        val sb = StringBuilder()
        for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
        return sb.toString()
    }

    // ---- document --------------------------------------------------------------------------

    private class Field(var instr: StringBuilder = StringBuilder(), var inResult: Boolean = false, var link: String? = null)

    private class ImageBuilder {
        var embed: String? = null
        var cx: Float = 0f
        var cy: Float = 0f
        var descr: String? = null
    }

    private class Ctx(
        val zip: ZipFile,
        val styles: Styles,
        val numbering: Numbering,
        val rels: Map<String, OfficeRel>,
        val maxBlocks: Int = MAX_BLOCKS,
    ) {
        val out = ArrayList<DocBlock>()
        val images = LinkedHashMap<String, ByteArray>()
        var imageBytes = 0L
        var page = DocPageSetup()
        var blockCount = 0
        var charCount = 0
        var limitHit = false
        val fields = ArrayList<Field>()
        /** Abstract numbering id -> counters per level (Int.MIN_VALUE = not started). */
        val counters = HashMap<String, IntArray>()
        val numsSeen = HashSet<String>()
        var pendingHardBreak = false
        var pendingRenderedBreak = false

        fun countBlock() {
            blockCount++
            if (blockCount > maxBlocks || charCount > MAX_CHARS) {
                limitHit = true
                throw StopParsing()
            }
        }

        // body / cell content
        fun body(x: XmlReader, into: MutableList<DocBlock>, topLevel: Boolean) {
            x.children { n -> blockElement(x, n, into, topLevel) }
        }

        fun blockElement(x: XmlReader, n: String, into: MutableList<DocBlock>, topLevel: Boolean) {
            when (n) {
                "p" -> paragraph(x, into, topLevel)
                "tbl" -> {
                    val t = table(x)
                    if (t != null) {
                        val hard = topLevel && pendingHardBreak
                        if (topLevel) { pendingHardBreak = false; pendingRenderedBreak = false }
                        into.add(if (hard) t.copy(pageBreakBefore = true) else t)
                        countBlock()
                    }
                }
                "sdt" -> x.children { c -> if (c == "sdtContent") body(x, into, topLevel) }
                "customXml", "ins", "smartTag", "moveTo" -> body(x, into, topLevel)
                "AlternateContent" -> alternate(x) { body(x, into, topLevel) }
                "sectPr" -> if (topLevel) readSectPr(x)
            }
        }

        /** Takes the first mc:Choice, ignores the fallback. */
        inline fun alternate(x: XmlReader, block: () -> Unit) {
            var taken = false
            x.children { c ->
                if ((c == "Choice" || c == "Fallback") && !taken) {
                    taken = true
                    block()
                }
            }
        }

        fun readSectPr(x: XmlReader) {
            var p = page
            x.children { c ->
                when (c) {
                    "pgSz" -> {
                        val wv = twipsToPt(x.attr("w")) ?: p.widthPt
                        val hv = twipsToPt(x.attr("h")) ?: p.heightPt
                        if (wv in 100f..5000f && hv in 100f..5000f) p = p.copy(widthPt = wv, heightPt = hv)
                    }
                    "pgMar" -> {
                        fun m(v: String?, d: Float) = twipsToPt(v)?.let { kotlin.math.abs(it) }?.takeIf { it < 1000f } ?: d
                        p = p.copy(
                            marginLeftPt = m(x.attr("left") ?: x.attr("start"), p.marginLeftPt),
                            marginRightPt = m(x.attr("right") ?: x.attr("end"), p.marginRightPt),
                            marginTopPt = m(x.attr("top"), p.marginTopPt),
                            marginBottomPt = m(x.attr("bottom"), p.marginBottomPt),
                        )
                    }
                }
            }
            page = p
        }

        // ---- paragraph ----

        private inner class ParaBuilder(val topLevel: Boolean) {
            val segments = ArrayList<MutableList<DocRun>>().apply { add(ArrayList()) }
            val hardBreakAt = ArrayList<Boolean>().apply { add(false) }
            var renderedAtStart = false
            var renderedMid = false
            val extraBlocks = ArrayList<DocBlock>()
            var pProps = PProps()
            var paraRProps = RProps()
            var sectionBreak = false

            val current get() = segments.last()
            fun hasContent() = segments.any { s -> s.any { it is DocRun.Image || (it as? DocRun.Text)?.text?.isNotEmpty() == true } }

            fun pageBreak() {
                segments.add(ArrayList())
                hardBreakAt.add(true)
            }

            fun renderedBreak() {
                if (!topLevel) return
                if (!hasContent()) renderedAtStart = true else renderedMid = true
            }

            fun addText(text: String, r: RProps, link: String?) {
                if (text.isEmpty()) return
                charCount += text.length
                val t = if (r.caps == true) text.uppercase() else text
                val last = current.lastOrNull() as? DocRun.Text
                val run = DocRun.Text(
                    text = t,
                    bold = r.bold == true,
                    italic = r.italic == true,
                    underline = r.underline == true || (link != null && r.underline == null),
                    strike = r.strike == true,
                    sizePt = r.sizePt ?: styles.defaultSizePt,
                    colorHex = r.color ?: if (link != null && !r.colorAuto) "#0563C1" else null,
                    highlightHex = r.highlight,
                    hyperlink = link,
                    script = r.script ?: 0,
                )
                // Merge with the previous run when the formatting is identical.
                if (last != null && last.copy(text = "") == run.copy(text = "")) {
                    current[current.size - 1] = last.copy(text = last.text + run.text)
                } else {
                    current.add(run)
                }
            }
        }

        fun paragraph(x: XmlReader, into: MutableList<DocBlock>, topLevel: Boolean) {
            val pb = ParaBuilder(topLevel)
            x.children { n ->
                when (n) {
                    "pPr" -> {
                        pb.pProps = readPPr(x) { pb.sectionBreak = it }
                    }
                    else -> inline(x, n, pb, null)
                }
            }
            emitParagraph(pb, into, topLevel)
        }

        /** Paragraph-level children: runs, links, fields, revisions. */
        private fun inline(x: XmlReader, n: String, pb: ParaBuilder, link: String?) {
            when (n) {
                "r" -> run(x, pb, link)
                "hyperlink" -> {
                    val id = x.rAttr("id")
                    val anchor = x.attr("anchor")
                    val target = id?.let { rels[it]?.target } ?: anchor?.let { "#$it" } ?: link
                    x.children { c -> inline(x, c, pb, target ?: link) }
                }
                "fldSimple" -> {
                    val instr = x.attr("instr") ?: ""
                    val l = hyperlinkFromInstr(instr) ?: link
                    x.children { c -> inline(x, c, pb, l) }
                }
                "ins", "smartTag", "customXml", "moveTo", "bdo", "dir" -> x.children { c -> inline(x, c, pb, link) }
                "sdt" -> x.children { c -> if (c == "sdtContent") x.children { cc -> inline(x, cc, pb, link) } }
                "AlternateContent" -> alternate(x) { x.children { c -> inline(x, c, pb, link) } }
                // "del", "moveFrom", "proofErr", "bookmarkStart", … are ignored.
            }
        }

        private fun hyperlinkFromInstr(instr: String): String? {
            val t = instr.trim()
            if (!t.startsWith("HYPERLINK", ignoreCase = true)) return null
            val quoted = Regex("\"([^\"]*)\"").find(t)?.groupValues?.get(1)
            val anchor = Regex("\\\\l\\s+\"([^\"]*)\"").find(t)?.groupValues?.get(1)
            return when {
                anchor != null && (quoted == null || quoted == anchor) -> "#$anchor"
                quoted != null -> quoted
                else -> t.split(Regex("\\s+")).getOrNull(1)
            }
        }

        private fun resolvedRun(pb: ParaBuilder, direct: RProps?): RProps {
            val r = styles.defaultR.copy()
            val pStyle = pb.pProps.styleId ?: styles.defaultParagraphStyle
            for (s in styles.chain(pStyle)) r.overlay(s.r)
            val rStyle = direct?.styleId
            if (rStyle != null) for (s in styles.chain(rStyle)) r.overlay(s.r)
            r.overlay(direct)
            return r
        }

        private fun run(x: XmlReader, pb: ParaBuilder, link: String?) {
            var direct: RProps? = null
            var props: RProps? = null
            fun props(): RProps = props ?: resolvedRun(pb, direct).also { props = it }
            fun activeLink(): String? = fields.lastOrNull { it.inResult && it.link != null }?.link ?: link
            fun inInstr(): Boolean = fields.any { !it.inResult }
            x.children { n ->
                when (n) {
                    "rPr" -> { direct = readRPr(x); props = null }
                    "t" -> {
                        val text = x.text()
                        if (!inInstr() && props().hidden != true) pb.addText(text, props(), activeLink())
                    }
                    "instrText" -> {
                        val text = x.text()
                        fields.lastOrNull()?.takeIf { !it.inResult }?.instr?.append(text)
                    }
                    "fldChar" -> when (x.attr("fldCharType")) {
                        "begin" -> if (fields.size < 32) fields.add(Field())
                        "separate" -> fields.lastOrNull()?.let { f ->
                            f.inResult = true
                            f.link = hyperlinkFromInstr(f.instr.toString())
                        }
                        "end" -> if (fields.isNotEmpty()) fields.removeAt(fields.size - 1)
                    }
                    "tab", "ptab" -> if (!inInstr()) pb.addText("\t", props(), activeLink())
                    "br" -> when (x.attr("type")) {
                        "page" -> pb.pageBreak()
                        else -> if (!inInstr()) pb.addText("\n", props(), activeLink())
                    }
                    "cr" -> if (!inInstr()) pb.addText("\n", props(), activeLink())
                    "noBreakHyphen" -> if (!inInstr()) pb.addText("\u2011", props(), activeLink())
                    "softHyphen" -> {}
                    "sym" -> {
                        val code = x.attr("char")?.toIntOrNull(16)
                        if (code != null && !inInstr()) {
                            val ch = if (code in 0xF000..0xF0FF) bulletChar(code.toChar().toString()) else String(Character.toChars(code))
                            pb.addText(ch, props(), activeLink())
                        }
                    }
                    "lastRenderedPageBreak" -> pb.renderedBreak()
                    "drawing", "pict", "object" -> if (!inInstr()) drawing(x, pb)
                    "AlternateContent" -> alternate(x) {
                        x.children { c -> if (c == "drawing" || c == "pict") drawing(x, pb) }
                    }
                }
            }
        }

        /** Walks a w:drawing / w:pict subtree: pictures and text boxes. */
        private fun drawing(x: XmlReader, pb: ParaBuilder) {
            val img = ImageBuilder()
            fun walk() {
                x.children { n ->
                    when (n) {
                        "blip" -> { if (img.embed == null) img.embed = x.rAttr("embed") ?: x.rAttr("link"); walk() }
                        "imagedata" -> if (img.embed == null) img.embed = x.rAttr("id")
                        "extent" -> if (img.cx == 0f) {
                            img.cx = (x.attr("cx")?.toFloatOrNull() ?: 0f) / 12700f
                            img.cy = (x.attr("cy")?.toFloatOrNull() ?: 0f) / 12700f
                        }
                        "docPr" -> { img.descr = x.attr("descr")?.takeIf { it.isNotBlank() } ?: x.attr("title")?.takeIf { it.isNotBlank() } }
                        "shape", "rect", "roundrect" -> {
                            if (img.cx == 0f) x.attr("style")?.let { style ->
                                img.cx = cssPt(style, "width")
                                img.cy = cssPt(style, "height")
                            }
                            walk()
                        }
                        "txbxContent" -> body(x, pb.extraBlocks, topLevel = false)
                        "AlternateContent" -> alternate(x) { walk() }
                        else -> walk()
                    }
                }
            }
            walk()
            val embed = img.embed ?: return
            val rel = rels[embed] ?: return
            if (rel.external) return
            val path = rel.target
            if (!images.containsKey(path)) {
                val entry = zip.findEntry(path) ?: return
                if (imageBytes + entry.size > OfficeLimits.MAX_TOTAL_IMAGE_BYTES) return
                val bytes = runCatching { zip.readBytes(entry, OfficeLimits.MAX_IMAGE_BYTES) }.getOrNull() ?: return
                imageBytes += bytes.size
                images[path] = bytes
            }
            pb.current.add(DocRun.Image(path, img.cx, img.cy, img.descr))
        }

        private fun cssPt(style: String, key: String): Float {
            val m = Regex("(?:^|;)\\s*$key\\s*:\\s*([0-9.]+)\\s*(pt|px|in|cm|mm)?", RegexOption.IGNORE_CASE).find(style) ?: return 0f
            val v = m.groupValues[1].toFloatOrNull() ?: return 0f
            return when (m.groupValues[2].lowercase()) {
                "px" -> v * 0.75f
                "in" -> v * 72f
                "cm" -> v * 28.35f
                "mm" -> v * 2.835f
                else -> v
            }
        }

        private fun emitParagraph(pb: ParaBuilder, into: MutableList<DocBlock>, topLevel: Boolean) {
            val styleChain = styles.chain(pb.pProps.styleId ?: styles.defaultParagraphStyle)
            val p = PProps().overlay(styles.defaultP)
            for (s in styleChain) p.overlay(s.p)
            p.overlay(pb.pProps)
            p.styleId = pb.pProps.styleId

            // Heading level.
            var heading = 0
            val outline = pb.pProps.outline ?: styleChain.lastOrNull { it.p.outline != null }?.p?.outline
            if (outline != null && outline in 0..8) heading = outline + 1
            if (heading == 0) for (s in styleChain.asReversed()) {
                val nm = s.name.lowercase().trim()
                val id = s.id.lowercase()
                val m = Regex("^heading\\s*(\\d)$").find(nm) ?: Regex("^heading(\\d)$").find(id)
                if (m != null) { heading = m.groupValues[1].toInt().coerceIn(1, 9); break }
                if (nm == "title" || id == "title") { heading = 1; break }
            }
            // A style that isn't defined in styles.xml (styles part missing or stripped by the saving app):
            // still treat the usual ids ("Heading1", "heading 2", "Title") as headings.
            if (heading == 0 && styleChain.isEmpty()) {
                val id = pb.pProps.styleId?.lowercase()?.trim().orEmpty()
                val m = Regex("^heading\\s*(\\d)$").find(id)
                if (m != null) heading = m.groupValues[1].toInt().coerceIn(1, 9) else if (id == "title") heading = 1
            }
            val styleSetsSize = styleChain.any { it.r.sizePt != null }
            val styleSetsBold = styleChain.any { it.r.bold != null }

            // List marker.
            var marker: String? = null
            var level = 0
            var indStart = p.indStart ?: 0f
            var firstLine = p.firstLine ?: 0f
            val numId = p.numId
            if (numId != null && numId != "0") {
                val num = numbering.nums[numId]
                if (num != null) {
                    level = (p.ilvl ?: 0).coerceIn(0, 8)
                    val absLvls = numbering.abstracts[num.abstractId] ?: emptyMap()
                    fun lvl(i: Int): Lvl? = num.lvlOverrides[i] ?: absLvls[i]
                    val lv = lvl(level)
                    if (lv != null) {
                        val c = counters.getOrPut(num.abstractId) { IntArray(9) { Int.MIN_VALUE } }
                        if (numsSeen.add(numId)) {
                            // A startOverride restarts the list at that level (the start value is applied below).
                            for (il in num.startOverrides.keys) if (il in 0..8) {
                                for (d in il..8) c[d] = Int.MIN_VALUE
                            }
                        }
                        fun startOf(i: Int) = num.startOverrides[i] ?: lvl(i)?.start ?: 1
                        c[level] = if (c[level] == Int.MIN_VALUE) startOf(level) else c[level] + 1
                        for (d in level + 1..8) c[d] = Int.MIN_VALUE
                        marker = if (lv.fmt == "bullet") {
                            bulletChar(lv.text)
                        } else {
                            var t = lv.text
                            for (i in 0..8) {
                                val token = "%${i + 1}"
                                if (t.contains(token)) {
                                    val value = if (c[i] == Int.MIN_VALUE) startOf(i) else c[i]
                                    val f = lvl(i)?.fmt ?: "decimal"
                                    t = t.replace(token, formatNumber(value, if (f == "bullet") "decimal" else f))
                                }
                            }
                            t
                        }
                        if (pb.pProps.indStart == null && styleChain.none { it.p.indStart != null }) {
                            indStart = lv.indStart ?: (18f * (level + 1))
                            firstLine = lv.firstLine ?: -18f
                        }
                    }
                }
            }
            if (marker != null && marker.isEmpty()) marker = null

            // Headings with no explicit size get Word-like defaults.
            fun fixRun(r: DocRun): DocRun {
                if (heading == 0 || r !is DocRun.Text) return r
                var out = r
                if (!styleSetsSize && r.sizePt == styles.defaultSizePt) {
                    out = out.copy(sizePt = when (heading) { 1 -> 20f; 2 -> 16f; 3 -> 14f; 4 -> 13f; else -> 12f })
                }
                if (!styleSetsBold && !r.bold) out = out.copy(bold = true)
                return out
            }

            for ((i, seg) in pb.segments.withIndex()) {
                val hard = pb.hardBreakAt[i] || (i == 0 && (p.pageBreakBefore == true || (topLevel && pendingHardBreak)))
                val rendered = i == 0 && topLevel && (pb.renderedAtStart || pendingRenderedBreak)
                // A segment that is only the remainder after a page break and has no text is
                // still kept (it carries the break) unless it's the very end of the paragraph.
                val isLastEmpty = i > 0 && seg.isEmpty() && i == pb.segments.lastIndex
                if (isLastEmpty) {
                    if (topLevel) pendingHardBreak = true
                    continue
                }
                into.add(
                    DocParagraph(
                        runs = seg.map(::fixRun),
                        align = p.align ?: DocAlign.START,
                        headingLevel = heading,
                        listMarker = if (i == 0) marker else null,
                        listLevel = level,
                        indentStartPt = indStart.coerceIn(0f, 400f),
                        firstLinePt = if (i == 0) firstLine.coerceIn(-200f, 200f) else 0f,
                        spaceBeforePt = (p.spaceBefore ?: 0f).coerceIn(0f, 120f),
                        spaceAfterPt = (p.spaceAfter ?: 0f).coerceIn(0f, 120f),
                        pageBreakBefore = hard,
                        renderedPageBreakBefore = rendered,
                        styleId = p.styleId,
                    ),
                )
                if (topLevel && i == 0) { pendingHardBreak = false; pendingRenderedBreak = false }
                countBlock()
            }
            into.addAll(pb.extraBlocks)
            if (topLevel) {
                if (pb.sectionBreak) pendingHardBreak = true
                if (pb.renderedMid) pendingRenderedBreak = true
            }
        }

        // ---- table ----

        fun table(x: XmlReader): DocTable? {
            val rows = ArrayList<DocTableRow>()
            val grid = ArrayList<Float>()
            var borders: Boolean? = null
            var styleId: String? = null
            x.children { n ->
                when (n) {
                    "tblPr" -> x.children { c ->
                        when (c) {
                            "tblStyle" -> styleId = x.attr("val")
                            "tblBorders" -> borders = readBorders(x)
                        }
                    }
                    "tblGrid" -> x.children { c -> if (c == "gridCol") grid.add(twipsToPt(x.attr("w")) ?: 0f) }
                    "tr" -> row(x)?.let { rows.add(it) }
                    "sdt" -> x.children { c -> if (c == "sdtContent") x.children { cc -> if (cc == "tr") row(x)?.let { rows.add(it) } } }
                    "customXml", "ins" -> x.children { c -> if (c == "tr") row(x)?.let { rows.add(it) } }
                }
            }
            if (rows.isEmpty()) return null
            val b = borders ?: styleId?.let { id ->
                styles.chain(id).lastOrNull { it.tableBorders != null }?.tableBorders
                    ?: if (id.lowercase().contains("normal") || id.lowercase().contains("plain")) false else true
            } ?: true
            val widths = if (grid.all { it > 0f }) grid else emptyList()
            return DocTable(rows, widths, b)
        }

        private fun row(x: XmlReader): DocTableRow? {
            val cells = ArrayList<DocTableCell>()
            var header = false
            fun cell() {
                var span = 1
                var cont = false
                var fill: String? = null
                val blocks = ArrayList<DocBlock>()
                x.children { c ->
                    when (c) {
                        "tcPr" -> x.children { pc ->
                            when (pc) {
                                "gridSpan" -> span = (x.attr("val")?.toIntOrNull() ?: 1).coerceIn(1, 64)
                                "vMerge" -> cont = x.attr("val").let { it == null || it == "continue" }
                                "shd" -> fill = normalizeHex(x.attr("fill"))?.takeIf { it != "#FFFFFF" }
                            }
                        }
                        else -> blockElement(x, c, blocks, topLevel = false)
                    }
                }
                cells.add(DocTableCell(if (cont) emptyList() else blocks, span, cont, fill))
            }
            x.children { n ->
                when (n) {
                    "trPr" -> x.children { c -> if (c == "tblHeader") header = x.onOff() }
                    "tc" -> cell()
                    "sdt" -> x.children { c -> if (c == "sdtContent") x.children { cc -> if (cc == "tc") cell() } }
                    "customXml" -> x.children { c -> if (c == "tc") cell() }
                }
            }
            if (cells.isEmpty()) return null
            countBlock()
            return DocTableRow(cells, header)
        }
    }
}
