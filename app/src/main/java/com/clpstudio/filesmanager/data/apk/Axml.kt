package com.clpstudio.filesmanager.data.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A small decoder for Android's binary XML (AXML): AndroidManifest.xml and res/ ... .xml inside an APK.
 * Pure Kotlin, offline, no libraries. It reads the string pool, the attribute resource-id map, namespaces,
 * elements, attributes and text, builds a tree ([Element]) and prints it as pretty, readable XML.
 *
 * Resource references (@0x7f…) are printed as ids unless a [Resolver] (the Android side, from the APK's own
 * resources) turns them into names like "@string/app_name".
 */
object Axml {
    fun interface Resolver {
        /** "@string/app_name" / "@android:style/Theme" for a resource id, or null when unknown. */
        fun name(id: Int): String?
    }

    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    class Attr(
        val ns: String?,
        val name: String,
        val resId: Int,
        val type: Int,
        val data: Int,
        val raw: String?,
    ) {
        /** The attribute's value as text (string, number, boolean, color, dimension, @reference…). */
        fun text(resolver: Resolver? = null): String = raw ?: formatValue(type, data, resolver)

        /** An integer value (versionCode, minSdkVersion…) or null when this isn't a number. */
        fun int(): Int? = when (type) {
            TYPE_INT_DEC, TYPE_INT_HEX, TYPE_INT_BOOLEAN -> data
            TYPE_STRING -> raw?.trim()?.toIntOrNull()
            else -> raw?.trim()?.toIntOrNull()
        }

        fun bool(): Boolean? = when (type) {
            TYPE_INT_BOOLEAN -> data != 0
            TYPE_STRING -> raw?.trim()?.lowercase()?.toBooleanStrictOrNull()
            else -> null
        }

        val isReference: Boolean get() = raw == null && (type == TYPE_REFERENCE || type == TYPE_DYNAMIC_REFERENCE)
    }

    class Element(val ns: String?, val name: String, val attrs: List<Attr>) {
        val children = mutableListOf<Element>()
        var text: String? = null
        /** Namespace declarations made on this element (prefix to uri). */
        val nsDecls = mutableListOf<Pair<String, String>>()

        fun attr(name: String, androidId: Int = 0): Attr? =
            attrs.firstOrNull { (androidId != 0 && it.resId == androidId) } ?: attrs.firstOrNull { it.name == name && (it.ns == null || it.ns == ANDROID_NS) }
                ?: attrs.firstOrNull { it.name == name }

        fun child(name: String): Element? = children.firstOrNull { it.name == name }
        fun all(name: String): List<Element> = children.filter { it.name == name }
    }

    class Document(val root: Element, val prefixes: Map<String, String>)

    class AxmlException(msg: String) : Exception(msg)

    /** True when [bytes] start like a binary XML document. */
    fun isAxml(bytes: ByteArray): Boolean =
        bytes.size >= 8 && u16(bytes, 0) == RES_XML_TYPE && u16(bytes, 2) == 8

    fun parse(bytes: ByteArray): Document {
        if (!isAxml(bytes)) throw AxmlException("Not a binary XML file")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val total = minOf(buf.getInt(4).toLong() and 0xffffffffL, bytes.size.toLong()).toInt()
        var strings: List<String?> = emptyList()
        var resIds = IntArray(0)
        val prefixes = LinkedHashMap<String, String>() // uri -> prefix
        val pendingNs = mutableListOf<Pair<String, String>>()
        val stack = ArrayDeque<Element>()
        var root: Element? = null
        var pos = 8
        fun str(i: Int): String? = if (i < 0 || i >= strings.size) null else strings[i]
        while (pos + 8 <= total) {
            val type = u16(bytes, pos)
            val headerSize = u16(bytes, pos + 2)
            val size = buf.getInt(pos + 4)
            if (size < 8 || pos + size > total) break
            when (type) {
                RES_STRING_POOL_TYPE -> strings = readStringPool(buf, pos)
                RES_XML_RESOURCE_MAP_TYPE -> {
                    val n = (size - headerSize) / 4
                    resIds = IntArray(n) { buf.getInt(pos + headerSize + it * 4) }
                }
                RES_XML_START_NAMESPACE_TYPE -> {
                    val prefix = str(buf.getInt(pos + headerSize)) ?: ""
                    val uri = str(buf.getInt(pos + headerSize + 4)) ?: ""
                    prefixes[uri] = prefix
                    pendingNs += prefix to uri
                }
                RES_XML_START_ELEMENT_TYPE -> {
                    val ext = pos + headerSize
                    val ns = str(buf.getInt(ext))
                    val nameIdx = buf.getInt(ext + 4)
                    val attrStart = u16(bytes, ext + 8)
                    val attrSize = u16(bytes, ext + 10).let { if (it == 0) 20 else it }
                    val attrCount = u16(bytes, ext + 12)
                    val attrs = ArrayList<Attr>(attrCount)
                    for (a in 0 until attrCount) {
                        val p = ext + attrStart + a * attrSize
                        if (p + 20 > pos + size) break
                        val aNs = str(buf.getInt(p))
                        val aNameIdx = buf.getInt(p + 4)
                        val rawIdx = buf.getInt(p + 8)
                        val dType = bytes[p + 15].toInt() and 0xff
                        val data = buf.getInt(p + 16)
                        val resId = if (aNameIdx in resIds.indices) resIds[aNameIdx] else 0
                        var aName = str(aNameIdx).orEmpty()
                        if (aName.isEmpty() && resId != 0) aName = AndroidAttrs.name(resId) ?: String.format("attr_0x%08x", resId)
                        attrs += Attr(aNs?.takeIf { it.isNotEmpty() }, aName, resId, dType, data, if (rawIdx >= 0) str(rawIdx) else null)
                    }
                    val el = Element(ns?.takeIf { it.isNotEmpty() }, str(nameIdx) ?: "?", attrs)
                    el.nsDecls += pendingNs; pendingNs.clear()
                    if (stack.isEmpty()) { if (root == null) root = el } else stack.last().children += el
                    stack.addLast(el)
                }
                RES_XML_END_ELEMENT_TYPE -> if (stack.isNotEmpty()) stack.removeLast()
                RES_XML_CDATA_TYPE -> {
                    val t = str(buf.getInt(pos + headerSize))
                    if (!t.isNullOrBlank() && stack.isNotEmpty()) stack.last().text = (stack.last().text ?: "") + t.trim()
                }
                else -> Unit
            }
            pos += size
        }
        return Document(root ?: throw AxmlException("No elements in this file"), prefixes)
    }

    /** Decodes and pretty-prints in one go. */
    fun decodeToString(bytes: ByteArray, resolver: Resolver? = null): String = print(parse(bytes), resolver)

    /** Pretty XML: declaration, 4-space indent, one attribute per line when there are several. */
    fun print(doc: Document, resolver: Resolver? = null): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        fun prefixed(ns: String?, name: String): String {
            if (ns == null) return name
            val p = doc.prefixes[ns] ?: (if (ns == ANDROID_NS) "android" else null)
            return if (p.isNullOrEmpty()) name else "$p:$name"
        }
        fun write(el: Element, depth: Int) {
            val ind = "    ".repeat(depth)
            val tag = prefixed(el.ns, el.name)
            val parts = el.nsDecls.map { (p, u) -> (if (p.isEmpty()) "xmlns" else "xmlns:$p") + "=\"" + escape(u) + "\"" } +
                el.attrs.map { prefixed(it.ns, it.name) + "=\"" + escape(it.text(resolver)) + "\"" }
            sb.append(ind).append('<').append(tag)
            if (parts.size == 1) sb.append(' ').append(parts[0])
            else for (p in parts) sb.append('\n').append(ind).append("    ").append(p)
            val text = el.text
            if (el.children.isEmpty() && text.isNullOrEmpty()) { sb.append(" />\n"); return }
            sb.append('>')
            if (el.children.isEmpty()) { sb.append(escape(text!!)).append("</").append(tag).append(">\n"); return }
            sb.append('\n')
            if (!text.isNullOrEmpty()) sb.append(ind).append("    ").append(escape(text)).append('\n')
            for (c in el.children) write(c, depth + 1)
            sb.append(ind).append("</").append(tag).append(">\n")
        }
        write(doc.root, 0)
        return sb.toString()
    }

    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
            '\n' -> append("&#10;")
            else -> append(c)
        }
    }

    // ---- value formatting ----
    const val TYPE_NULL = 0x00
    const val TYPE_REFERENCE = 0x01
    const val TYPE_ATTRIBUTE = 0x02
    const val TYPE_STRING = 0x03
    const val TYPE_FLOAT = 0x04
    const val TYPE_DIMENSION = 0x05
    const val TYPE_FRACTION = 0x06
    const val TYPE_DYNAMIC_REFERENCE = 0x07
    const val TYPE_INT_DEC = 0x10
    const val TYPE_INT_HEX = 0x11
    const val TYPE_INT_BOOLEAN = 0x12
    const val TYPE_FIRST_COLOR = 0x1c
    const val TYPE_LAST_COLOR = 0x1f

    private val DIM_UNITS = arrayOf("px", "dp", "sp", "pt", "in", "mm")
    private val FRACTION_UNITS = arrayOf("%", "%p")
    private val RADIX_MULTS = floatArrayOf(1.0f / (1 shl 8), 1.0f / (1 shl 15), 1.0f / (1 shl 23), 1.0f / (1 shl 31))

    private fun complexToFloat(data: Int): Float = (data and 0xffffff00.toInt()) * RADIX_MULTS[(data shr 4) and 3]

    private fun num(f: Float): String = if (f == f.toLong().toFloat()) f.toLong().toString() else "%.2f".format(java.util.Locale.US, f).trimEnd('0').trimEnd('.')

    fun formatValue(type: Int, data: Int, resolver: Resolver? = null): String = when (type) {
        TYPE_NULL -> if (data == 1) "@empty" else "@null"
        TYPE_REFERENCE, TYPE_DYNAMIC_REFERENCE -> if (data == 0) "@null" else resolver?.name(data) ?: String.format("@0x%08X", data)
        TYPE_ATTRIBUTE -> resolver?.name(data)?.replaceFirst('@', '?') ?: String.format("?0x%08X", data)
        TYPE_FLOAT -> num(java.lang.Float.intBitsToFloat(data))
        TYPE_DIMENSION -> num(complexToFloat(data)) + DIM_UNITS.getOrElse(data and 0xf) { "" }
        TYPE_FRACTION -> num(complexToFloat(data) * 100) + FRACTION_UNITS.getOrElse(data and 0xf) { "" }
        TYPE_INT_HEX -> String.format("0x%08X", data)
        TYPE_INT_BOOLEAN -> if (data != 0) "true" else "false"
        in TYPE_FIRST_COLOR..TYPE_LAST_COLOR -> String.format("#%08X", data)
        else -> data.toString()
    }

    // ---- chunks ----
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_TYPE = 0x0003
    private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
    private const val RES_XML_END_NAMESPACE_TYPE = 0x0101
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val RES_XML_CDATA_TYPE = 0x0104
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180

    private fun u16(b: ByteArray, i: Int): Int = if (i + 1 >= b.size) 0 else (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)

    /** Reads a ResStringPool chunk starting at [start] (UTF-8 or UTF-16). Also used for resources.arsc. */
    internal fun readStringPool(buf: ByteBuffer, start: Int): List<String?> {
        val headerSize = buf.getShort(start + 2).toInt() and 0xffff
        val count = buf.getInt(start + 8)
        val flags = buf.getInt(start + 16)
        val stringsStart = buf.getInt(start + 20)
        val utf8 = flags and 0x100 != 0
        val limit = buf.limit()
        if (count < 0 || count > 1_000_000) return emptyList()
        return List(count) { i ->
            runCatching {
                val off = buf.getInt(start + headerSize + i * 4)
                var p = start + stringsStart + off
                if (utf8) {
                    // char length (1-2 bytes), then byte length (1-2 bytes), then the bytes.
                    var b = buf.get(p).toInt() and 0xff; p += 1
                    if (b and 0x80 != 0) p += 1
                    b = buf.get(p).toInt() and 0xff; p += 1
                    var len = b
                    if (b and 0x80 != 0) { len = ((b and 0x7f) shl 8) or (buf.get(p).toInt() and 0xff); p += 1 }
                    if (p + len > limit) null
                    else {
                        val arr = ByteArray(len)
                        for (k in 0 until len) arr[k] = buf.get(p + k)
                        String(arr, Charsets.UTF_8)
                    }
                } else {
                    var len = buf.getShort(p).toInt() and 0xffff; p += 2
                    if (len and 0x8000 != 0) { len = ((len and 0x7fff) shl 16) or (buf.getShort(p).toInt() and 0xffff); p += 2 }
                    if (p + len * 2 > limit) null
                    else {
                        val chars = CharArray(len) { k -> buf.getChar(p + k * 2) }
                        String(chars)
                    }
                }
            }.getOrNull()
        }
    }
}

/** Names of the framework attributes that matter when an APK strips attribute names (obfuscated manifests). */
object AndroidAttrs {
    const val LABEL = 0x01010001
    const val ICON = 0x01010002
    const val NAME = 0x01010003
    const val VERSION_CODE = 0x0101021b
    const val VERSION_NAME = 0x0101021c
    const val MIN_SDK = 0x0101020c
    const val TARGET_SDK = 0x01010270
    const val MAX_SDK = 0x01010271
    const val COMPILE_SDK = 0x01010572
    const val IS_FEATURE_SPLIT = 0x0101055b
    const val IS_SPLIT_REQUIRED = 0x01010591

    private val names = mapOf(
        LABEL to "label", ICON to "icon", NAME to "name", VERSION_CODE to "versionCode", VERSION_NAME to "versionName",
        MIN_SDK to "minSdkVersion", TARGET_SDK to "targetSdkVersion", MAX_SDK to "maxSdkVersion", COMPILE_SDK to "compileSdkVersion",
        IS_FEATURE_SPLIT to "isFeatureSplit", IS_SPLIT_REQUIRED to "isSplitRequired",
        0x01010010 to "exported", 0x0101000e to "enabled", 0x01010000 to "theme", 0x01010006 to "permission",
        0x01010018 to "authorities", 0x0101000f to "debuggable",
    )

    fun name(id: Int): String? = names[id]
}
