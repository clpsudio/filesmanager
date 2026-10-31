package com.clpstudio.filesmanager.data

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.pow

/**
 * Offline previews for design files: Photoshop (PSD/PSB), Illustrator (AI), CorelDRAW (CDR) and AutoCAD (DWG).
 * Pure JVM (no Android calls) so it is unit tested; the viewer turns the result into a bitmap.
 *
 *  - PSD/PSB: the flattened composite image (8/16/32-bit, RGB, grayscale, CMYK, indexed, Lab, bitmap; raw or RLE),
 *    downsampled while it is read; the embedded JPEG thumbnail when the composite can't be read.
 *  - AI: files saved with "PDF compatible" (the default since CS) are PDFs → page 1 is rendered by PdfRenderer;
 *    otherwise (or if that fails) the XMP thumbnail Illustrator embeds.
 *  - CDR: X4 and later are ZIP files with a thumbnail inside; older RIFF files carry a DIB preview in a DISP chunk.
 *  - DWG (R13 / AC1012 and later): the preview image AutoCAD stores in the file (BMP, or PNG since 2013).
 *    R12 and older have none → a "no preview" card.
 */
object DesignPreview {
    val extensions = setOf("psd", "psb", "ai", "cdr", "dwg")

    fun isDesign(name: String): Boolean = FileTypes.extension(name) in extensions

    /** What the viewer can draw. */
    sealed interface Image {
        /** JPEG / PNG / BMP file bytes. [swapRedBlue] for Photoshop 4 thumbnails (stored BGR). */
        class Encoded(val bytes: ByteArray, val swapRedBlue: Boolean = false) : Image
        /** Decoded pixels, 0xAARRGGBB. */
        class Pixels(val width: Int, val height: Int, val argb: IntArray) : Image
        /** A PDF-compatible Illustrator file: render page 1. */
        class PdfPage(val file: File) : Image
    }

    data class Result(
        /** "Photoshop document (PSD)" etc. */
        val format: String,
        val image: Image?,
        /** Where the preview came from, or why there is none. */
        val note: String,
        /** Extra rows for the info card: Dimensions, Color mode, Version... */
        val details: List<Pair<String, String>> = emptyList(),
    )

    fun formatName(name: String): String = when (FileTypes.extension(name)) {
        "psd" -> "Photoshop document (PSD)"
        "psb" -> "Photoshop large document (PSB)"
        "ai" -> "Illustrator artwork (AI)"
        "cdr" -> "CorelDRAW drawing (CDR)"
        "dwg" -> "AutoCAD drawing (DWG)"
        else -> FileTypes.extension(name).uppercase(Locale.ROOT)
    }

    /** Reads the best preview of [file] (named [name]); [maxDim] bounds decoded pixels. Never throws. */
    fun read(file: File, name: String = file.name, maxDim: Int = 2048, preferThumbnail: Boolean = false): Result {
        val format = formatName(name)
        return try {
            when (FileTypes.extension(name)) {
                "psd", "psb" -> Psd.read(file, maxDim, preferThumbnail).copy(format = format)
                "ai" -> Ai.read(file).copy(format = format)
                "cdr" -> Cdr.read(file).copy(format = format)
                "dwg" -> Dwg.read(file).copy(format = format)
                else -> Result(format, null, "No preview for this type")
            }
        } catch (e: OutOfMemoryError) {
            Result(format, null, "Too large to preview")
        } catch (e: Exception) {
            Result(format, null, "This file couldn't be read (" + (e.message ?: e.javaClass.simpleName) + ")")
        }
    }

    // ---------------------------------------------------------------- helpers

    internal fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    internal fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    /** A Windows DIB (BITMAPINFOHEADER + palette + pixels, no file header) → a complete .bmp file, or null. */
    fun dibToBmp(dib: ByteArray): ByteArray? {
        if (dib.size < 16) return null
        val headerSize = le32(dib, 0)
        if (headerSize != 12 && headerSize !in 40..124) return null
        val palette: Int
        var masks = 0
        if (headerSize == 12) {
            val bits = le16(dib, 10)
            palette = if (bits <= 8) (1 shl bits) * 3 else 0
        } else {
            if (dib.size < 40) return null
            val bits = le16(dib, 14)
            val compression = le32(dib, 16)
            val used = le32(dib, 32)
            val entries = if (used in 1..256) used else if (bits <= 8) 1 shl bits else 0
            palette = entries * 4
            if (headerSize == 40 && (compression == 3 || compression == 6)) masks = if (compression == 6) 16 else 12
        }
        val offset = 14 + headerSize + masks + palette
        if (offset > 14 + dib.size) return null
        val total = 14 + dib.size
        val out = ByteArray(total)
        out[0] = 'B'.code.toByte(); out[1] = 'M'.code.toByte()
        putLe32(out, 2, total)
        putLe32(out, 10, offset)
        System.arraycopy(dib, 0, out, 14, dib.size)
        return out
    }

    private fun putLe32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte()
    }

    internal fun startsWith(b: ByteArray, s: String, at: Int = 0): Boolean =
        b.size >= at + s.length && s.indices.all { b[at + it] == s[it].code.toByte() }

    internal fun head(file: File, n: Int): ByteArray = FileInputStream(file).use { i ->
        val buf = ByteArray(n)
        var r = 0
        while (r < n) { val k = i.read(buf, r, n - r); if (k < 0) break; r += k }
        buf.copyOf(r)
    }

    internal fun imageKind(b: ByteArray): String? = when {
        b.size > 3 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8 -> "JPEG"
        startsWith(b, "\u0089PNG") -> "PNG"
        startsWith(b, "BM") -> "BMP"
        else -> null
    }

    // ---------------------------------------------------------------- PSD / PSB

    object Psd {
        private val modes = mapOf(0 to "Bitmap", 1 to "Grayscale", 2 to "Indexed", 3 to "RGB", 4 to "CMYK", 7 to "Multichannel", 8 to "Duotone", 9 to "Lab")

        class Header(val version: Int, val channels: Int, val height: Int, val width: Int, val depth: Int, val mode: Int)

        fun read(file: File, maxDim: Int, preferThumbnail: Boolean): Result {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.readInt() != 0x38425053) return Result("", null, "Not a Photoshop file")
                val version = raf.readUnsignedShort()
                if (version != 1 && version != 2) return Result("", null, "Unknown Photoshop version $version")
                raf.skipBytes(6)
                val h = Header(version, raf.readUnsignedShort(), raf.readInt(), raf.readInt(), raf.readUnsignedShort(), raf.readUnsignedShort())
                val details = listOf(
                    "Dimensions" to "${h.width} × ${h.height}",
                    "Color mode" to (modes[h.mode] ?: "Mode ${h.mode}") + " · ${h.depth}-bit",
                )
                val colorLen = raf.readInt().toLong() and 0xFFFFFFFFL
                val palette = if (h.mode == 2 && colorLen >= 768) ByteArray(768).also { raf.readFully(it); raf.seek(raf.filePointer + colorLen - 768) }
                else { raf.seek(raf.filePointer + colorLen); null }
                // Image resources: the embedded thumbnail (1036, or 1033 = Photoshop 4, BGR).
                val resLen = raf.readInt().toLong() and 0xFFFFFFFFL
                val resEnd = raf.filePointer + resLen
                var thumb: Image.Encoded? = null
                while (raf.filePointer + 12 <= resEnd) {
                    val sig = raf.readInt()
                    if (sig != 0x3842494D && sig != 0x4D655361 /* MeSa */ && sig != 0x50485554 /* PHUT */) break
                    val id = raf.readUnsignedShort()
                    val nameLen = raf.readUnsignedByte()
                    raf.skipBytes(if ((nameLen + 1) % 2 == 0) nameLen else nameLen + 1)
                    val size = raf.readInt().toLong() and 0xFFFFFFFFL
                    val dataStart = raf.filePointer
                    if ((id == 1036 || id == 1033) && size > 28 && thumb == null) {
                        raf.skipBytes(28)
                        val jpeg = ByteArray((size - 28).toInt())
                        raf.readFully(jpeg)
                        if (imageKind(jpeg) == "JPEG") thumb = Image.Encoded(jpeg, swapRedBlue = id == 1033)
                    }
                    raf.seek(dataStart + size + (size and 1))
                }
                raf.seek(resEnd)
                if (preferThumbnail && thumb != null) return Result("", thumb, "Embedded thumbnail", details)
                val layerLen = if (version == 1) raf.readInt().toLong() and 0xFFFFFFFFL else raf.readLong()
                raf.seek(raf.filePointer + layerLen)
                val dataOffset = raf.filePointer
                val pixels = try {
                    decodeComposite(file, dataOffset, h, palette, maxDim)
                } catch (e: EOFException) {
                    null
                }
                return when {
                    pixels != null -> Result("", pixels, "Full image", details)
                    thumb != null -> Result("", thumb, "Embedded thumbnail", details)
                    else -> Result("", null, "This Photoshop file has no readable preview", details)
                }
            }
        }

        /** The merged image (planar channels, raw or RLE), sampled down to at most [maxDim] on the long side. */
        internal fun decodeComposite(file: File, offset: Long, h: Header, palette: ByteArray?, maxDim: Int): Image.Pixels? {
            val w = h.width
            val ht = h.height
            if (w <= 0 || ht <= 0) return null
            val colorChannels = when (h.mode) {
                3, 9 -> 3
                4 -> 4
                else -> 1
            }
            if (h.channels < colorChannels) return null
            if (h.depth !in setOf(1, 8, 16, 32)) return null
            val step = max(1, (max(w, ht) + maxDim - 1) / maxDim)
            val ow = (w + step - 1) / step
            val oh = (ht + step - 1) / step
            val planes = Array(colorChannels) { ByteArray(ow * oh) }
            val rowBytes = if (h.depth == 1) (w + 7) / 8 else w * (h.depth / 8)
            val row = ByteArray(rowBytes)
            DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { input ->
                skipFully(input, offset)
                val compression = input.readUnsignedShort()
                when (compression) {
                    0 -> for (c in 0 until colorChannels) for (y in 0 until ht) {
                        input.readFully(row)
                        if (y % step == 0) sampleRow(row, h.depth, w, step, planes[c], (y / step) * ow)
                    }
                    1 -> {
                        val n = h.channels * ht
                        val counts = IntArray(n) { if (h.version == 2) input.readInt() else input.readUnsignedShort() }
                        var packed = ByteArray(rowBytes * 2 + 16)
                        for (c in 0 until colorChannels) for (y in 0 until ht) {
                            val len = counts[c * ht + y]
                            if (y % step != 0) { skipFully(input, len.toLong()); continue }
                            if (packed.size < len) packed = ByteArray(len)
                            input.readFully(packed, 0, len)
                            unpackBits(packed, len, row)
                            sampleRow(row, h.depth, w, step, planes[c], (y / step) * ow)
                        }
                    }
                    else -> return null // ZIP-compressed composite: not written by Photoshop for the merged image
                }
            }
            val out = IntArray(ow * oh)
            for (i in out.indices) out[i] = toArgb(h.mode, planes, i, palette)
            return Image.Pixels(ow, oh, out)
        }

        private fun sampleRow(row: ByteArray, depth: Int, w: Int, step: Int, plane: ByteArray, at: Int) {
            var o = at
            var x = 0
            while (x < w) {
                plane[o++] = when (depth) {
                    8 -> row[x]
                    16 -> row[x * 2]
                    32 -> {
                        val bits = ((row[x * 4].toInt() and 0xFF) shl 24) or ((row[x * 4 + 1].toInt() and 0xFF) shl 16) or
                            ((row[x * 4 + 2].toInt() and 0xFF) shl 8) or (row[x * 4 + 3].toInt() and 0xFF)
                        // 32-bit files are linear light: back to sRGB-ish gamma for display.
                        val v = Float.fromBits(bits).coerceIn(0f, 1f).toDouble().pow(1 / 2.2)
                        (v * 255 + 0.5).toInt().toByte()
                    }
                    else -> if ((row[x shr 3].toInt() shr (7 - (x and 7))) and 1 == 1) 0.toByte() else 255.toByte() // bitmap: 1 = black
                }
                x += step
            }
        }

        private fun toArgb(mode: Int, p: Array<ByteArray>, i: Int, palette: ByteArray?): Int {
            fun v(c: Int) = p[c][i].toInt() and 0xFF
            return when (mode) {
                3 -> rgb(v(0), v(1), v(2))
                4 -> { // Photoshop stores CMYK inverted (255 = no ink)
                    val k = v(3)
                    rgb(v(0) * k / 255, v(1) * k / 255, v(2) * k / 255)
                }
                2 -> if (palette != null) { val x = v(0); rgb(palette[x].toInt() and 0xFF, palette[256 + x].toInt() and 0xFF, palette[512 + x].toInt() and 0xFF) } else gray(v(0))
                9 -> lab(v(0), v(1), v(2))
                else -> gray(v(0))
            }
        }

        private fun gray(g: Int) = rgb(g, g, g)

        private fun lab(l8: Int, a8: Int, b8: Int): Int {
            val l = l8 * 100.0 / 255
            val a = a8 - 128.0
            val b = b8 - 128.0
            val fy = (l + 16) / 116
            val fx = fy + a / 500
            val fz = fy - b / 200
            fun f(t: Double) = if (t * t * t > 0.008856) t * t * t else (t - 16.0 / 116) / 7.787
            val x = 0.95047 * f(fx)
            val y = f(fy)
            val z = 1.08883 * f(fz)
            fun g(c: Double): Int {
                val s = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055
                return (s * 255 + 0.5).toInt().coerceIn(0, 255)
            }
            return rgb(
                g(3.2406 * x - 1.5372 * y - 0.4986 * z),
                g(-0.9689 * x + 1.8758 * y + 0.0415 * z),
                g(0.0557 * x - 0.2040 * y + 1.0570 * z),
            )
        }

        /** PackBits (Photoshop RLE): one row into [out]; extra input is ignored, missing output stays 0. */
        internal fun unpackBits(src: ByteArray, len: Int, out: ByteArray) {
            var i = 0
            var o = 0
            while (i < len && o < out.size) {
                val n = src[i++].toInt()
                if (n >= 0) {
                    val count = minOf(n + 1, out.size - o, len - i)
                    System.arraycopy(src, i, out, o, count)
                    i += n + 1
                    o += count
                } else if (n != -128) {
                    if (i >= len) break
                    val b = src[i++]
                    val count = minOf(1 - n, out.size - o)
                    java.util.Arrays.fill(out, o, o + count, b)
                    o += count
                }
            }
            if (o < out.size) java.util.Arrays.fill(out, o, out.size, 0)
        }
    }

    internal fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    internal fun skipFully(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = input.skip(left)
            if (k <= 0) {
                if (input.read() < 0) throw EOFException()
                left--
            } else left -= k
        }
    }

    // ---------------------------------------------------------------- AI

    object Ai {
        fun read(file: File): Result {
            val start = head(file, 1024)
            val s = String(start, Charsets.ISO_8859_1)
            if (s.contains("%PDF-")) return Result("", Image.PdfPage(file), "Page 1 (PDF compatible)")
            val thumb = xmpThumbnail(file)
            if (thumb != null) return Result("", Image.Encoded(thumb), "Embedded thumbnail")
            return Result(
                "", null,
                if (s.startsWith("%!PS")) "Saved without \"PDF compatible\" and no thumbnail: no offline preview"
                else "No preview inside this file",
            )
        }

        /** The base64 JPEG in Illustrator's XMP (xmpGImg:image), searched in the first 32 MB. */
        fun xmpThumbnail(file: File, limit: Int = 32 shl 20): ByteArray? {
            val tag = "xmpGImg:image>".toByteArray(Charsets.ISO_8859_1)
            FileInputStream(file).use { input ->
                val buf = BufferedInputStream(input, 1 shl 16)
                var read = 0
                var matched = 0
                while (read < limit) {
                    val c = buf.read()
                    if (c < 0) return null
                    read++
                    matched = if (c == tag[matched].toInt()) matched + 1 else if (c == tag[0].toInt()) 1 else 0
                    if (matched == tag.size) {
                        // Collect up to the closing tag.
                        val sb = StringBuilder()
                        while (true) {
                            val d = buf.read()
                            if (d < 0 || d == '<'.code) break
                            sb.append(d.toChar())
                            if (sb.length > (8 shl 20)) return null
                        }
                        val b64 = sb.toString().replace("&#xA;", "").replace("&#xD;", "").replace("&#10;", "").replace("&#13;", "")
                            .filter { !it.isWhitespace() }
                        if (b64.length < 32) { matched = 0; continue }
                        val bytes = runCatching { Base64.getMimeDecoder().decode(b64) }.getOrNull()
                        if (bytes != null && imageKind(bytes) != null) return bytes
                        matched = 0
                    }
                }
            }
            return null
        }
    }

    // ---------------------------------------------------------------- CDR

    object Cdr {
        fun read(file: File): Result {
            val start = head(file, 16)
            if (startsWith(start, "PK")) {
                ZipFile(file).use { zip ->
                    val candidates = zip.entries().asSequence().filter { e ->
                        val n = e.name.lowercase(Locale.ROOT)
                        !e.isDirectory && (n.contains("thumbnail") || n.startsWith("previews/")) &&
                            (n.endsWith(".png") || n.endsWith(".bmp") || n.endsWith(".jpg") || n.endsWith(".jpeg"))
                    }.sortedByDescending { it.size }.toList()
                    for (e in candidates) {
                        if (e.size > 64L shl 20) continue
                        val bytes = zip.getInputStream(e).use { it.readBytes() }
                        val img = if (imageKind(bytes) != null) bytes else dibToBmp(bytes)
                        if (img != null) return Result("", Image.Encoded(img), "Embedded thumbnail", listOf("Version" to "CorelDRAW X4 or later"))
                    }
                    return Result("", null, "No thumbnail inside this drawing", listOf("Version" to "CorelDRAW X4 or later"))
                }
            }
            if (startsWith(start, "RIFF") && (startsWith(start, "CDR", 8) || startsWith(start, "cdr", 8))) {
                val version = "CorelDRAW " + when (val v = start[11].toInt().toChar()) {
                    in '5'..'9' -> (v - '0').toString()
                    in 'A'..'D' -> (10 + (v - 'A')).toString()
                    else -> "(older)"
                }
                val dib = riffChunk(file, "DISP")
                val bmp = dib?.let { d ->
                    // DISP = a short header, then a DIB: find its BITMAPINFOHEADER.
                    (0..minOf(16, d.size - 40).coerceAtLeast(-1)).asSequence()
                        .filter { o -> le32(d, o) == 40 && le32(d, o + 4) in 1..4096 && le32(d, o + 8) != 0 }
                        .firstNotNullOfOrNull { o -> dibToBmp(d.copyOfRange(o, d.size)) }
                }
                return if (bmp != null) Result("", Image.Encoded(bmp), "Embedded thumbnail", listOf("Version" to version))
                else Result("", null, "No preview inside this drawing", listOf("Version" to version))
            }
            return Result("", null, "Not a CorelDRAW file this app can read")
        }

        /** The first top-level RIFF chunk [id] (≤ 8 MB), or null. */
        internal fun riffChunk(file: File, id: String): ByteArray? {
            RandomAccessFile(file, "r").use { raf ->
                var pos = 12L
                val hdr = ByteArray(8)
                while (pos + 8 <= raf.length()) {
                    raf.seek(pos)
                    raf.readFully(hdr)
                    val size = le32(hdr, 4).toLong() and 0xFFFFFFFFL
                    if (startsWith(hdr, id)) {
                        if (size > (8 shl 20)) return null
                        return ByteArray(size.toInt()).also { raf.readFully(it) }
                    }
                    pos += 8 + size + (size and 1)
                }
            }
            return null
        }
    }

    // ---------------------------------------------------------------- DWG

    object Dwg {
        private val sentinel = intArrayOf(0x1F, 0x25, 0x6D, 0x07, 0xD4, 0x36, 0x28, 0x28, 0x9D, 0x57, 0xCA, 0x3F, 0x9D, 0x44, 0x10, 0x2B)
        private val releases = mapOf(
            "AC1012" to "R13", "AC1014" to "R14", "AC1015" to "AutoCAD 2000", "AC1018" to "AutoCAD 2004",
            "AC1021" to "AutoCAD 2007", "AC1024" to "AutoCAD 2010", "AC1027" to "AutoCAD 2013", "AC1032" to "AutoCAD 2018",
        )

        fun read(file: File): Result {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(6).also { raf.readFully(it) }.toString(Charsets.ISO_8859_1)
                if (!magic.startsWith("AC")) return Result("", null, "Not an AutoCAD drawing")
                val release = releases[magic] ?: if (magic < "AC1012") "R12 or older" else magic
                val details = listOf("Version" to "$release ($magic)")
                if (magic < "AC1012") return Result("", null, "Drawings from R12 or older have no preview", details)
                raf.seek(0x0D)
                val b4 = ByteArray(4).also { raf.readFully(it) }
                val address = le32(b4, 0).toLong() and 0xFFFFFFFFL
                if (address <= 0 || address + 16 > raf.length()) return Result("", null, "No preview inside this drawing", details)
                raf.seek(address)
                for (s in sentinel) if (raf.readUnsignedByte() != s) return Result("", null, "No preview inside this drawing", details)
                raf.skipBytes(4) // overall size
                val count = raf.readUnsignedByte()
                var bmp: ByteArray? = null
                var png: ByteArray? = null
                repeat(count) {
                    val code = raf.readUnsignedByte()
                    val e = ByteArray(8).also { raf.readFully(it) }
                    val start = le32(e, 0).toLong() and 0xFFFFFFFFL
                    val size = le32(e, 4).toLong() and 0xFFFFFFFFL
                    if ((code == 2 || code == 6) && size in 16..(32L shl 20) && start + size <= raf.length()) {
                        val here = raf.filePointer
                        raf.seek(start)
                        val data = ByteArray(size.toInt()).also { raf.readFully(it) }
                        raf.seek(here)
                        if (code == 6 && imageKind(data) == "PNG") png = data
                        if (code == 2) bmp = dibToBmp(data)
                    }
                }
                val img = png ?: bmp
                return if (img != null) Result("", Image.Encoded(img), "Preview saved by AutoCAD", details)
                else Result("", null, "No preview inside this drawing", details)
            }
        }
    }
}
