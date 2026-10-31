package com.clpstudio.filesmanager.viewer.office

import java.io.File

/**
 * Item 11 (F11): PowerPoint 97–2003 (.ppt / .pps) opened in the app's PowerPoint viewer, offline: slides in show
 * order with their titles, body text (placeholders and text boxes) and pictures (JPEG / PNG / bitmap). Not
 * reproduced: exact positions, themes and backgrounds, charts, WMF/EMF drawings, animations.
 */
object LegacyPpt {
    fun read(file: File): PptxPresentation = Cfb.open(file).use { read(it) }

    private class Rec(val ver: Int, val instance: Int, val type: Int, val start: Int, val len: Int) {
        val dataStart get() = start + 8
        val end get() = start + 8 + len
        val isContainer get() = ver == 0xF
    }

    private fun header(b: ByteArray, p: Int): Rec? {
        if (p < 0 || p + 8 > b.size) return null
        val vi = Le.u16(b, p)
        val len = Le.i32(b, p + 4)
        if (len < 0 || p + 8 + len > b.size) return null
        return Rec(vi and 0xF, vi ushr 4, Le.u16(b, p + 2), p, len)
    }

    private fun children(b: ByteArray, r: Rec): List<Rec> {
        val out = ArrayList<Rec>()
        var p = r.dataStart
        while (p < r.end) { val c = header(b, p) ?: break; out.add(c); p = c.end }
        return out
    }

    private fun walk(b: ByteArray, r: Rec, depth: Int = 0, each: (Rec) -> Unit) {
        each(r)
        if (r.isContainer && depth < 32) for (c in children(b, r)) walk(b, c, depth + 1, each)
    }

    private fun text(b: ByteArray, r: Rec): String = when (r.type) {
        0x0FA0 -> String(b, r.dataStart, r.len - r.len % 2, Charsets.UTF_16LE)
        0x0FA8 -> String(b, r.dataStart, r.len, charset("windows-1252"))
        else -> ""
    }

    fun read(cfb: Cfb): PptxPresentation {
        val doc = cfb.stream("PowerPoint Document") ?: throw OfficeParseException("This isn't a PowerPoint file (.ppt).")
        val user = cfb.stream("Current User")
        val persist = HashMap<Int, Int>()
        var docRef = -1
        // The persist directory: newest edit first, older edits fill in what is missing.
        var edit = if (user != null && user.size >= 20) Le.i32(user, 16) else -1
        var guard = 0
        while (edit > 0 && edit + 32 <= doc.size && guard++ < 1000) {
            val r = header(doc, edit) ?: break
            if (r.type != 0x0FF5) break
            if (docRef < 0) docRef = Le.i32(doc, r.dataStart + 16)
            val dirAt = Le.i32(doc, r.dataStart + 12)
            header(doc, dirAt)?.takeIf { it.type == 0x1772 }?.let { d ->
                var p = d.dataStart
                while (p + 4 <= d.end) {
                    val v = Le.i32(doc, p); p += 4
                    val id = v and 0xFFFFF
                    val n = v ushr 20
                    for (k in 0 until n) { if (p + 4 > d.end) break; persist.putIfAbsent(id + k, Le.i32(doc, p)); p += 4 }
                }
            }
            edit = Le.i32(doc, r.dataStart + 8)
        }
        if (user != null && Le.i32(user, 12) == 0xF3D1C4DF.toInt()) throw OfficeParseException("This presentation is password-protected. Open it with another app.")
        val docRec = persist[docRef]?.let { header(doc, it) }?.takeIf { it.type == 0x03E8 }
            ?: findFirst(doc, 0x03E8) ?: throw OfficeParseException("This PowerPoint file is damaged.")
        var wPt = 720f; var hPt = 540f
        val pictures = cfb.stream("Pictures")
        val blips = ArrayList<ByteArray?>()
        val slides = ArrayList<PptxSlide>()
        val images = HashMap<String, ByteArray>()
        for (c in children(doc, docRec)) {
            when (c.type) {
                0x03E9 -> if (c.len >= 8) { wPt = Le.i32(doc, c.dataStart) / 8f; hPt = Le.i32(doc, c.dataStart + 4) / 8f }
                0x040B -> walk(doc, c) { r -> if (r.type == 0xF007) blips.add(blip(doc, r, pictures)) }
            }
        }
        val list = children(doc, docRec).firstOrNull { it.type == 0x0FF0 && it.instance == 0 }
        if (list != null) {
            // Placeholder text per slide, in show order.
            var curRef = -1
            var curType = 4
            val groups = LinkedHashMap<Int, MutableList<SlideItem>>()
            for (r in children(doc, list)) {
                when (r.type) {
                    0x03F3 -> { curRef = Le.i32(doc, r.dataStart); groups[curRef] = ArrayList() }
                    0x0F9F -> curType = Le.i32(doc, r.dataStart)
                    0x0FA0, 0x0FA8 -> groups[curRef]?.addAll(paragraphs(text(doc, r), curType))
                }
            }
            var number = 0
            for ((ref, items) in groups) {
                number++
                // Text boxes and pictures drawn on the slide itself.
                persist[ref]?.let { header(doc, it) }?.takeIf { it.type == 0x03EE }?.let { slide ->
                    var type = 4
                    val placeholderText = items.filterIsInstance<SlideItem.Paragraph>().map { it.text }.toSet()
                    walk(doc, slide) { r ->
                        when (r.type) {
                            0x0F9F -> type = Le.i32(doc, r.dataStart)
                            // Some writers repeat placeholder text in the slide: don't show it twice.
                            0x0FA0, 0x0FA8 -> items.addAll(paragraphs(text(doc, r), type).filter { it.text !in placeholderText })
                            0xF00B -> pib(doc, r)?.let { idx ->
                                val bytes = blips.getOrNull(idx - 1)
                                if (bytes != null) {
                                    val key = "blip$idx"
                                    images[key] = bytes
                                    items.add(SlideItem.Picture(key, 0f, 0f))
                                }
                            }
                        }
                    }
                }
                slides.add(PptxSlide(number, items))
            }
        }
        if (slides.isEmpty()) throw OfficeParseException("No slides could be read in this presentation.")
        return PptxPresentation(slides, images, wPt.coerceAtLeast(100f), hPt.coerceAtLeast(100f))
    }

    private fun findFirst(b: ByteArray, type: Int): Rec? {
        var p = 0
        while (p + 8 <= b.size) { val r = header(b, p) ?: return null; if (r.type == type) return r; p = r.end }
        return null
    }

    /** Text atom → paragraphs ("\r" separates them, vertical tab is a line break). */
    internal fun paragraphs(s: String, textType: Int): List<SlideItem.Paragraph> {
        val title = textType == 0 || textType == 6
        val bulleted = textType == 1 || textType == 7 || textType == 8
        return s.split('\r').map { it.replace('\u000B', '\n').trimEnd() }.filter { it.isNotBlank() }.map { line ->
            SlideItem.Paragraph(listOf(DocRun.Text(line, bold = title, sizePt = if (title) 28f else 18f)), bullet = if (bulleted) "•" else null, isTitle = title,
                align = if (textType == 5 || textType == 6) DocAlign.CENTER else DocAlign.START)
        }
    }

    /** OfficeArtFOPT: the picture index (pib, 1-based) of a picture shape. */
    private fun pib(b: ByteArray, r: Rec): Int? {
        val n = r.instance
        for (k in 0 until n) {
            val o = r.dataStart + k * 6
            if (o + 6 > r.end) break
            val opid = Le.u16(b, o)
            if (opid and 0x3FFF == 0x0104) return Le.i32(b, o + 2).takeIf { it > 0 }
        }
        return null
    }

    /** One BLIP store entry → JPEG / PNG / BMP bytes, or null (WMF / EMF / PICT). */
    private fun blip(b: ByteArray, fbse: Rec, pictures: ByteArray?): ByteArray? {
        if (fbse.len < 36) return null
        val size = Le.i32(b, fbse.dataStart + 20)
        val fo = Le.i32(b, fbse.dataStart + 28)
        val (src, at) = when {
            pictures != null && fo >= 0 && fo + 8 <= pictures.size -> pictures to fo
            fbse.len > 36 + 8 -> b to (fbse.dataStart + 36 + Le.u8(b, fbse.dataStart + 33))
            else -> return null
        }
        if (size <= 0) return null
        val r = header(src, at) ?: return null
        val skip = when (r.instance) {
            0x46A, 0x6E2, 0x6E0, 0x7A8 -> 17
            0x46B, 0x6E3, 0x6E1, 0x7A9 -> 33
            else -> return null
        }
        if (r.len <= skip) return null
        val data = src.copyOfRange(r.dataStart + skip, r.end)
        return when (r.type) {
            0xF01D, 0xF02A, 0xF01E -> data
            0xF01F -> com.clpstudio.filesmanager.data.DesignPreview.dibToBmp(data)
            else -> null
        }
    }
}
