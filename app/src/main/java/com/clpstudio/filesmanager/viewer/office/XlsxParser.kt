package com.clpstudio.filesmanager.viewer.office

import java.io.File
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

data class XlsxCell(
    val text: String,
    val isNumber: Boolean = false,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val colorHex: String? = null,
    val fillHex: String? = null,
    /** null = Excel's default (numbers right, text left). */
    val align: DocAlign? = null,
)

data class XlsxSheet(
    val name: String,
    /** Dense rows 0..[rowCount]-1; each row maps column index -> cell. */
    val rows: List<Map<Int, XlsxCell>>,
    val columnCount: Int,
    /** Column index -> width in Excel character units. */
    val columnWidths: Map<Int, Float> = emptyMap(),
    val defaultColumnWidth: Float = 8.43f,
    /** Row index -> height in points. */
    val rowHeights: Map<Int, Float> = emptyMap(),
    val hidden: Boolean = false,
    val truncated: Boolean = false,
) {
    val rowCount: Int get() = rows.size
    fun cell(row: Int, col: Int): XlsxCell? = rows.getOrNull(row)?.get(col)
}

data class XlsxWorkbook(val sheets: List<XlsxSheet>, val warnings: List<String> = emptyList())

/** Offline .xlsx reader (java.util.zip + XmlPullParser). */
object XlsxParser {
    const val MAX_ROWS = 10_000
    const val MAX_COLS = 256

    fun parse(file: File): XlsxWorkbook {
        // Item 11 (F11): the 97–2003 binary format (.doc / .xls / .ppt) goes to its own reader, same viewer.
        if (Cfb.isCfb(file)) return LegacyXls.read(file)
        val zip = try {
            ZipFile(file)
        } catch (e: Exception) {
            throw OfficeParseException("This file isn't a valid Excel workbook (.xlsx).", e)
        }
        return zip.use { parse(it) }
    }

    private class Xf(val numFmt: Int, val fontId: Int, val fillId: Int, val align: DocAlign?)
    private class Font(val bold: Boolean, val italic: Boolean, val color: String?)
    private class Styles(
        val xfs: List<Xf> = emptyList(),
        val fonts: List<Font> = emptyList(),
        val fills: List<String?> = emptyList(),
        val numFmts: Map<Int, String> = emptyMap(),
    )

    fun parse(zip: ZipFile): XlsxWorkbook {
        val rootRels = zip.readRels("_rels/.rels", "")
        val wbPath = rootRels.values.firstOrNull { it.type.endsWith("/officeDocument") && !it.external }?.target
            ?.takeIf { zip.findEntry(it) != null }
            ?: "xl/workbook.xml".takeIf { zip.findEntry(it) != null }
            ?: throw OfficeParseException("This file isn't an Excel workbook: xl/workbook.xml is missing.")
        val base = zipDir(wbPath)
        val rels = zip.readRels(relsPathFor(wbPath), base)
        val warnings = ArrayList<String>()

        data class SheetRef(val name: String, val rid: String?, val hidden: Boolean)
        val refs = ArrayList<SheetRef>()
        var date1904 = false
        try {
            zip.parseXml(wbPath) { x ->
                if (!x.toRoot()) return@parseXml
                x.children { n ->
                    when (n) {
                        "workbookPr" -> date1904 = x.attr("date1904").let { it == "1" || it == "true" }
                        "sheets" -> x.children { s ->
                            if (s == "sheet") refs.add(SheetRef(x.attr("name") ?: "Sheet${refs.size + 1}", x.rAttr("id"), x.attr("state")?.let { it != "visible" } ?: false))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            throw OfficeParseException("This Excel workbook is damaged and couldn't be read.", e)
        }

        val sstPath = rels.values.firstOrNull { it.type.endsWith("/sharedStrings") }?.target ?: "${base}sharedStrings.xml"
        val shared = runCatching { zip.parseXml(sstPath) { readSharedStrings(it) } }.onFailure { warnings.add("sharedStrings") }.getOrNull() ?: emptyList()
        val stylesPath = rels.values.firstOrNull { it.type.endsWith("/styles") }?.target ?: "${base}styles.xml"
        val styles = runCatching { zip.parseXml(stylesPath) { readStyles(it) } }.onFailure { warnings.add("styles") }.getOrNull() ?: Styles()

        val sheets = ArrayList<XlsxSheet>()
        refs.forEachIndexed { i, ref ->
            val path = ref.rid?.let { rels[it]?.target } ?: "${base}worksheets/sheet${i + 1}.xml"
            if (rels[ref.rid]?.type?.endsWith("/chartsheet") == true) return@forEachIndexed
            val sheet = try {
                zip.parseXml(path) { readSheet(it, ref.name, shared, styles, date1904) }
            } catch (e: Exception) {
                warnings.add(ref.name)
                null
            }
            if (sheet != null) sheets.add(sheet.copy(hidden = ref.hidden))
        }
        if (sheets.isEmpty()) throw OfficeParseException("This Excel workbook has no sheets that can be shown.")
        // Hidden sheets go last.
        return XlsxWorkbook(sheets.sortedBy { it.hidden }, warnings)
    }

    private fun readSharedStrings(x: XmlReader): List<String> {
        val out = ArrayList<String>()
        if (!x.toRoot()) return out
        x.children { n -> if (n == "si") out.add(richText(x)) }
        return out
    }

    /** Text of an si / is element: t, or r/t, skipping phonetic runs. */
    private fun richText(x: XmlReader): String {
        val sb = StringBuilder()
        x.children { c ->
            when (c) {
                "t" -> sb.append(x.text())
                "r" -> x.children { rc -> if (rc == "t") sb.append(x.text()) }
            }
        }
        return sb.toString()
    }

    private fun readStyles(x: XmlReader): Styles {
        val numFmts = HashMap<Int, String>()
        val fonts = ArrayList<Font>()
        val fills = ArrayList<String?>()
        val xfs = ArrayList<Xf>()
        if (!x.toRoot()) return Styles()
        x.children { n ->
            when (n) {
                "numFmts" -> x.children { c ->
                    if (c == "numFmt") {
                        val id = x.attr("numFmtId")?.toIntOrNull()
                        val code = x.attr("formatCode")
                        if (id != null && code != null) numFmts[id] = code
                    }
                }
                "fonts" -> x.children { c ->
                    if (c == "font") {
                        var b = false
                        var i = false
                        var color: String? = null
                        x.children { f ->
                            when (f) {
                                "b" -> b = x.onOff()
                                "i" -> i = x.onOff()
                                "color" -> color = argb(x.attr("rgb"))
                            }
                        }
                        fonts.add(Font(b, i, color))
                    }
                }
                "fills" -> x.children { c ->
                    if (c == "fill") {
                        var color: String? = null
                        x.children { f ->
                            if (f == "patternFill") {
                                val solid = x.attr("patternType") == "solid"
                                x.children { pf -> if (pf == "fgColor" && solid) color = argb(x.attr("rgb")) }
                            }
                        }
                        fills.add(color)
                    }
                }
                "cellXfs" -> x.children { c ->
                    if (c == "xf") {
                        val numFmt = x.attr("numFmtId")?.toIntOrNull() ?: 0
                        val font = x.attr("fontId")?.toIntOrNull() ?: 0
                        val fill = x.attr("fillId")?.toIntOrNull() ?: 0
                        var align: DocAlign? = null
                        x.children { a ->
                            if (a == "alignment") align = when (x.attr("horizontal")) {
                                "center", "centerContinuous" -> DocAlign.CENTER
                                "right" -> DocAlign.END
                                "left" -> DocAlign.START
                                "justify", "distributed" -> DocAlign.JUSTIFY
                                else -> null
                            }
                        }
                        xfs.add(Xf(numFmt, font, fill, align))
                    }
                }
            }
        }
        return Styles(xfs, fonts, fills, numFmts)
    }

    private fun argb(v: String?): String? {
        if (v == null) return null
        val s = v.trim()
        return normalizeHex(if (s.length == 8) s.substring(2) else s)
    }

    /** "A1" -> (row 0, col 0). */
    internal fun cellRef(ref: String): Pair<Int, Int>? {
        var col = 0
        var i = 0
        while (i < ref.length && ref[i].isLetter()) {
            col = col * 26 + (ref[i].uppercaseChar() - 'A' + 1)
            i++
        }
        val row = ref.substring(i).toIntOrNull() ?: return null
        if (col == 0 || row <= 0) return null
        return (row - 1) to (col - 1)
    }

    fun columnName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val m = (n - 1) % 26
            sb.insert(0, 'A' + m)
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun readSheet(x: XmlReader, name: String, shared: List<String>, styles: Styles, date1904: Boolean): XlsxSheet {
        val rows = HashMap<Int, HashMap<Int, XlsxCell>>()
        val widths = HashMap<Int, Float>()
        val heights = HashMap<Int, Float>()
        var defaultWidth = 8.43f
        var maxRow = -1
        var maxCol = -1
        var truncated = false
        if (!x.toRoot()) return XlsxSheet(name, emptyList(), 0)
        x.children { n ->
            when (n) {
                "sheetFormatPr" -> x.attr("defaultColWidth")?.toFloatOrNull()?.let { defaultWidth = it }
                "cols" -> x.children { c ->
                    if (c == "col") {
                        val min = (x.attr("min")?.toIntOrNull() ?: 1) - 1
                        val max = minOf((x.attr("max")?.toIntOrNull() ?: 1) - 1, MAX_COLS - 1)
                        val hidden = x.attr("hidden").let { it == "1" || it == "true" }
                        val wv = if (hidden) 0f else x.attr("width")?.toFloatOrNull()
                        if (wv != null) for (i in min..max) widths[i] = wv
                    }
                }
                "sheetData" -> {
                    var nextRow = 0
                    x.children { r ->
                        if (r != "row") return@children
                        val rowIndex = x.attr("r")?.toIntOrNull()?.minus(1) ?: nextRow
                        nextRow = rowIndex + 1
                        if (rowIndex >= MAX_ROWS) { truncated = true; return@children }
                        x.attr("ht")?.toFloatOrNull()?.let { heights[rowIndex] = it }
                        var nextCol = 0
                        x.children { c ->
                            if (c != "c") return@children
                            val pos = x.attr("r")?.let { cellRef(it) }
                            val col = pos?.second ?: nextCol
                            nextCol = col + 1
                            if (col >= MAX_COLS) { truncated = true; return@children }
                            val type = x.attr("t") ?: "n"
                            val style = x.attr("s")?.toIntOrNull() ?: 0
                            var v: String? = null
                            var inline: String? = null
                            x.children { cc ->
                                when (cc) {
                                    "v" -> v = x.text()
                                    "is" -> inline = richText(x)
                                }
                            }
                            val cell = makeCell(type, v, inline, style, shared, styles, date1904) ?: return@children
                            rows.getOrPut(rowIndex) { HashMap() }[col] = cell
                            if (rowIndex > maxRow) maxRow = rowIndex
                            if (col > maxCol) maxCol = col
                        }
                    }
                }
            }
        }
        val dense = List(maxRow + 1) { rows[it] ?: emptyMap<Int, XlsxCell>() }
        return XlsxSheet(name, dense, maxCol + 1, widths, defaultWidth, heights, truncated = truncated)
    }

    private fun makeCell(type: String, v: String?, inline: String?, styleIndex: Int, shared: List<String>, styles: Styles, date1904: Boolean): XlsxCell? {
        val xf = styles.xfs.getOrNull(styleIndex)
        val font = xf?.let { styles.fonts.getOrNull(it.fontId) }
        val fill = xf?.let { styles.fills.getOrNull(it.fillId) }
        var isNumber = false
        val text: String = when (type) {
            "s" -> v?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
            "inlineStr" -> inline ?: v ?: ""
            "str" -> v ?: ""
            "b" -> if (v?.trim() == "1") "TRUE" else "FALSE"
            "e" -> v ?: "#ERROR"
            "d" -> v ?: ""
            else -> {
                val d = v?.trim()?.toDoubleOrNull()
                if (d == null) {
                    v ?: ""
                } else {
                    isNumber = true
                    formatNumber(d, xf?.numFmt ?: 0, styles.numFmts, date1904)
                }
            }
        }
        if (text.isEmpty() && fill == null) return null
        return XlsxCell(
            text = text,
            isNumber = isNumber,
            bold = font?.bold == true,
            italic = font?.italic == true,
            colorHex = font?.color?.takeIf { it != "#000000" },
            fillHex = fill?.takeIf { it != "#FFFFFF" },
            align = xf?.align,
        )
    }

    private val builtInFormats = mapOf(
        1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00", 9 to "0%", 10 to "0.00%", 11 to "0.00E+00",
        14 to "dd/mm/yyyy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy", 18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM",
        20 to "h:mm", 21 to "h:mm:ss", 22 to "dd/mm/yyyy h:mm", 37 to "#,##0", 38 to "#,##0", 39 to "#,##0.00", 40 to "#,##0.00",
        45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mm:ss.0", 49 to "@",
    )

    internal fun formatNumber(d: Double, fmtId: Int, custom: Map<Int, String>, date1904: Boolean = false): String {
        val code = (custom[fmtId] ?: builtInFormats[fmtId] ?: "General").split(';').first()
        val unbracketed = code.replace(Regex("\\[\\$([^\\]-]*)[^\\]]*]"), "$1").replace(Regex("\\[[^\\]]*]"), "")
        // Quoted literals stay as text in the output, but never count as date letters.
        val clean = unbracketed.replace(Regex("\"([^\"]*)\""), "$1").replace("\\", "").replace("_)", "").trim()
        val lower = unbracketed.replace(Regex("\"[^\"]*\""), "").lowercase()
        val isDate = fmtId in 14..22 || fmtId in 45..47 ||
            (lower.contains(Regex("[dy]")) && !lower.contains("general")) || (lower.contains("h") && lower.contains("m") && !lower.contains("0"))
        if (isDate && d >= 0 && d < 2958466) return formatDate(d, lower, date1904)
        if (clean.isEmpty() || lower == "general" || clean == "@") return general(d)
        return try {
            if (clean.endsWith("%")) {
                val decimals = clean.substringAfter('.', "").count { it == '0' }
                decimalFormat(if (decimals > 0) "0." + "0".repeat(decimals) else "0").format(d * 100) + "%"
            } else if (clean.contains("E+", ignoreCase = true)) {
                val s = String.format(Locale.US, "%.2E", d)
                s
            } else {
                val pattern = clean.filter { it in "0#,.-" }.ifEmpty { "0" }
                val prefix = clean.takeWhile { it !in "0#" }.filter { it !in ",.-" && it != '?' && it != '*' }
                val suffix = clean.takeLastWhile { it !in "0#" }.filter { it != '?' && it != '*' }.trim()
                val formatted = decimalFormat(pattern).format(abs(d))
                (if (d < 0) "-" else "") + prefix.trim() + formatted + suffix
            }
        } catch (_: Exception) {
            general(d)
        }
    }

    private fun decimalFormat(pattern: String) = DecimalFormat(pattern, DecimalFormatSymbols(Locale.US))

    private fun general(d: Double): String {
        if (d == floor(d) && abs(d) < 1e15) return d.roundToLong().toString()
        val bd = BigDecimal(d).round(java.math.MathContext(11)).stripTrailingZeros()
        return if (abs(d) >= 1e11 || (abs(d) < 1e-9 && d != 0.0)) String.format(Locale.US, "%.5E", d) else bd.toPlainString()
    }

    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private fun formatDate(serial: Double, fmt: String, date1904: Boolean): String {
        var days = floor(serial).toLong()
        val secondsOfDay = ((serial - floor(serial)) * 86400).roundToLong().coerceIn(0, 86399)
        // Excel serial -> epoch day (1900 system has the fake 29 Feb 1900).
        val epochDay = if (date1904) days - 24107 else {
            if (days < 61) days += 1
            days - 25569
        }
        val date = java.time.LocalDate.ofEpochDay(epochDay)
        val hasDate = fmt.contains('d') || fmt.contains('y')
        val hasTime = fmt.contains('h') || fmt.contains('s')
        val h = secondsOfDay / 3600
        val m = (secondsOfDay % 3600) / 60
        val s = secondsOfDay % 60
        val time = if (fmt.contains("am/pm")) {
            String.format(Locale.US, "%d:%02d %s", if (h % 12 == 0L) 12 else h % 12, m, if (h < 12) "AM" else "PM")
        } else if (fmt.contains('s')) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", h, m)
        }
        val datePart = when {
            fmt.trimStart().startsWith("y") -> String.format(Locale.US, "%04d-%02d-%02d", date.year, date.monthValue, date.dayOfMonth)
            fmt.contains("mmm") -> "${date.dayOfMonth} ${MONTHS[date.monthValue - 1]} ${date.year}"
            fmt.indexOf('m') in 0 until (fmt.indexOf('d').takeIf { it >= 0 } ?: Int.MAX_VALUE) ->
                String.format(Locale.US, "%02d/%02d/%04d", date.monthValue, date.dayOfMonth, date.year)
            else -> String.format(Locale.US, "%02d/%02d/%04d", date.dayOfMonth, date.monthValue, date.year)
        }
        return when {
            hasDate && hasTime -> "$datePart $time"
            hasTime && !hasDate -> time
            else -> datePart
        }
    }
}
