package com.clpstudio.filesmanager.viewer.office

import java.io.File
import java.util.zip.ZipFile

sealed interface SlideItem {
    /** A text paragraph; [level] is the outline level (0 = top). */
    data class Paragraph(
        val runs: List<DocRun.Text>,
        val level: Int = 0,
        val bullet: String? = null,
        val align: DocAlign = DocAlign.START,
        val isTitle: Boolean = false,
    ) : SlideItem {
        val text: String get() = runs.joinToString("") { it.text }
    }

    data class Picture(val path: String, val widthPt: Float, val heightPt: Float, val description: String? = null) : SlideItem

    data class Table(val rows: List<List<String>>) : SlideItem
}

data class PptxSlide(val number: Int, val items: List<SlideItem>, val hidden: Boolean = false, val notes: String? = null) {
    val title: String? get() = items.filterIsInstance<SlideItem.Paragraph>().firstOrNull { it.isTitle }?.text
}

data class PptxPresentation(
    val slides: List<PptxSlide>,
    val images: Map<String, ByteArray>,
    val slideWidthPt: Float = 960f,
    val slideHeightPt: Float = 540f,
    val warnings: List<String> = emptyList(),
)

/**
 * Offline .pptx reader: each slide's text paragraphs, pictures and tables in reading order
 * (top to bottom, then left to right). Layout positions and themes are not reproduced.
 */
object PptxParser {
    private const val MAX_SLIDES = 2000

    fun parse(file: File): PptxPresentation {
        // Item 11 (F11): the 97–2003 binary format (.doc / .xls / .ppt) goes to its own reader, same viewer.
        if (Cfb.isCfb(file)) return LegacyPpt.read(file)
        val zip = try {
            ZipFile(file)
        } catch (e: Exception) {
            throw OfficeParseException("This file isn't a valid PowerPoint file (.pptx).", e)
        }
        return zip.use { parse(it) }
    }

    fun parse(zip: ZipFile): PptxPresentation {
        val rootRels = zip.readRels("_rels/.rels", "")
        val presPath = rootRels.values.firstOrNull { it.type.endsWith("/officeDocument") && !it.external }?.target
            ?.takeIf { zip.findEntry(it) != null }
            ?: "ppt/presentation.xml".takeIf { zip.findEntry(it) != null }
            ?: throw OfficeParseException("This file isn't a PowerPoint presentation: ppt/presentation.xml is missing.")
        val base = zipDir(presPath)
        val rels = zip.readRels(relsPathFor(presPath), base)
        val slideIds = ArrayList<String>()
        var w = 960f
        var h = 540f
        try {
            zip.parseXml(presPath) { x ->
                if (!x.toRoot()) return@parseXml
                x.children { n ->
                    when (n) {
                        "sldIdLst" -> x.children { s -> if (s == "sldId") x.rAttr("id")?.let { slideIds.add(it) } }
                        "sldSz" -> {
                            val cx = x.attr("cx")?.toFloatOrNull()
                            val cy = x.attr("cy")?.toFloatOrNull()
                            if (cx != null && cy != null && cx > 0 && cy > 0) { w = cx / 12700f; h = cy / 12700f }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            throw OfficeParseException("This PowerPoint file is damaged and couldn't be read.", e)
        }
        var paths = slideIds.mapNotNull { rels[it]?.takeIf { r -> !r.external }?.target }
        if (paths.isEmpty()) {
            // No list: fall back to slideN.xml in number order.
            val found = ArrayList<String>()
            val e = zip.entries()
            while (e.hasMoreElements()) {
                val n = e.nextElement().name
                if (Regex("^${Regex.escape(base)}slides/slide\\d+\\.xml$").matches(n)) found.add(n)
            }
            paths = found.sortedBy { Regex("(\\d+)\\.xml$").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
        }
        val images = LinkedHashMap<String, ByteArray>()
        var imageBytes = 0L
        val warnings = ArrayList<String>()
        val slides = ArrayList<PptxSlide>()
        for ((i, path) in paths.take(MAX_SLIDES).withIndex()) {
            val slideRels = zip.readRels(relsPathFor(path), zipDir(path))
            val slide = try {
                zip.parseXml(path) { x -> readSlide(x, i + 1, slideRels) }
            } catch (e: Exception) {
                warnings.add("slide ${i + 1}")
                PptxSlide(i + 1, emptyList())
            } ?: continue
            for (item in slide.items) if (item is SlideItem.Picture && !images.containsKey(item.path)) {
                val entry = zip.findEntry(item.path) ?: continue
                if (imageBytes + entry.size > OfficeLimits.MAX_TOTAL_IMAGE_BYTES) continue
                val bytes = runCatching { zip.readBytes(entry, OfficeLimits.MAX_IMAGE_BYTES) }.getOrNull() ?: continue
                imageBytes += bytes.size
                images[item.path] = bytes
            }
            slides.add(slide)
        }
        if (slides.isEmpty()) throw OfficeParseException("This presentation has no slides.")
        return PptxPresentation(slides, images, w, h, warnings)
    }

    private class Shape(val y: Float, val x: Float, val order: Int, val items: List<SlideItem>)

    private fun readSlide(x: XmlReader, number: Int, rels: Map<String, OfficeRel>): PptxSlide? {
        if (!x.toRoot()) return null
        val hidden = x.attr("show") == "0"
        val shapes = ArrayList<Shape>()
        x.children { n ->
            if (n == "cSld") x.children { c -> if (c == "spTree") shapeTree(x, rels, shapes) }
        }
        // Reading order: top to bottom, then left to right; shapes with no position keep their place.
        val sorted = shapes.sortedWith(compareBy<Shape>({ (it.y / 20f).toInt() }, { it.x }, { it.order }))
        return PptxSlide(number, sorted.flatMap { it.items }, hidden)
    }

    private fun shapeTree(x: XmlReader, rels: Map<String, OfficeRel>, out: MutableList<Shape>) {
        x.children { n ->
            when (n) {
                "sp" -> shape(x, out)
                "pic" -> picture(x, rels, out)
                "graphicFrame" -> graphicFrame(x, out)
                "grpSp" -> shapeTree(x, rels, out)
                "AlternateContent" -> {
                    var taken = false
                    x.children { c ->
                        if ((c == "Choice" || c == "Fallback") && !taken) {
                            taken = true
                            shapeTree(x, rels, out)
                        }
                    }
                }
            }
        }
    }

    private class Pos(var x: Float = Float.NaN, var y: Float = Float.NaN, var w: Float = 0f, var h: Float = 0f)

    private fun readXfrm(x: XmlReader, pos: Pos) {
        x.children { c ->
            when (c) {
                "off" -> {
                    pos.x = (x.attr("x")?.toFloatOrNull() ?: 0f) / 12700f
                    pos.y = (x.attr("y")?.toFloatOrNull() ?: 0f) / 12700f
                }
                "ext" -> {
                    pos.w = (x.attr("cx")?.toFloatOrNull() ?: 0f) / 12700f
                    pos.h = (x.attr("cy")?.toFloatOrNull() ?: 0f) / 12700f
                }
            }
        }
    }

    private fun place(pos: Pos, phType: String?, order: Int, items: List<SlideItem>): Shape {
        val y = when {
            !pos.y.isNaN() -> pos.y
            phType == "title" || phType == "ctrTitle" -> -2f
            phType == "subTitle" -> 100f
            else -> 200f
        }
        return Shape(y, if (pos.x.isNaN()) 0f else pos.x, order, items)
    }

    private fun shape(x: XmlReader, out: MutableList<Shape>) {
        var phType: String? = null
        var isPlaceholder = false
        val pos = Pos()
        val items = ArrayList<SlideItem>()
        x.children { n ->
            when (n) {
                "nvSpPr" -> x.children { c ->
                    if (c == "nvPr") x.children { p ->
                        if (p == "ph") {
                            isPlaceholder = true
                            phType = x.attr("type") ?: "body"
                        }
                    }
                }
                "spPr" -> x.children { c -> if (c == "xfrm") readXfrm(x, pos) }
                "txBody" -> {
                    val title = phType == "title" || phType == "ctrTitle"
                    val defaultBullets = isPlaceholder && phType == "body"
                    textBody(x, items, title, defaultBullets, if (title) 32f else if (phType == "subTitle") 20f else 18f)
                }
            }
        }
        if (phType in setOf("dt", "ftr", "sldNum")) return
        if (items.isNotEmpty()) out.add(place(pos, phType, out.size, items))
    }

    private fun textBody(x: XmlReader, into: MutableList<SlideItem>, title: Boolean, defaultBullets: Boolean, defaultSize: Float) {
        val autoCounters = IntArray(9)
        x.children { n ->
            if (n != "p") return@children
            var level = 0
            var align = DocAlign.START
            var bullet: String? = null
            var bulletNone = false
            var autoNum: String? = null
            var autoStart = 1
            val runs = ArrayList<DocRun.Text>()
            x.children { c ->
                when (c) {
                    "pPr" -> {
                        level = (x.attr("lvl")?.toIntOrNull() ?: 0).coerceIn(0, 8)
                        align = when (x.attr("algn")) {
                            "ctr" -> DocAlign.CENTER
                            "r" -> DocAlign.END
                            "just", "dist" -> DocAlign.JUSTIFY
                            else -> DocAlign.START
                        }
                        x.children { b ->
                            when (b) {
                                "buNone" -> bulletNone = true
                                "buChar" -> bullet = x.attr("char")?.let { ch -> if (ch.isEmpty() || ch[0].code in 0xF000..0xF0FF || ch == "§" || ch == "Ø" || ch == "ü") "•" else ch }
                                "buAutoNum" -> {
                                    autoNum = x.attr("type") ?: "arabicPeriod"
                                    autoStart = x.attr("startAt")?.toIntOrNull() ?: 1
                                }
                            }
                        }
                    }
                    "r", "fld" -> {
                        var props = RunProps()
                        var text = ""
                        x.children { rc ->
                            when (rc) {
                                "rPr" -> props = runProps(x)
                                "t" -> text = x.text()
                            }
                        }
                        if (text.isNotEmpty()) runs.add(props.toRun(text, defaultSize, title))
                    }
                    "br" -> runs.add(DocRun.Text("\n", sizePt = defaultSize))
                }
            }
            if (runs.isEmpty() || runs.all { it.text.isBlank() }) {
                into.add(SlideItem.Paragraph(emptyList(), level, null, align, title))
                return@children
            }
            val marker = when {
                bulletNone || title -> null
                autoNum != null -> {
                    if (autoCounters[level] == 0) autoCounters[level] = autoStart else autoCounters[level]++
                    for (d in level + 1..8) autoCounters[d] = 0
                    autoNumber(autoCounters[level], autoNum!!)
                }
                bullet != null -> bullet
                defaultBullets -> if (level == 0) "•" else "–"
                else -> null
            }
            into.add(SlideItem.Paragraph(runs, level, marker, align, title))
        }
        // Drop trailing empty paragraphs.
        while (into.isNotEmpty() && (into.last() as? SlideItem.Paragraph)?.runs?.isEmpty() == true) into.removeAt(into.size - 1)
    }

    private fun autoNumber(n: Int, type: String): String {
        val body = when {
            type.startsWith("alphaLc") -> DocxParser.formatNumber(n, "lowerLetter")
            type.startsWith("alphaUc") -> DocxParser.formatNumber(n, "upperLetter")
            type.startsWith("romanLc") -> DocxParser.formatNumber(n, "lowerRoman")
            type.startsWith("romanUc") -> DocxParser.formatNumber(n, "upperRoman")
            else -> n.toString()
        }
        return when {
            type.endsWith("ParenBoth") -> "($body)"
            type.endsWith("ParenR") -> "$body)"
            type.endsWith("Plain") -> body
            else -> "$body."
        }
    }

    private class RunProps(
        var bold: Boolean? = null,
        var italic: Boolean = false,
        var underline: Boolean = false,
        var strike: Boolean = false,
        var size: Float? = null,
        var color: String? = null,
        var link: String? = null,
        var script: Int = 0,
    ) {
        fun toRun(text: String, defaultSize: Float, title: Boolean) = DocRun.Text(
            text = text.replace('\u000B', '\n'),
            bold = bold ?: title,
            italic = italic,
            underline = underline || link != null,
            strike = strike,
            sizePt = size ?: defaultSize,
            colorHex = color,
            hyperlink = link,
            script = script,
        )
    }

    private fun runProps(x: XmlReader): RunProps {
        val p = RunProps()
        x.attr("b")?.let { p.bold = it == "1" || it == "true" }
        p.italic = x.attr("i").let { it == "1" || it == "true" }
        p.underline = x.attr("u")?.let { it != "none" } ?: false
        p.strike = x.attr("strike")?.let { it != "noStrike" } ?: false
        p.size = x.attr("sz")?.toFloatOrNull()?.div(100f)?.coerceIn(4f, 200f)
        x.attr("baseline")?.toIntOrNull()?.let { p.script = if (it > 0) 1 else if (it < 0) -1 else 0 }
        x.children { c ->
            when (c) {
                "solidFill" -> x.children { f -> if (f == "srgbClr") p.color = normalizeHex(x.attr("val")) }
                "hlinkClick" -> p.link = x.rAttr("id")?.let { "link" }
            }
        }
        return p
    }

    private fun picture(x: XmlReader, rels: Map<String, OfficeRel>, out: MutableList<Shape>) {
        val pos = Pos()
        var embed: String? = null
        var descr: String? = null
        fun walk() {
            x.children { n ->
                when (n) {
                    "cNvPr" -> descr = x.attr("descr")?.takeIf { it.isNotBlank() }
                    "blip" -> { if (embed == null) embed = x.rAttr("embed"); walk() }
                    "xfrm" -> readXfrm(x, pos)
                    else -> walk()
                }
            }
        }
        walk()
        val rel = embed?.let { rels[it] } ?: return
        if (rel.external) return
        out.add(place(pos, null, out.size, listOf(SlideItem.Picture(rel.target, pos.w, pos.h, descr))))
    }

    private fun graphicFrame(x: XmlReader, out: MutableList<Shape>) {
        val pos = Pos()
        val rows = ArrayList<List<String>>()
        fun cellText(): String {
            val sb = StringBuilder()
            fun walk() {
                x.children { n ->
                    when (n) {
                        "t" -> sb.append(x.text())
                        "p" -> { if (sb.isNotEmpty()) sb.append('\n'); walk() }
                        else -> walk()
                    }
                }
            }
            walk()
            return sb.toString()
        }
        fun walk() {
            x.children { n ->
                when (n) {
                    "xfrm" -> readXfrm(x, pos)
                    "tr" -> {
                        val cells = ArrayList<String>()
                        x.children { c -> if (c == "tc") cells.add(cellText()) }
                        rows.add(cells)
                    }
                    else -> walk()
                }
            }
        }
        walk()
        if (rows.isNotEmpty()) out.add(place(pos, null, out.size, listOf(SlideItem.Table(rows))))
    }
}
