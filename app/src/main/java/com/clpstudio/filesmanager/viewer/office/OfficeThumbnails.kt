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
 *  2. for Word files without one, a small snapshot of the first page (white page, real text).
 */
object OfficeThumbnails {
    /** Blocks parsed for a snapshot: plenty for a first page, cheap for a huge document. */
    private const val SNAPSHOT_BLOCKS = 400

    fun load(file: File, format: OfficeFormat?, maxPx: Int): Bitmap? {
        val fmt = format ?: OfficeFormats.sniff(file) ?: return null
        val zip = try { ZipFile(file) } catch (e: Exception) { return null }
        return zip.use { z ->
            embedded(z, maxPx) ?: if (fmt == OfficeFormat.DOCX) {
                runCatching { DocxSnapshot.render(DocxParser.parse(z, maxBlocks = SNAPSHOT_BLOCKS), maxPx) }.getOrNull()
            } else null
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
