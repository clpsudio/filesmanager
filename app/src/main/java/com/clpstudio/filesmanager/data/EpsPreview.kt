package com.clpstudio.filesmanager.data

import java.io.File
import java.io.RandomAccessFile

/**
 * Item 9 (F11): previews inside PostScript-based Illustrator files (saved without "PDF compatible", Illustrator 8
 * and older, or as EPS): the DOS EPS binary header's TIFF preview, or the EPSI hex preview (%%BeginPreview).
 * Decoded offline into pixels; returns null when the file carries neither (or only a WMF preview).
 */
object EpsPreview {
    fun read(file: File, maxDim: Int = 2048): DesignPreview.Image.Pixels? {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(32)
            if (raf.length() < 32) return null
            raf.readFully(head)
            if (head[0] == 0xC5.toByte() && head[1] == 0xD0.toByte() && head[2] == 0xD3.toByte() && head[3] == 0xC6.toByte()) {
                val tiffAt = DesignPreview.le32(head, 20).toLong() and 0xFFFFFFFFL
                val tiffLen = DesignPreview.le32(head, 24).toLong() and 0xFFFFFFFFL
                if (tiffAt > 0 && tiffLen in 8..(64L shl 20) && tiffAt + tiffLen <= raf.length()) {
                    val t = ByteArray(tiffLen.toInt())
                    raf.seek(tiffAt); raf.readFully(t)
                    Tiff.decode(t, maxDim)?.let { return it }
                }
            }
        }
        return epsi(file, maxDim)
    }

    /** %%BeginPreview: w h depth lines, then hex rows ("%" prefixed). */
    fun epsi(file: File, maxDim: Int, limit: Int = 8 shl 20): DesignPreview.Image.Pixels? {
        val text = file.inputStream().use { i -> val b = ByteArray(minOf(file.length(), limit.toLong()).toInt()); var n = 0; while (n < b.size) { val r = i.read(b, n, b.size - n); if (r < 0) break; n += r }; String(b, 0, n, Charsets.ISO_8859_1) }
        val at = text.indexOf("%%BeginPreview:")
        if (at < 0) return null
        val line = text.substring(at + 15, text.indexOf('\n', at).let { if (it < 0) text.length else it }).trim().split(Regex("\\s+"))
        val w = line.getOrNull(0)?.toIntOrNull() ?: return null
        val h = line.getOrNull(1)?.toIntOrNull() ?: return null
        val depth = line.getOrNull(2)?.toIntOrNull() ?: return null
        if (w !in 1..4096 || h !in 1..4096 || depth !in listOf(1, 2, 4, 8)) return null
        val end = text.indexOf("%%EndPreview", at).let { if (it < 0) text.length else it }
        val hex = StringBuilder()
        for (l in text.substring(text.indexOf('\n', at) + 1, end).lineSequence()) hex.append(l.trimStart('%').filter { it.isLetterOrDigit() })
        val rowBytes = (w * depth + 7) / 8
        val bytes = ByteArray(rowBytes * h)
        var k = 0
        while (k < bytes.size && 2 * k + 1 < hex.length) { bytes[k] = hex.substring(2 * k, 2 * k + 2).toInt(16).toByte(); k++ }
        if (k < bytes.size) return null
        val max = (1 shl depth) - 1
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val bit = x * depth
            val b = bytes[y * rowBytes + bit / 8].toInt() and 0xFF
            val v = (b shr (8 - depth - bit % 8)) and max
            // EPSI: 0 = white, max = black.
            val g = 255 - v * 255 / max
            px[y * w + x] = DesignPreview.rgb(g, g, g)
        }
        return Tiff.scale(DesignPreview.Image.Pixels(w, h, px), maxDim)
    }
}

/** A small baseline TIFF decoder (strips; none / PackBits / LZW; gray, RGB(A), palette; 1 / 8 bits). */
object Tiff {
    fun decode(b: ByteArray, maxDim: Int): DesignPreview.Image.Pixels? = runCatching { decodeOrThrow(b, maxDim) }.getOrNull()

    private fun decodeOrThrow(b: ByteArray, maxDim: Int): DesignPreview.Image.Pixels? {
        val le = when { b[0] == 'I'.code.toByte() && b[1] == 'I'.code.toByte() -> true; b[0] == 'M'.code.toByte() && b[1] == 'M'.code.toByte() -> false; else -> return null }
        fun u16(o: Int) = if (le) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        fun u32(o: Int) = if (le) u16(o) or (u16(o + 2) shl 16) else (u16(o) shl 16) or u16(o + 2)
        if (u16(2) != 42) return null
        val ifd = u32(4)
        val n = u16(ifd)
        val tags = HashMap<Int, IntArray>()
        for (i in 0 until n) {
            val e = ifd + 2 + i * 12
            val tag = u16(e); val type = u16(e + 2); val count = u32(e + 4)
            val size = when (type) { 3 -> 2; 4 -> 4; 1, 2, 7 -> 1; else -> 4 }
            if (count > 1 shl 20) continue
            val at = if (count * size <= 4) e + 8 else u32(e + 8)
            tags[tag] = IntArray(count) { k -> when (size) { 2 -> u16(at + k * 2); 4 -> u32(at + k * 4); else -> b[at + k].toInt() and 0xFF } }
        }
        val w = tags[256]?.get(0) ?: return null
        val h = tags[257]?.get(0) ?: return null
        if (w !in 1..8192 || h !in 1..8192) return null
        val bps = tags[258]?.get(0) ?: 1
        val spp = tags[277]?.get(0) ?: 1
        val comp = tags[259]?.get(0) ?: 1
        val photo = tags[262]?.get(0) ?: 1
        val predictor = tags[317]?.get(0) ?: 1
        val offsets = tags[273] ?: return null
        val counts = tags[279] ?: return null
        val rps = tags[278]?.get(0) ?: h
        if (bps != 8 && bps != 1) return null
        if (tags[284]?.get(0) == 2) return null // planar
        val rowBytes = (w * spp * bps + 7) / 8
        val data = ByteArray(rowBytes * h)
        var pos = 0
        for (s in offsets.indices) {
            val raw = b.copyOfRange(offsets[s], (offsets[s] + counts.getOrElse(s) { 0 }).coerceAtMost(b.size))
            val want = minOf(rowBytes * rps, data.size - pos)
            val strip = when (comp) {
                1 -> raw
                32773 -> packBits(raw, want)
                5 -> lzw(raw, want)
                else -> return null
            }
            System.arraycopy(strip, 0, data, pos, minOf(want, strip.size))
            pos += want
            if (pos >= data.size) break
        }
        if (predictor == 2 && bps == 8) for (y in 0 until h) for (x in spp until w * spp) {
            val i = y * rowBytes + x
            data[i] = (data[i] + data[i - spp]).toByte()
        }
        val map = tags[320]
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val c: Int = if (bps == 1) {
                val v = (data[y * rowBytes + x / 8].toInt() shr (7 - x % 8)) and 1
                val on = if (photo == 0) v == 0 else v == 1
                if (on) DesignPreview.rgb(255, 255, 255) else DesignPreview.rgb(0, 0, 0)
            } else {
                val i = y * rowBytes + x * spp
                fun v(k: Int) = data[i + k].toInt() and 0xFF
                when (photo) {
                    0 -> (255 - v(0)).let { DesignPreview.rgb(it, it, it) }
                    1 -> DesignPreview.rgb(v(0), v(0), v(0))
                    2 -> if (spp >= 3) DesignPreview.rgb(v(0), v(1), v(2)) else return null
                    3 -> if (map != null && map.size >= 768) DesignPreview.rgb(map[v(0)] shr 8, map[256 + v(0)] shr 8, map[512 + v(0)] shr 8) else return null
                    5 -> if (spp >= 4) { val k = v(3); DesignPreview.rgb((255 - v(0)) * (255 - k) / 255, (255 - v(1)) * (255 - k) / 255, (255 - v(2)) * (255 - k) / 255) } else return null
                    else -> return null
                }
            }
            px[y * w + x] = c
        }
        return scale(DesignPreview.Image.Pixels(w, h, px), maxDim)
    }

    internal fun packBits(src: ByteArray, want: Int): ByteArray {
        val out = ByteArray(want)
        var i = 0; var o = 0
        while (i < src.size && o < want) {
            val n = src[i++].toInt()
            if (n >= 0) { val c = minOf(n + 1, want - o, src.size - i); System.arraycopy(src, i, out, o, c); i += n + 1; o += c }
            else if (n != -128) { if (i >= src.size) break; val v = src[i++]; val c = minOf(1 - n, want - o); out.fill(v, o, o + c); o += c }
        }
        return out
    }

    internal fun lzw(src: ByteArray, want: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(want)
        val dict = arrayOfNulls<ByteArray>(4096)
        for (k in 0 until 256) dict[k] = byteArrayOf(k.toByte())
        var next = 258; var width = 9
        var bitPos = 0L
        var prev: ByteArray? = null
        fun read(): Int {
            var v = 0
            for (k in 0 until width) {
                val byte = (bitPos shr 3).toInt()
                if (byte >= src.size) return 257
                v = (v shl 1) or ((src[byte].toInt() shr (7 - (bitPos and 7).toInt())) and 1)
                bitPos++
            }
            return v
        }
        while (out.size() < want) {
            val code = read()
            if (code == 257) break
            if (code == 256) { next = 258; width = 9; prev = null; continue }
            val entry = when {
                code < next && dict[code] != null -> dict[code]!!
                code == next && prev != null -> prev + prev[0]
                else -> break
            }
            out.write(entry)
            if (prev != null && next < 4096) { dict[next++] = prev + entry[0] }
            prev = entry
            if (next + 1 >= (1 shl width) && width < 12) width++
        }
        return out.toByteArray()
    }

    fun scale(p: DesignPreview.Image.Pixels, maxDim: Int): DesignPreview.Image.Pixels {
        val s = maxOf(1, (maxOf(p.width, p.height) + maxDim - 1) / maxDim)
        if (s == 1) return p
        val w = p.width / s; val h = p.height / s
        return DesignPreview.Image.Pixels(w, h, IntArray(w * h) { i -> p.argb[(i / w) * s * p.width + (i % w) * s] })
    }
}
