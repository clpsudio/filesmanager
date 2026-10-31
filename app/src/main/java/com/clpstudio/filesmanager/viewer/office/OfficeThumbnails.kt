package com.clpstudio.filesmanager.viewer.office

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * List / grid thumbnails for Office files, fully offline:
 *  1. the picture the saving app stored in the file (docProps/thumbnail.*), when there is one;
 *  2. for files without one, a small snapshot: Word = the first page, Excel = the top-left of the first sheet
 *     (grid, real values), PowerPoint = the first slide (title, text, first picture) — item 10 (F11).
 */
object OfficeThumbnails {
    /** Blocks parsed for a snapshot: plenty for a first page, cheap for a huge document. */
    private const val SNAPSHOT_BLOCKS = 400
    const val SNAPSHOT_MAX_BYTES = 40L shl 20

    fun load(file: File, format: OfficeFormat?, maxPx: Int): Bitmap? {
        val fmt = format ?: OfficeFormats.sniff(file) ?: return null
        // Item 10/11: 97–2003 files have no stored thumbnail here; draw the same snapshot from the binary reader.
        if (Cfb.isCfb(file)) return if (file.length() > SNAPSHOT_MAX_BYTES) null else runCatching {
            when (fmt) {
                OfficeFormat.DOCX -> DocxSnapshot.render(LegacyDoc.read(file), maxPx)
                OfficeFormat.XLSX -> XlsxSnapshot.render(LegacyXls.read(file), maxPx)
                OfficeFormat.PPTX -> PptxSnapshot.render(LegacyPpt.read(file), maxPx)
            }
        }.getOrNull()
        val zip = try { ZipFile(file) } catch (e: Exception) { return null }
        return zip.use { z ->
            embedded(z, maxPx) ?: when (fmt) {
                OfficeFormat.DOCX -> runCatching { DocxSnapshot.render(DocxParser.parse(z, maxBlocks = SNAPSHOT_BLOCKS), maxPx) }.getOrNull()
                // Big workbooks / decks: the full parse costs too much for a list thumbnail.
                OfficeFormat.XLSX -> if (file.length() > SNAPSHOT_MAX_BYTES) null else runCatching { XlsxSnapshot.render(XlsxParser.parse(z), maxPx) }.getOrNull()
                OfficeFormat.PPTX -> if (file.length() > SNAPSHOT_MAX_BYTES) null else runCatching { PptxSnapshot.render(PptxParser.parse(z), maxPx) }.getOrNull()
            }
        }
    }

    /** docProps/thumbnail.* (named by the package relationship, else by the usual path). PNG / JPEG / GIF / BMP / WebP. */
    internal fun embedded(zip: ZipFile, maxPx: Int): Bitmap? {
        val rels = zip.readRels("_rels/.rels", "")
        val candidates = ArrayList<String>()
        rels.values.filter { !it.external && it.type.endsWith("/thumbnail") }.forEach { candidates.add(it.target) }
        for (ext in listOf("png", "jpeg", "jpg", "gif", "bmp", "webp")) candidates.add("docProps/thumbnail.$ext")
        for (path in candidates.distinct()) {
            val entry = zip.findEntry(path) ?: continue
            if (entry.size > OfficeLimits.MAX_IMAGE_BYTES) continue
            val bytes = runCatching { zip.readBytes(entry, OfficeLimits.MAX_IMAGE_BYTES) }.getOrNull() ?: continue
            // WMF / EMF thumbnails (old Word) don't decode: fall through to the snapshot.
            val bmp = decodeOfficeImage(bytes, maxPx) ?: continue
            if (bmp.width < 8 || bmp.height < 8) continue
            return bmp
        }
        return null
    }
}

/** Draws the first page of a parsed Word document onto a small white bitmap. */
object DocxSnapshot {
    private const val INK = 0xFF111111.toInt()
    private const val OUTLINE = 0xFFD9DCE1.toInt()

    fun render(doc: DocxDocument, widthPx: Int = 256): Bitmap {
        val w = widthPx.coerceIn(64, 1024)
        val ratio = (doc.page.heightPt / doc.page.widthPt).coerceIn(0.5f, 2f)
        val h = (w * ratio).roundToInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val ptPx = w / doc.page.widthPt
        // Narrower margins and slightly larger text than the real page, so a thumbnail reads as a document.
        val margin = max(doc.page.marginLeftPt * ptPx * 0.6f, w * 0.07f)
        val s = Snap(c, doc, textScale = ptPx * 1.25f, maxImagePx = w)
        val first = doc.pages().firstOrNull().orEmpty()
        var y = margin
        val bottom = h - margin * 0.6f
        c.save()
        c.clipRect(0f, 0f, w.toFloat(), bottom)
        for (b in first) {
            if (y >= bottom) break
            y = s.block(b, margin, y, w - 2 * margin)
        }
        c.restore()
        val outline = Paint().apply { color = OUTLINE; style = Paint.Style.STROKE; strokeWidth = max(1f, w / 256f) }
        c.drawRect(RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), outline)
        return bmp
    }

    private data class CellDraw(val x: Float, val width: Float, val layouts: List<StaticLayout>, val fill: Int?)

    private class Snap(val c: Canvas, val doc: DocxDocument, val textScale: Float, val maxImagePx: Int) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; textSize = doc.defaultSizePt * textScale }
        val grid = Paint().apply { color = 0xFFBFC4CC.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f }
        val fill = Paint()

        fun block(b: DocBlock, x: Float, y: Float, width: Float): Float = when (b) {
            is DocParagraph -> paragraph(b, x, y, width)
            is DocTable -> table(b, x, y, width)
        }

        fun paragraph(p: DocParagraph, x: Float, y0: Float, width: Float): Float {
            var y = y0 + p.spaceBeforePt * textScale * 0.5f
            val indent = (p.indentStartPt * textScale * 0.6f).coerceAtMost(width / 2)
            val text = spans(p)
            if (text.isNotEmpty()) {
                val lw = (width - indent).toInt().coerceAtLeast(8)
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, lw)
                    .setAlignment(
                        when (p.align) {
                            DocAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                            DocAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
                            else -> Layout.Alignment.ALIGN_NORMAL
                        },
                    )
                    .setIncludePad(false)
                    .build()
                c.save()
                c.translate(x + indent, y)
                layout.draw(c)
                c.restore()
                y += layout.height
            }
            for (r in p.runs) if (r is DocRun.Image) y = image(r, x + indent, y, width - indent)
            if (text.isEmpty() && !p.hasImage) y += doc.defaultSizePt * textScale * 0.9f
            return y + p.spaceAfterPt * textScale * 0.5f
        }

        fun spans(p: DocParagraph): SpannableStringBuilder {
            val sb = SpannableStringBuilder()
            p.listMarker?.let { sb.append(it).append("  ") }
            for (r in p.runs) {
                if (r !is DocRun.Text || r.text.isEmpty()) continue
                val start = sb.length
                sb.append(r.text)
                val end = sb.length
                fun span(o: Any) = sb.setSpan(o, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val bold = r.bold || (p.headingLevel > 0 && r.sizePt <= doc.defaultSizePt)
                when {
                    bold && r.italic -> span(StyleSpan(Typeface.BOLD_ITALIC))
                    bold -> span(StyleSpan(Typeface.BOLD))
                    r.italic -> span(StyleSpan(Typeface.ITALIC))
                }
                if (r.underline || r.hyperlink != null) span(UnderlineSpan())
                if (r.strike) span(StrikethroughSpan())
                val size = if (r.script != 0) r.sizePt * 0.7f else r.sizePt
                span(AbsoluteSizeSpan(max(1, (size * textScale).roundToInt())))
                val color = r.colorHex?.let(::parseHex) ?: if (r.hyperlink != null) 0xFF0563C1.toInt() else null
                color?.let { span(ForegroundColorSpan(it)) }
                r.highlightHex?.let(::parseHex)?.let { span(BackgroundColorSpan(it)) }
            }
            return sb
        }

        fun image(r: DocRun.Image, x: Float, y: Float, width: Float): Float {
            val bytes = doc.images[r.path] ?: return y
            val bmp = decodeOfficeImage(bytes, maxImagePx) ?: return y
            var dw = if (r.widthPt > 0f) r.widthPt * textScale / 1.25f else bmp.width.toFloat()
            var dh = if (r.heightPt > 0f) r.heightPt * textScale / 1.25f else bmp.height.toFloat()
            if (dw > width) { dh *= width / dw; dw = width }
            c.drawBitmap(bmp, null, RectF(x, y, x + dw, y + dh), Paint(Paint.FILTER_BITMAP_FLAG))
            return y + dh + 2f
        }

        fun table(t: DocTable, x: Float, y0: Float, width: Float): Float {
            val cols = max(1, t.columnCount)
            val widths = if (t.columnWidthsPt.size == cols && t.columnWidthsPt.sum() > 0f) {
                val sum = t.columnWidthsPt.sum()
                t.columnWidthsPt.map { it / sum * width }
            } else List(cols) { width / cols }
            val pad = max(1.5f, textScale * 2f)
            var y = y0 + 2f
            for (row in t.rows) {
                if (y > c.height) break
                var cx = x
                var col = 0
                val layouts = ArrayList<CellDraw>()
                var rowH = paint.textSize + pad * 2
                for (cell in row.cells) {
                    val span = min(cell.colSpan.coerceAtLeast(1), cols - col).coerceAtLeast(1)
                    val cw = widths.subList(col.coerceAtMost(cols - 1), min(cols, col + span)).sum().coerceAtLeast(4f)
                    val ls = if (cell.mergedContinue) emptyList() else flatten(cell.blocks).mapNotNull { p ->
                        val text = spans(p)
                        if (text.isEmpty()) null else StaticLayout.Builder.obtain(text, 0, text.length, paint, (cw - pad * 2).toInt().coerceAtLeast(4)).setIncludePad(false).build()
                    }
                    rowH = max(rowH, ls.sumOf { it.height } + pad * 2)
                    layouts.add(CellDraw(cx, cw, ls, cell.fillHex?.let(::parseHex)))
                    cx += cw
                    col += span
                }
                for ((lx, lw, ls, fillColor) in layouts) {
                    fillColor?.let { fill.color = it; c.drawRect(lx, y, lx + lw, y + rowH, fill) }
                    var ty = y + pad
                    for (l in ls) {
                        c.save(); c.translate(lx + pad, ty); l.draw(c); c.restore()
                        ty += l.height
                    }
                    if (t.borders) c.drawRect(lx, y, lx + lw, y + rowH, grid)
                }
                y += rowH
            }
            return y + paint.textSize * 0.5f
        }

        fun flatten(blocks: List<DocBlock>): List<DocParagraph> = blocks.flatMap { b ->
            when (b) {
                is DocParagraph -> listOf(b)
                is DocTable -> b.rows.flatMap { r -> r.cells.flatMap { flatten(it.blocks) } }
            }
        }
    }

    private fun parseHex(hex: String): Int? = runCatching {
        val h = hex.removePrefix("#")
        if (h.length != 6) null else (0xFF000000.toInt() or h.toInt(16))
    }.getOrNull()
}


/** Item 10: the top-left of the first visible sheet as a small grid with the real values. */
object XlsxSnapshot {
    const val ROWS = 14
    const val COLS = 6

    fun render(book: XlsxWorkbook, widthPx: Int = 256): Bitmap {
        val w = widthPx.coerceIn(64, 1024)
        val h = (w * 0.8f).roundToInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val sheet = book.sheets.firstOrNull { !it.hidden } ?: book.sheets.firstOrNull()
        val headerH = h / (ROWS + 1f)
        val rowH = headerH
        val headW = w * 0.08f
        // Column widths in proportion to Excel's, over the visible columns.
        val cols = (0 until COLS).map { sheet?.columnWidths?.get(it) ?: sheet?.defaultColumnWidth ?: 8.43f }
        val total = cols.sum().coerceAtLeast(1f)
        val colW = cols.map { it / total * (w - headW) }
        val head = Paint().apply { color = 0xFFF1F3F6.toInt() }
        c.drawRect(0f, 0f, w.toFloat(), headerH, head)
        c.drawRect(0f, 0f, headW, h.toFloat(), head)
        val grid = Paint().apply { color = 0xFFD6DAE0.toInt(); strokeWidth = max(1f, w / 400f) }
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; textSize = rowH * 0.55f }
        val dim = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6B7280.toInt(); textSize = rowH * 0.5f; textAlign = Paint.Align.CENTER }
        var x = headW
        for ((i, cw) in colW.withIndex()) {
            c.drawText(('A' + i).toString(), x + cw / 2, headerH * 0.72f, dim)
            x += cw
        }
        for (r in 0 until ROWS) {
            val top = headerH + r * rowH
            c.drawText((r + 1).toString(), headW / 2, top + rowH * 0.72f, dim)
            var cx = headW
            for (col in 0 until COLS) {
                val cw = colW[col]
                val cell = sheet?.cell(r, col)
                if (cell != null) {
                    cell.fillHex?.let { hex -> parse(hex)?.let { fc -> c.drawRect(cx, top, cx + cw, top + rowH, Paint().apply { color = fc }) } }
                    if (cell.text.isNotEmpty()) {
                        text.isFakeBoldText = cell.bold
                        text.color = cell.colorHex?.let(::parse) ?: INK
                        val s = TextUtilsCompat.ellipsize(cell.text, text, cw - rowH * 0.3f)
                        val right = cell.align == DocAlign.END || (cell.align == null && cell.isNumber)
                        val tx = if (right) cx + cw - rowH * 0.15f - text.measureText(s) else cx + rowH * 0.15f
                        c.save(); c.clipRect(cx, top, cx + cw, top + rowH)
                        c.drawText(s, tx, top + rowH * 0.72f, text)
                        c.restore()
                    }
                }
                cx += cw
            }
        }
        // Grid lines.
        var gx = headW
        c.drawLine(gx, 0f, gx, h.toFloat(), grid)
        for (cw in colW) { gx += cw; c.drawLine(gx, 0f, gx, h.toFloat(), grid) }
        for (r in 0..ROWS) { val y = headerH + r * rowH; c.drawLine(0f, y, w.toFloat(), y, grid) }
        c.drawLine(0f, headerH, w.toFloat(), headerH, grid)
        c.drawRect(RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), Paint().apply { color = 0xFFD9DCE1.toInt(); style = Paint.Style.STROKE; strokeWidth = max(1f, w / 256f) })
        return bmp
    }

    private const val INK = 0xFF111111.toInt()
    private fun parse(hex: String): Int? = runCatching { val h = hex.removePrefix("#").takeLast(6); if (h.length != 6) null else (0xFF000000.toInt() or h.toInt(16)) }.getOrNull()
}

/** Item 10: the first slide (16:9 or the deck's own shape): title, text lines and its first picture. */
object PptxSnapshot {
    fun render(deck: PptxPresentation, widthPx: Int = 256): Bitmap {
        val w = widthPx.coerceIn(64, 1024)
        val ratio = (deck.slideHeightPt / deck.slideWidthPt).coerceIn(0.4f, 1.5f)
        val h = (w * ratio).roundToInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val slide = deck.slides.firstOrNull { !it.hidden } ?: deck.slides.firstOrNull()
        val pad = w * 0.07f
        var y = pad
        val paras = slide?.items?.filterIsInstance<SlideItem.Paragraph>().orEmpty().filter { it.text.isNotBlank() }
        val pic = slide?.items?.filterIsInstance<SlideItem.Picture>()?.firstNotNullOfOrNull { p -> deck.images[p.path]?.let { decodeOfficeImage(it, w) } }
        val title = paras.firstOrNull { it.isTitle } ?: paras.firstOrNull()
        val body = paras.filter { it !== title }
        val textW = if (pic != null && body.isNotEmpty()) (w - 2 * pad) * 0.55f else w - 2 * pad
        if (title != null) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111111.toInt(); textSize = h * 0.11f; isFakeBoldText = true }
            val l = StaticLayout.Builder.obtain(title.text, 0, title.text.length, tp, (w - 2 * pad).toInt()).setMaxLines(2)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .setAlignment(if (body.isEmpty() && pic == null) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
            if (body.isEmpty() && pic == null) y = (h - l.height) / 2f
            c.save(); c.translate(pad, y); l.draw(c); c.restore()
            y += l.height + h * 0.05f
        }
        val bp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt(); textSize = h * 0.065f }
        val bodyTop = y
        for (p in body) {
            if (y > h - pad) break
            val t = (p.bullet?.let { "$it " } ?: "") + p.text
            val indent = p.level * bp.textSize
            val l = StaticLayout.Builder.obtain(t, 0, t.length, bp, (textW - indent).toInt().coerceAtLeast(8)).setMaxLines(2)
                .setEllipsize(android.text.TextUtils.TruncateAt.END).setIncludePad(false).build()
            c.save(); c.translate(pad + indent, y); l.draw(c); c.restore()
            y += l.height + bp.textSize * 0.35f
        }
        if (pic != null) {
            val left = if (body.isNotEmpty()) pad + textW + pad * 0.5f else pad
            val boxW = w - pad - left
            val boxH = h - pad - bodyTop
            if (boxW > 8 && boxH > 8) {
                val s = min(boxW / pic.width, boxH / pic.height)
                val dw = pic.width * s
                val dh = pic.height * s
                val dx = left + (boxW - dw) / 2
                val dy = bodyTop + (boxH - dh) / 2
                c.drawBitmap(pic, null, RectF(dx, dy, dx + dw, dy + dh), Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
        c.drawRect(RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), Paint().apply { color = 0xFFD9DCE1.toInt(); style = Paint.Style.STROKE; strokeWidth = max(1f, w / 256f) })
        return bmp
    }
}

/** android.text.TextUtils.ellipsize without the CharSequence dance. */
internal object TextUtilsCompat {
    fun ellipsize(s: String, p: TextPaint, width: Float): String =
        android.text.TextUtils.ellipsize(s, p, width.coerceAtLeast(1f), android.text.TextUtils.TruncateAt.END).toString()
}
