package com.clpstudio.filesmanager.viewer.office

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Thrown when an Office file can't be read at all (not a zip, main part missing, …). */
class OfficeParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Limits that keep a hostile or huge file from exhausting memory. */
internal object OfficeLimits {
    const val MAX_XML_BYTES = 64L * 1024 * 1024
    const val MAX_IMAGE_BYTES = 16L * 1024 * 1024
    const val MAX_TOTAL_IMAGE_BYTES = 96L * 1024 * 1024
}

/** Small streaming helper over [XmlPullParser] (namespace aware: [name] is the local name). */
internal class XmlReader(val p: XmlPullParser) {
    val name: String get() = p.name ?: ""

    /** Set when the document ended in the middle of an element (a cut-off or damaged file). */
    var hitEof = false

    /** Attribute by local name (any namespace). */
    fun attr(local: String): String? {
        for (i in 0 until p.attributeCount) if (p.getAttributeName(i) == local) return p.getAttributeValue(i)
        return null
    }

    /** Attribute by local name, preferring the relationships namespace (r:id, r:embed). */
    fun rAttr(local: String): String? {
        var fallback: String? = null
        for (i in 0 until p.attributeCount) {
            if (p.getAttributeName(i) != local) continue
            val ns = p.getAttributeNamespace(i) ?: ""
            if (ns.contains("relationships")) return p.getAttributeValue(i)
            if (fallback == null) fallback = p.getAttributeValue(i)
        }
        return fallback
    }

    /** OOXML on/off attribute: absent = true, "0"/"false"/"off" = false. */
    fun onOff(): Boolean {
        val v = attr("val") ?: return true
        return !(v == "0" || v.equals("false", true) || v.equals("off", true) || v.equals("none", true))
    }

    /**
     * Iterates the direct child elements of the current element. [block] may consume the child
     * (by calling [children]/[text]/[skip]) or leave it; an unconsumed child is skipped.
     */
    inline fun children(block: (String) -> Unit) {
        if (p.eventType != XmlPullParser.START_TAG) return
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) { hitEof = true; return }
            if (ev == XmlPullParser.END_TAG && p.depth == depth) return
            if (ev == XmlPullParser.START_TAG && p.depth == depth + 1) {
                block(p.name ?: "")
                if (p.eventType == XmlPullParser.START_TAG && p.depth == depth + 1) skip()
            }
        }
    }

    /** Concatenated text inside the current element (consumes it). */
    fun text(): String {
        if (p.eventType != XmlPullParser.START_TAG) return ""
        val depth = p.depth
        val sb = StringBuilder()
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) { hitEof = true; break }
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev == XmlPullParser.TEXT || ev == XmlPullParser.CDSECT || ev == XmlPullParser.ENTITY_REF) p.text?.let { sb.append(it) }
        }
        return sb.toString()
    }

    /** Skips the current element and everything inside it. */
    fun skip() {
        if (p.eventType != XmlPullParser.START_TAG) return
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) { hitEof = true; return }
            if (ev == XmlPullParser.END_TAG && p.depth == depth) return
        }
    }

    /** Moves to the root element. Returns false for an empty document. */
    fun toRoot(): Boolean {
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> return true
                XmlPullParser.END_DOCUMENT -> return false
            }
        }
    }
}

internal fun newXmlReader(input: InputStream): XmlReader {
    val p = Xml.newPullParser()
    p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
    try { p.setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl", false) } catch (_: Exception) {}
    p.setInput(input, null)
    return XmlReader(p)
}

/** Reads one zip part with a size cap. */
internal fun ZipFile.readBytes(entry: ZipEntry, cap: Long): ByteArray? {
    if (entry.isDirectory) return null
    if (entry.size > cap) return null
    getInputStream(entry).use { input ->
        val out = ByteArrayOutputStream(if (entry.size in 1..cap) entry.size.toInt() else 8192)
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

/** Finds a zip part case-insensitively (some generators vary the case). */
internal fun ZipFile.findEntry(path: String): ZipEntry? {
    getEntry(path)?.let { return it }
    val lower = path.lowercase()
    val e = entries()
    while (e.hasMoreElements()) {
        val z = e.nextElement()
        if (z.name.lowercase() == lower || z.name.trimStart('/').lowercase() == lower) return z
    }
    return null
}

/** Parses a zip XML part with [block]; returns null if the part is missing or too big. */
internal fun <T> ZipFile.parseXml(path: String, block: (XmlReader) -> T): T? {
    val entry = findEntry(path) ?: return null
    if (entry.size > OfficeLimits.MAX_XML_BYTES) return null
    return getInputStream(entry).use { input -> block(newXmlReader(input.buffered())) }
}

internal data class OfficeRel(val target: String, val type: String, val external: Boolean)

/** Reads a .rels part: Id -> target (already resolved to a zip path unless external). */
internal fun ZipFile.readRels(relsPath: String, baseDir: String): Map<String, OfficeRel> {
    val out = HashMap<String, OfficeRel>()
    runCatching {
        parseXml(relsPath) { x ->
            if (!x.toRoot()) return@parseXml
            x.children { n ->
                if (n == "Relationship") {
                    val id = x.attr("Id")
                    val target = x.attr("Target")
                    val external = x.attr("TargetMode").equals("External", true)
                    if (id != null && target != null) {
                        out[id] = OfficeRel(if (external) target else resolveZipPath(baseDir, target), x.attr("Type") ?: "", external)
                    }
                }
            }
        }
    }
    return out
}

/** "word/" + "../media/a.png" -> "media/a.png"; "/word/media/a.png" -> "word/media/a.png". */
internal fun resolveZipPath(baseDir: String, target: String): String {
    val t = target.replace('\\', '/')
    val parts = ArrayList<String>()
    if (!t.startsWith("/")) baseDir.split('/').filter { it.isNotEmpty() }.forEach { parts.add(it) }
    for (seg in t.split('/')) {
        when {
            seg.isEmpty() || seg == "." -> {}
            seg == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts.add(java.net.URLDecoder.decode(seg.replace("+", "%2B"), "UTF-8"))
        }
    }
    return parts.joinToString("/")
}

/** Directory of a zip path, with a trailing slash ("word/document.xml" -> "word/"). */
internal fun zipDir(path: String): String = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

/** The .rels part that belongs to [partPath]. */
internal fun relsPathFor(partPath: String): String = "${zipDir(partPath)}_rels/${partPath.substringAfterLast('/')}.rels"

/**
 * Decodes image bytes with a size cap: the bitmap's longest side is at most about [maxPx]
 * (power-of-two subsampling), so a 50-megapixel photo inside a document can't cause an OOM.
 */
fun decodeOfficeImage(bytes: ByteArray, maxPx: Int = 2048): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
} catch (_: Throwable) {
    null
}

/** "FF0000" / "ff0000" -> "#FF0000"; "auto" and junk -> null. */
internal fun normalizeHex(v: String?): String? {
    if (v == null) return null
    val s = v.trim().removePrefix("#")
    if (s.length != 6 || !s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return "#" + s.uppercase()
}

internal val HIGHLIGHT_COLORS = mapOf(
    "yellow" to "#FFFF00", "green" to "#00FF00", "cyan" to "#00FFFF", "magenta" to "#FF00FF", "blue" to "#0000FF",
    "red" to "#FF0000", "darkBlue" to "#000080", "darkCyan" to "#008080", "darkGreen" to "#008000",
    "darkMagenta" to "#800080", "darkRed" to "#800000", "darkYellow" to "#808000", "darkGray" to "#808080",
    "lightGray" to "#C0C0C0", "black" to "#000000", "white" to "#FFFFFF",
)
