package com.clpstudio.filesmanager.viewer.office

import java.io.File

/**
 * Item 11 (F11): Word 97–2003 (.doc) opened in the app's Word viewer, read offline from the binary format:
 * text from the piece table, paragraph properties (headings, alignment, table cells / rows) and character
 * properties (bold, italic, underline, size) from the formatting pages, page breaks and field results.
 * Not reproduced: pictures, fonts, colours, exact page layout.
 */
object LegacyDoc {
    const val MAX_CHARS = 4_000_000

    fun read(file: File): DocxDocument = Cfb.open(file).use { read(it) }

    private class Piece(val cpStart: Int, val cpEnd: Int, val fc: Int, val compressed: Boolean)
    data class ParaProps(val inTable: Boolean = false, val ttp: Boolean = false, val istd: Int = 0, val jc: Int = 0)
    data class CharProps(val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false, val strike: Boolean = false, val halfPts: Int = 22)

    fun read(cfb: Cfb): DocxDocument {
        val wd = cfb.stream("WordDocument") ?: throw OfficeParseException("This isn't a Word document (.doc).")
        if (wd.size < 0x200 || (Le.u16(wd, 0) != 0xA5EC && Le.u16(wd, 0) != 0xA5DC)) throw OfficeParseException("This isn't a Word 97–2003 document.")
        val flags = Le.u16(wd, 0x0A)
        if (flags and 0x0100 != 0) throw OfficeParseException("This document is password-protected. Open it with another app.")
        val nFib = Le.u16(wd, 2)
        if (nFib < 101) {
            // Word 6 / 95: one 8-bit run between fcMin and fcMac, no formatting read.
            val fcMin = Le.i32(wd, 0x18); val fcMac = Le.i32(wd, 0x1C)
            val t = if (fcMin in 0 until fcMac && fcMac <= wd.size) String(wd, fcMin, fcMac - fcMin, charset("windows-1252")) else ""
            return DocxDocument(Builder(t, IntArray(t.length) { -1 }, { ParaProps() }, { CharProps() }).build(), emptyMap())
        }
        val table = cfb.stream(if (flags and 0x0200 != 0) "1Table" else "0Table") ?: throw OfficeParseException("The document's text table is missing.")
        val csw = Le.u16(wd, 32)
        val lwAt = 34 + csw * 2
        val cslw = Le.u16(wd, lwAt)
        val ccpText = Le.i32(wd, lwAt + 2 + 3 * 4)
        val rg = lwAt + 2 + cslw * 4 + 2
        fun fcLcb(i: Int) = Le.i32(wd, rg + i * 8) to Le.i32(wd, rg + i * 8 + 4)
        val pieces = pieces(table, fcLcb(33), ccpText)
        val sb = StringBuilder()
        val fcs = IntArray(minOf(MAX_CHARS, pieces.sumOf { it.cpEnd - it.cpStart }.coerceAtLeast(0)))
        val cp1252 = charset("windows-1252")
        loop@ for (p in pieces) {
            val n = p.cpEnd - p.cpStart
            for (k in 0 until n) {
                if (sb.length >= fcs.size) break@loop
                val fc = if (p.compressed) p.fc + k else p.fc + k * 2
                if (fc < 0 || fc + (if (p.compressed) 1 else 2) > wd.size) { continue }
                val ch = if (p.compressed) String(wd, fc, 1, cp1252)[0] else ((wd[fc].toInt() and 0xFF) or ((wd[fc + 1].toInt() and 0xFF) shl 8)).toChar()
                fcs[sb.length] = fc
                sb.append(ch)
            }
        }
        val papx = Fkp(wd, table, fcLcb(13), isPap = true)
        val chpx = Fkp(wd, table, fcLcb(12), isPap = false)
        val text = sb.toString()
        return DocxDocument(Builder(text, fcs, { fc -> papx.para(fc) }, { fc -> chpx.chars(fc) }).build(), emptyMap())
    }

    private fun pieces(table: ByteArray, clx: Pair<Int, Int>, ccpText: Int): List<Piece> {
        val (fcClx, lcbClx) = clx
        if (fcClx < 0 || lcbClx <= 0 || fcClx + lcbClx > table.size) throw OfficeParseException("The document's text table is damaged.")
        var p = fcClx
        val end = fcClx + lcbClx
        while (p < end && table[p].toInt() == 0x01) { val cb = Le.u16(table, p + 1); p += 3 + cb }
        if (p >= end || table[p].toInt() != 0x02) throw OfficeParseException("The document's text table is damaged.")
        val lcb = Le.i32(table, p + 1)
        p += 5
        val n = (lcb - 4) / 12
        if (n <= 0 || p + lcb > table.size) throw OfficeParseException("The document's text table is damaged.")
        val out = ArrayList<Piece>()
        for (i in 0 until n) {
            val a = Le.i32(table, p + i * 4)
            var b = Le.i32(table, p + (i + 1) * 4)
            if (ccpText > 0) { if (a >= ccpText) break; b = minOf(b, ccpText) }
            val raw = Le.i32(table, p + (n + 1) * 4 + i * 8 + 2)
            val compressed = raw and 0x40000000 != 0
            out.add(Piece(a, b, if (compressed) (raw and 0x3FFFFFFF) / 2 else raw and 0x3FFFFFFF, compressed))
        }
        return out
    }

    /** Formatted disk pages (PAPX or CHPX): FC ranges → their sprms. */
    private class Fkp(val wd: ByteArray, table: ByteArray, plc: Pair<Int, Int>, val isPap: Boolean) {
        private val starts = ArrayList<Int>()
        private val ends = ArrayList<Int>()
        private val grpprls = ArrayList<ByteArray?>()
        private val istds = ArrayList<Int>()

        init {
            runCatching {
                val (fc, lcb) = plc
                if (fc >= 0 && lcb > 4 && fc + lcb <= table.size) {
                    val n = (lcb - 4) / 8
                    for (i in 0 until n) {
                        val pn = Le.i32(table, fc + (n + 1) * 4 + i * 4) and 0x3FFFFF
                        val page = pn * 512
                        if (page < 0 || page + 512 > wd.size) continue
                        val crun = Le.u8(wd, page + 511)
                        for (r in 0 until crun) {
                            val a = Le.i32(wd, page + r * 4)
                            val b = Le.i32(wd, page + (r + 1) * 4)
                            val bxAt = page + (crun + 1) * 4 + r * (if (isPap) 13 else 1)
                            val off = Le.u8(wd, bxAt) * 2
                            var grp: ByteArray? = null
                            var istd = 0
                            if (off != 0) {
                                val at = page + off
                                if (isPap) {
                                    var cb = Le.u8(wd, at)
                                    var start = at + 1
                                    val size = if (cb != 0) 2 * cb - 1 else { cb = Le.u8(wd, at + 1); start = at + 2; 2 * cb }
                                    if (start + size <= page + 511 && size >= 2) {
                                        istd = Le.u16(wd, start)
                                        grp = wd.copyOfRange(start + 2, start + size)
                                    }
                                } else {
                                    val cb = Le.u8(wd, at)
                                    if (at + 1 + cb <= page + 511) grp = wd.copyOfRange(at + 1, at + 1 + cb)
                                }
                            }
                            starts.add(a); ends.add(b); grpprls.add(grp); istds.add(istd)
                        }
                    }
                }
            }
        }

        private fun find(fc: Int): Int {
            var lo = 0; var hi = starts.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                when {
                    fc < starts[mid] -> hi = mid - 1
                    fc >= ends[mid] -> lo = mid + 1
                    else -> return mid
                }
            }
            return -1
        }

        private fun sprms(g: ByteArray, each: (Int, ByteArray, Int) -> Unit) {
            var p = 0
            while (p + 2 <= g.size) {
                val sprm = Le.u16(g, p); p += 2
                val size = when (sprm ushr 13) {
                    0, 1 -> 1
                    2, 4, 5 -> 2
                    3 -> 4
                    7 -> 3
                    else -> {
                        if (p >= g.size) return
                        if (sprm == 0xD606 || sprm == 0xC615) { if (p + 2 > g.size) return; Le.u16(g, p) + 1 } else Le.u8(g, p) + 1
                    }
                }
                if (p + size > g.size) return
                each(sprm, g, p)
                p += size
            }
        }

        fun para(fc: Int): ParaProps {
            val i = if (fc < 0) -1 else find(fc)
            if (i < 0) return ParaProps()
            var inTable = false; var ttp = false; var jc = 0
            grpprls[i]?.let { g ->
                sprms(g) { s, b, o ->
                    when (s) {
                        0x2416 -> inTable = b[o].toInt() != 0
                        0x2417 -> ttp = b[o].toInt() != 0
                        0x244B -> { if (b[o].toInt() != 0) inTable = true } // sprmPFInnerTableCell
                        0x244C -> { if (b[o].toInt() != 0) ttp = true }     // sprmPFInnerTtp
                        0x2403, 0x2461 -> jc = b[o].toInt()
                    }
                }
            }
            return ParaProps(inTable, ttp, istds[i], jc)
        }

        fun chars(fc: Int): CharProps {
            val i = if (fc < 0) -1 else find(fc)
            if (i < 0) return CharProps()
            var c = CharProps()
            grpprls[i]?.let { g ->
                sprms(g) { s, b, o ->
                    val v = b[o].toInt() and 0xFF
                    // Toggle values 0x80 / 0x81 mean "same as / opposite of the style": treat 0x81 as on.
                    fun on() = v == 1 || v == 0x81
                    when (s) {
                        0x0835 -> c = c.copy(bold = on())
                        0x0836 -> c = c.copy(italic = on())
                        0x0837, 0x2A53 -> c = c.copy(strike = on())
                        0x2A3E -> c = c.copy(underline = v != 0)
                        0x4A43 -> c = c.copy(halfPts = Le.u16(b, o).coerceIn(8, 200))
                    }
                }
            }
            return c
        }
    }

    /** Text + properties → paragraphs, tables and page breaks. */
    internal class Builder(val text: String, val fcs: IntArray, val para: (Int) -> ParaProps, val chars: (Int) -> CharProps) {
        private val out = ArrayList<DocBlock>()
        private var rows = ArrayList<DocTableRow>()
        private var cells = ArrayList<DocTableCell>()
        private var cellBlocks = ArrayList<DocBlock>()
        private var runs = ArrayList<DocRun.Text>()
        private var run = StringBuilder()
        private var runProps: CharProps? = null
        private var pageBreak = false
        private val fieldStack = ArrayList<Boolean>()

        private fun fc(i: Int) = if (i < fcs.size) fcs[i] else -1

        private fun endRun() {
            val p = runProps
            if (run.isNotEmpty() && p != null) runs.add(DocRun.Text(run.toString(), bold = p.bold, italic = p.italic, underline = p.underline, strike = p.strike, sizePt = p.halfPts / 2f))
            run = StringBuilder()
        }

        private fun append(c: Char, i: Int) {
            val p = chars(fc(i))
            if (p != runProps) { endRun(); runProps = p }
            run.append(c)
        }

        private fun paragraph(props: ParaProps): DocParagraph {
            endRun()
            val heading = if (props.istd in 1..9) props.istd else 0
            val rs = if (heading > 0) runs.map { it.copy(bold = true, sizePt = maxOf(it.sizePt, 16f - heading)) } else runs.toList()
            val align = when (props.jc) { 1 -> DocAlign.CENTER; 2 -> DocAlign.END; 3, 4, 5 -> DocAlign.JUSTIFY; else -> DocAlign.START }
            val p = DocParagraph(rs, align = align, headingLevel = heading, pageBreakBefore = pageBreak, spaceAfterPt = 4f)
            runs = ArrayList(); runProps = null
            return p
        }

        private fun flushTable() {
            if (cellBlocks.isNotEmpty()) { cells.add(DocTableCell(cellBlocks)); cellBlocks = ArrayList() }
            if (cells.isNotEmpty()) { rows.add(DocTableRow(cells)); cells = ArrayList() }
            if (rows.isNotEmpty()) { out.add(DocTable(rows)); rows = ArrayList() }
        }

        fun build(): List<DocBlock> {
            for (i in text.indices) {
                val c = text[i]
                // Fields: keep only the result.
                when (c) {
                    '\u0013' -> { fieldStack.add(true); continue }
                    '\u0014' -> { if (fieldStack.isNotEmpty()) fieldStack[fieldStack.lastIndex] = false; continue }
                    '\u0015' -> { if (fieldStack.isNotEmpty()) fieldStack.removeAt(fieldStack.lastIndex); continue }
                }
                if (fieldStack.any { it } && c != '\r' && c != '\u0007') continue
                when (c) {
                    '\r', '\u0007' -> {
                        val props = para(fc(i))
                        when {
                            // Row end mark: close the row.
                            c == '\u0007' && props.ttp -> {
                                endRun(); runs = ArrayList(); runProps = null
                                if (cellBlocks.isNotEmpty()) { cells.add(DocTableCell(cellBlocks)); cellBlocks = ArrayList() }
                                if (cells.isNotEmpty()) { rows.add(DocTableRow(cells)); cells = ArrayList() }
                            }
                            c == '\u0007' -> { cellBlocks.add(paragraph(props)); cells.add(DocTableCell(cellBlocks)); cellBlocks = ArrayList() }
                            props.inTable -> cellBlocks.add(paragraph(props))
                            else -> {
                                flushTable()
                                out.add(paragraph(props)); pageBreak = false
                            }
                        }
                    }
                    '\u000C' -> { // page / section break
                        if (run.isNotEmpty() || runs.isNotEmpty()) { flushTable(); out.add(paragraph(ParaProps())) }
                        pageBreak = true
                    }
                    '\u000B' -> append('\n', i)
                    '\u001E' -> append('-', i)
                    '\t' -> append('\t', i)
                    else -> if (c >= ' ') append(c, i)
                }
            }
            if (run.isNotEmpty() || runs.isNotEmpty()) { flushTable(); out.add(paragraph(ParaProps())) }
            flushTable()
            while (out.isNotEmpty() && (out.last() as? DocParagraph)?.runs?.all { (it as? DocRun.Text)?.text.isNullOrBlank() } == true) out.removeAt(out.lastIndex)
            return out
        }
    }
}
