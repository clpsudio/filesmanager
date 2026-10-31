package com.clpstudio.filesmanager.viewer.office

import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Item 11 (F11): Excel 97–2003 (.xls, BIFF8; Excel 5/95 BIFF5 text too) opened in the app's Excel viewer, offline:
 * every sheet's values (text, numbers, dates, booleans, formula results), bold / italic, column widths, hidden
 * sheets. Not reproduced: charts, pictures, merged-cell layout, conditional formats.
 */
object LegacyXls {
    fun read(file: File): XlsxWorkbook = Cfb.open(file).use { read(it) }

    private class Rec(val type: Int, val data: ByteArray)
    private class SheetRef(val name: String, val pos: Int, val hidden: Boolean, val isWorksheet: Boolean)
    private class Font(val bold: Boolean, val italic: Boolean)

    fun read(cfb: Cfb): XlsxWorkbook {
        val book = cfb.stream("Workbook") ?: cfb.stream("Book") ?: throw OfficeParseException("This isn't an Excel workbook (.xls).")
        val biff8 = cfb.has("Workbook")
        val sst = ArrayList<String>()
        val xfFmt = ArrayList<Int>()
        val xfFont = ArrayList<Int>()
        val fonts = ArrayList<Font>()
        val formats = HashMap<Int, String>()
        val sheets = ArrayList<SheetRef>()
        var date1904 = false
        // Globals: from the first BOF to its EOF.
        val globals = records(book, 0)
        var i = 0
        while (i < globals.size) {
            val r = globals[i]
            val d = r.data
            when (r.type) {
                0x002F -> throw OfficeParseException("This workbook is password-protected. Open it with another app.")
                0x0085 -> {
                    val pos = Le.i32(d, 0)
                    val hidden = Le.u8(d, 4) != 0
                    val dt = Le.u8(d, 5)
                    val name = if (biff8) shortString(d, 6) else String(d, 7, Le.u8(d, 6).coerceAtMost(d.size - 7), charset("windows-1252"))
                    sheets.add(SheetRef(name, pos, hidden, dt == 0))
                }
                0x00FC -> {
                    val parts = ArrayList<ByteArray>().apply { add(d) }
                    while (i + 1 < globals.size && globals[i + 1].type == 0x003C) { i++; parts.add(globals[i].data) }
                    readSst(parts, sst)
                }
                0x00E0 -> { xfFont.add(Le.u16(d, 0)); xfFmt.add(Le.u16(d, 2)) }
                0x041E -> if (d.size > 4) formats[Le.u16(d, 0)] = if (biff8) unicodeString(d, 2).first else String(d, 3, Le.u8(d, 2).coerceAtMost(d.size - 3), charset("windows-1252"))
                0x0031 -> if (d.size >= 8) fonts.add(Font(Le.u16(d, 6) >= 700, Le.u16(d, 2) and 0x02 != 0))
                0x0022 -> date1904 = Le.u16(d, 0) == 1
            }
            i++
        }
        fun isDate(xf: Int): Boolean {
            val f = xfFmt.getOrNull(xf) ?: return false
            if (f in 14..22 || f in 45..47) return true
            val s = formats[f] ?: return false
            val clean = s.replace(Regex("\"[^\"]*\"|\\[[^]]*]|\\\\."), "").lowercase(Locale.ROOT)
            return clean != "general" && clean.any { it == 'y' || it == 'd' } || (clean.contains('h') && clean.contains('m') && !clean.contains('0'))
        }
        fun isPercent(xf: Int): Boolean { val f = xfFmt.getOrNull(xf) ?: return false; return f == 9 || f == 10 || formats[f]?.contains('%') == true }
        fun font(xf: Int): Font? = xfFont.getOrNull(xf)?.let { fi -> fonts.getOrNull(if (fi >= 4) fi - 1 else fi) } // font index 4 is skipped
        fun num(v: Double, xf: Int): XlsxCell {
            val f = font(xf)
            val text = when {
                isDate(xf) -> date(v, date1904)
                isPercent(xf) -> plain(v * 100) + "%"
                else -> plain(v)
            }
            return XlsxCell(text, isNumber = true, bold = f?.bold == true, italic = f?.italic == true)
        }
        fun str(s: String, xf: Int): XlsxCell { val f = font(xf); return XlsxCell(s, bold = f?.bold == true, italic = f?.italic == true) }

        val out = ArrayList<XlsxSheet>()
        for (sh in sheets) {
            if (!sh.isWorksheet || sh.pos <= 0 || sh.pos >= book.size) continue
            val cells = HashMap<Int, HashMap<Int, XlsxCell>>()
            val widths = HashMap<Int, Float>()
            var maxRow = -1; var maxCol = -1
            var truncated = false
            fun put(r: Int, c: Int, cell: XlsxCell) {
                if (r >= XlsxParser.MAX_ROWS || c >= XlsxParser.MAX_COLS) { truncated = true; return }
                cells.getOrPut(r) { HashMap() }[c] = cell
                if (r > maxRow) maxRow = r
                if (c > maxCol) maxCol = c
            }
            var pendingFormula: Pair<Int, Int>? = null
            var pendingXf = 0
            for (r in records(book, sh.pos)) {
                val d = r.data
                when (r.type) {
                    0x00FD -> if (d.size >= 10) put(Le.u16(d, 0), Le.u16(d, 2), str(sst.getOrElse(Le.i32(d, 6)) { "" }, Le.u16(d, 4)))
                    0x0204, 0x00D6 -> if (d.size >= 8) put(Le.u16(d, 0), Le.u16(d, 2), str(
                        if (biff8) unicodeString(d, 6).first else String(d, 8, Le.u16(d, 6).coerceAtMost(d.size - 8), charset("windows-1252")), Le.u16(d, 4)))
                    0x0203 -> if (d.size >= 14) put(Le.u16(d, 0), Le.u16(d, 2), num(Le.f64(d, 6), Le.u16(d, 4)))
                    0x027E -> if (d.size >= 10) put(Le.u16(d, 0), Le.u16(d, 2), num(rk(Le.i32(d, 6)), Le.u16(d, 4)))
                    0x00BD -> if (d.size >= 12) {
                        val row = Le.u16(d, 0); val first = Le.u16(d, 2)
                        val n = (d.size - 6) / 6
                        for (k in 0 until n) put(row, first + k, num(rk(Le.i32(d, 4 + k * 6 + 2)), Le.u16(d, 4 + k * 6)))
                    }
                    0x0006 -> if (d.size >= 14) {
                        val row = Le.u16(d, 0); val col = Le.u16(d, 2); val xf = Le.u16(d, 4)
                        if (Le.u16(d, 12) == 0xFFFF) when (Le.u8(d, 6)) {
                            0 -> { pendingFormula = row to col; pendingXf = xf }
                            1 -> put(row, col, str(if (Le.u8(d, 8) != 0) "TRUE" else "FALSE", xf))
                            2 -> put(row, col, str(errorText(Le.u8(d, 8)), xf))
                            else -> Unit
                        } else put(row, col, num(Le.f64(d, 6), xf))
                    }
                    0x0207 -> pendingFormula?.let { (row, col) ->
                        put(row, col, str(if (biff8) unicodeString(d, 0).first else String(d, 2, Le.u16(d, 0).coerceAtMost(d.size - 2), charset("windows-1252")), pendingXf))
                        pendingFormula = null
                    }
                    0x0205 -> if (d.size >= 8) put(Le.u16(d, 0), Le.u16(d, 2), str(if (Le.u8(d, 7) != 0) errorText(Le.u8(d, 6)) else if (Le.u8(d, 6) != 0) "TRUE" else "FALSE", Le.u16(d, 4)))
                    0x007D -> if (d.size >= 10) {
                        val a = Le.u16(d, 0); val b = minOf(Le.u16(d, 2), 255)
                        val hidden = Le.u16(d, 8) and 0x01 != 0
                        for (c in a..b) widths[c] = if (hidden) 0f else Le.u16(d, 4) / 256f
                    }
                }
            }
            val rows = (0..maxRow).map { r -> cells[r]?.toMap() ?: emptyMap() }
            out.add(XlsxSheet(sh.name, rows, maxCol + 1, widths, hidden = sh.hidden, truncated = truncated))
        }
        if (out.isEmpty()) throw OfficeParseException("No sheets could be read in this workbook.")
        return XlsxWorkbook(out)
    }

    /** Records from the BOF at [start] to its EOF. */
    private fun records(b: ByteArray, start: Int): List<Rec> {
        val out = ArrayList<Rec>()
        var p = start
        var first = true
        while (p + 4 <= b.size) {
            val type = Le.u16(b, p); val len = Le.u16(b, p + 2)
            if (p + 4 + len > b.size) break
            if (first && type != 0x0809 && type != 0x0409 && type != 0x0209 && type != 0x0009) break
            first = false
            out.add(Rec(type, b.copyOfRange(p + 4, p + 4 + len)))
            p += 4 + len
            if (type == 0x000A) break
            if (out.size > 2_000_000) break
        }
        return out
    }

    /** XLUnicodeString at [o]: (text, bytes used). */
    private fun unicodeString(d: ByteArray, o: Int): Pair<String, Int> {
        if (o + 3 > d.size) return "" to 0
        val cch = Le.u16(d, o)
        val high = Le.u8(d, o + 2) and 0x01 != 0
        val n = if (high) cch * 2 else cch
        val avail = (d.size - o - 3).coerceAtLeast(0)
        val len = minOf(n, avail)
        val s = if (high) String(d, o + 3, len - len % 2, Charsets.UTF_16LE) else String(d, o + 3, len, Charsets.ISO_8859_1)
        return s to 3 + len
    }

    private fun shortString(d: ByteArray, o: Int): String {
        if (o + 2 > d.size) return ""
        val cch = Le.u8(d, o)
        val high = Le.u8(d, o + 1) and 0x01 != 0
        val len = minOf(if (high) cch * 2 else cch, d.size - o - 2)
        return if (high) String(d, o + 2, len - len % 2, Charsets.UTF_16LE) else String(d, o + 2, len, Charsets.ISO_8859_1)
    }

    /** The shared strings, across CONTINUE records (a string's characters can restart there with a new width flag). */
    private fun readSst(parts: List<ByteArray>, out: MutableList<String>) {
        var part = 0
        var p = 8
        fun cur() = parts[part]
        fun need(n: Int): Boolean {
            while (part < parts.size && p + n > cur().size) {
                if (p < cur().size) return false // a value split across records: not expected for fixed fields
                part++; p = 0
            }
            return part < parts.size
        }
        val unique = Le.i32(parts[0], 4)
        repeat(minOf(unique, 2_000_000)) {
            if (!need(3)) return
            val cch = Le.u16(cur(), p)
            var flags = Le.u8(cur(), p + 2)
            p += 3
            var runs = 0; var ext = 0
            if (flags and 0x08 != 0) { if (!need(2)) return; runs = Le.u16(cur(), p); p += 2 }
            if (flags and 0x04 != 0) { if (!need(4)) return; ext = Le.i32(cur(), p); p += 4 }
            val sb = StringBuilder(cch)
            var left = cch
            while (left > 0) {
                if (p >= cur().size) {
                    part++; if (part >= parts.size) { out.add(sb.toString()); return }
                    p = 0
                    flags = Le.u8(cur(), p); p += 1 // the continued characters' width
                }
                val high = flags and 0x01 != 0
                val per = if (high) 2 else 1
                val can = minOf(left, (cur().size - p) / per)
                if (can <= 0) { p = cur().size; continue }
                sb.append(if (high) String(cur(), p, can * 2, Charsets.UTF_16LE) else String(cur(), p, can, Charsets.ISO_8859_1))
                p += can * per
                left -= can
            }
            out.add(sb.toString())
            // Skip formatting runs and the extended block (may cross records).
            var skip = runs * 4L + ext
            while (skip > 0) {
                if (p >= cur().size) { part++; if (part >= parts.size) return; p = 0 }
                val s = minOf(skip, (cur().size - p).toLong()).toInt()
                p += s; skip -= s
            }
        }
    }

    internal fun rk(v: Int): Double {
        val div100 = v and 0x01 != 0
        val d = if (v and 0x02 != 0) (v shr 2).toDouble() else java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
        return if (div100) d / 100.0 else d
    }

    internal fun plain(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return v.toString()
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) return v.toLong().toString()
        val s = java.math.BigDecimal(v).round(java.math.MathContext(10)).stripTrailingZeros().toPlainString()
        return s
    }

    internal fun date(v: Double, date1904: Boolean): String {
        if (v < 0 || v > 2958465) return plain(v)
        val days = kotlin.math.floor(v).toLong()
        val frac = v - days
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        if (date1904) cal.set(1904, Calendar.JANUARY, 1) else {
            // Excel's 1900 system counts a 29 Feb 1900 that never was.
            cal.set(1899, Calendar.DECEMBER, if (days < 61) 31 else 30)
        }
        cal.add(Calendar.DAY_OF_MONTH, days.toInt())
        val ms = Math.round(frac * 86_400_000.0)
        cal.add(Calendar.MILLISECOND, ms.toInt())
        val f = when {
            days == 0L && frac > 0 -> "HH:mm"
            frac > 1e-9 -> "yyyy-MM-dd HH:mm"
            else -> "yyyy-MM-dd"
        }
        return SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(cal.time)
    }

    private fun errorText(code: Int) = when (code) {
        0x00 -> "#NULL!"; 0x07 -> "#DIV/0!"; 0x0F -> "#VALUE!"; 0x17 -> "#REF!"; 0x1D -> "#NAME?"; 0x24 -> "#NUM!"; 0x2A -> "#N/A"; else -> "#ERR"
    }
}
