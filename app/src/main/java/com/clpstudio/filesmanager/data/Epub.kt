package com.clpstudio.filesmanager.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.Locale
import java.util.zip.ZipFile

/**
 * A minimal, offline EPUB 2/3 reader model: container.xml → OPF (metadata, manifest, spine, cover) → table of contents
 * from the EPUB 3 nav document or the EPUB 2 NCX. Chapters are served straight out of the ZIP by the reader.
 */
class EpubBook(
    val file: File,
    val title: String,
    val author: String?,
    val language: String?,
    /** Spine entries (zip paths), in reading order. */
    val spine: List<String>,
    val toc: List<TocEntry>,
    /** Zip path → media type. */
    val mediaTypes: Map<String, String>,
    val coverPath: String?,
) {
    data class TocEntry(val title: String, val path: String, val fragment: String?, val depth: Int)

    /** The spine index of a zip path (fragment ignored), or -1. */
    fun spineIndexOf(path: String): Int = spine.indexOf(path.substringBefore('#'))

    fun mediaTypeOf(path: String): String = mediaTypes[path] ?: Epub.guessMime(path)
}

object Epub {
    class NotAnEpub(message: String) : Exception(message)

    fun open(file: File): EpubBook = ZipFile(file).use { zip ->
        val container = zip.getEntry("META-INF/container.xml") ?: throw NotAnEpub("This EPUB has no META-INF/container.xml")
        val opfPath = zip.getInputStream(container).use { rootfile(it) } ?: throw NotAnEpub("This EPUB names no package file")
        val opfEntry = zip.getEntry(opfPath) ?: throw NotAnEpub("Package file $opfPath is missing")
        val opf = zip.getInputStream(opfEntry).use { parseOpf(it) }
        val base = opfPath.substringBeforeLast('/', "")
        val items = opf.items.mapValues { (_, it) -> it.copy(href = resolve(base, it.href)) }
        val spine = opf.spine.mapNotNull { items[it]?.href }.filter { zip.getEntry(it) != null }
        val nav = items.values.firstOrNull { "nav" in it.properties.split(' ') }
        val ncx = (opf.tocId?.let { items[it] } ?: items.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" })
        var toc: List<EpubBook.TocEntry> = emptyList()
        if (nav != null) zip.getEntry(nav.href)?.let { e ->
            toc = runCatching { zip.getInputStream(e).use { parseNav(it, nav.href.substringBeforeLast('/', "")) } }.getOrDefault(emptyList())
        }
        if (toc.isEmpty() && ncx != null) zip.getEntry(ncx.href)?.let { e ->
            toc = runCatching { zip.getInputStream(e).use { parseNcx(it, ncx.href.substringBeforeLast('/', "")) } }.getOrDefault(emptyList())
        }
        if (toc.isEmpty()) toc = spine.mapIndexed { i, p -> EpubBook.TocEntry("Chapter ${i + 1}", p, null, 0) }
        val cover = (items.values.firstOrNull { "cover-image" in it.properties.split(' ') }
            ?: opf.coverId?.let { items[it] }
            ?: items.values.firstOrNull { it.mediaType.startsWith("image/") && it.id.contains("cover", true) })
            ?.href?.takeIf { zip.getEntry(it) != null }
        EpubBook(
            file = file,
            title = opf.title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
            author = opf.creator?.takeIf { it.isNotBlank() },
            language = opf.language,
            spine = spine,
            toc = toc,
            mediaTypes = items.values.associate { it.href to it.mediaType },
            coverPath = cover,
        )
    }

    /** The bytes of one entry (chapters, images, CSS, fonts) or null. */
    fun read(file: File, path: String): ByteArray? = ZipFile(file).use { zip ->
        zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
    }

    /** "OEBPS/text" + "../images/a%20b.png" → "OEBPS/images/a b.png" (zip paths, never leaving the root). */
    fun resolve(base: String, href: String): String {
        val clean = runCatching { URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8") }.getOrDefault(href.substringBefore('#'))
        val parts = ArrayList<String>()
        if (!clean.startsWith("/")) base.split('/').filter { it.isNotEmpty() }.forEach { parts += it }
        for (p in clean.split('/')) when (p) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts += p
        }
        return parts.joinToString("/")
    }

    fun guessMime(path: String): String = when (FileTypes.extension(path)) {
        "xhtml", "xht" -> "application/xhtml+xml"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    // ---------------------------------------------------------------- XML

    private fun parser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        runCatching { setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        setInput(input, null)
    }

    private val XmlPullParser.local: String get() = name.substringAfter(':').lowercase(Locale.ROOT)

    private fun XmlPullParser.attr(n: String): String? {
        for (i in 0 until attributeCount) if (getAttributeName(i).substringAfter(':').equals(n, true)) return getAttributeValue(i)
        return null
    }

    internal fun rootfile(input: InputStream): String? {
        val p = parser(input)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.local == "rootfile") return p.attr("full-path")
        }
        return null
    }

    internal data class Item(val id: String, val href: String, val mediaType: String, val properties: String)
    internal class Opf(
        val title: String?, val creator: String?, val language: String?, val items: Map<String, Item>,
        val spine: List<String>, val tocId: String?, val coverId: String?,
    )

    internal fun parseOpf(input: InputStream): Opf {
        val p = parser(input)
        var title: String? = null
        var creator: String? = null
        var language: String? = null
        var tocId: String? = null
        var coverId: String? = null
        val items = LinkedHashMap<String, Item>()
        val spine = ArrayList<String>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.local) {
                "title" -> if (title == null) title = p.nextText().trim()
                "creator" -> if (creator == null) creator = p.nextText().trim()
                "language" -> if (language == null) language = p.nextText().trim()
                "meta" -> if (p.attr("name") == "cover") coverId = p.attr("content")
                "item" -> {
                    val id = p.attr("id") ?: continue
                    val href = p.attr("href") ?: continue
                    items[id] = Item(id, href, p.attr("media-type") ?: guessMime(href), p.attr("properties") ?: "")
                }
                "spine" -> tocId = p.attr("toc")
                "itemref" -> if (p.attr("linear") != "no") p.attr("idref")?.let { spine += it }
            }
        }
        return Opf(title, creator, language, items, spine, tocId, coverId)
    }

    /** EPUB 3: the nav element with epub:type="toc" (or the first nav) → its links, depth = list nesting. */
    internal fun parseNav(input: InputStream, base: String): List<EpubBook.TocEntry> {
        val p = parser(input)
        val out = ArrayList<EpubBook.TocEntry>()
        var inToc = false
        var navDepth = 0
        var listDepth = 0
        var href: String? = null
        val text = StringBuilder()
        var done = false
        while (!done && p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.local) {
                    "nav" -> {
                        navDepth++
                        if (!inToc && (p.attr("type")?.contains("toc") != false)) { inToc = true; navDepth = 1 }
                    }
                    "ol", "ul" -> if (inToc) listDepth++
                    "a" -> if (inToc) { href = p.attr("href"); text.setLength(0) }
                }
                XmlPullParser.TEXT -> if (href != null) text.append(p.text)
                XmlPullParser.END_TAG -> when (p.local) {
                    "a" -> if (inToc && href != null) {
                        val h = href!!
                        val t = text.toString().replace(Regex("\\s+"), " ").trim()
                        if (t.isNotEmpty() && !h.startsWith("http")) {
                            out += EpubBook.TocEntry(t, resolve(base, h), h.substringAfter('#', "").ifEmpty { null }, (listDepth - 1).coerceAtLeast(0))
                        }
                        href = null
                    }
                    "ol", "ul" -> if (inToc) listDepth--
                    "nav" -> if (inToc) { navDepth--; if (navDepth <= 0) done = true }
                }
            }
        }
        return out
    }

    /** EPUB 2: toc.ncx navPoints (nested = deeper). */
    internal fun parseNcx(input: InputStream, base: String): List<EpubBook.TocEntry> {
        val p = parser(input)
        val out = ArrayList<EpubBook.TocEntry>()
        var depth = 0
        var label: String? = null
        var inText = false
        val pending = ArrayList<Pair<String?, Int>>() // label per open navPoint
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.local) {
                    "navpoint" -> { depth++; label = null }
                    "text" -> if (depth > 0) inText = true
                    "content" -> if (depth > 0) {
                        val src = p.attr("src")
                        if (src != null) out += EpubBook.TocEntry(label?.trim().orEmpty().ifEmpty { "Chapter ${out.size + 1}" }, resolve(base, src), src.substringAfter('#', "").ifEmpty { null }, depth - 1)
                    }
                }
                XmlPullParser.TEXT -> if (inText) label = (label ?: "") + p.text
                XmlPullParser.END_TAG -> when (p.local) {
                    "text" -> inText = false
                    "navpoint" -> depth--
                }
            }
        }
        pending.clear()
        return out
    }

    /**
     * The chapter HTML with the reader's style put in its head: readable margins and line height, pictures that fit the
     * screen, and day / night colours. Scripts are not run (JavaScript is off in the reader).
     */
    fun styled(html: String, night: Boolean): String {
        val bg = if (night) "#121212" else "#FFFFFF"
        val fg = if (night) "#E6E6E6" else "#1B1B1F"
        val link = if (night) "#8AB4F8" else "#1A5FD0"
        val css = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>" +
            "<style>html,body{background:$bg !important;color:$fg !important;}" +
            "body{margin:0;padding:16px 18px 48px 18px;line-height:1.6;word-wrap:break-word;overflow-wrap:break-word;}" +
            (if (night) "*{color:$fg !important;background-color:transparent !important;}" else "") +
            "a{color:$link !important;}img,svg,video{max-width:100% !important;height:auto !important;}" +
            "pre{white-space:pre-wrap;}table{max-width:100%;}</style>"
        val headEnd = Regex("</head\\s*>", RegexOption.IGNORE_CASE).find(html)
        return when {
            headEnd != null -> html.substring(0, headEnd.range.first) + css + html.substring(headEnd.range.first)
            else -> {
                val body = Regex("<body[^>]*>", RegexOption.IGNORE_CASE).find(html)
                if (body != null) html.substring(0, body.range.last + 1) + css + html.substring(body.range.last + 1) else css + html
            }
        }
    }
}
