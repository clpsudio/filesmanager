package com.clpstudio.filesmanager.viewer.office

import java.io.File
import java.io.RandomAccessFile

/**
 * Item 11 (F11): a small reader for Microsoft's Compound File Binary format (OLE2 / "structured storage"), the
 * container of Word 97–2003 .doc, Excel 97–2003 .xls and PowerPoint 97–2003 .ppt. Read-only, offline, bounded.
 */
class Cfb private constructor(private val raf: RandomAccessFile) : AutoCloseable {
    data class Entry(val name: String, val type: Int, val start: Int, val size: Long, val left: Int, val right: Int, val child: Int)

    private var sectorSize = 512
    private var miniSectorSize = 64
    private var miniCutoff = 4096L
    private lateinit var fat: IntArray
    private var miniFat: IntArray = IntArray(0)
    lateinit var entries: List<Entry>
        private set
    private var miniStream: ByteArray = ByteArray(0)

    companion object {
        val MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        private const val END = -2
        private const val FREE = -1
        const val MAX_STREAM = 256L shl 20

        fun isCfb(file: File): Boolean = runCatching {
            RandomAccessFile(file, "r").use { r -> if (r.length() < 512) false else ByteArray(8).also { r.readFully(it) }.contentEquals(MAGIC) }
        }.getOrDefault(false)

        fun open(file: File): Cfb {
            val raf = RandomAccessFile(file, "r")
            try {
                return Cfb(raf).apply { load() }
            } catch (e: Exception) {
                raf.close()
                throw OfficeParseException("This old Office file is damaged or isn't a real Office file.", e)
            }
        }
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)

    private fun sector(n: Int): ByteArray {
        val b = ByteArray(sectorSize)
        val pos = (n.toLong() + 1) * sectorSize
        require(n >= 0 && pos + sectorSize <= raf.length() + sectorSize) { "bad sector $n" }
        raf.seek(pos)
        val read = raf.read(b)
        if (read < sectorSize && read >= 0) b.fill(0, read, sectorSize)
        return b
    }

    private fun load() {
        val h = ByteArray(512)
        raf.seek(0); raf.readFully(h)
        require(h.copyOfRange(0, 8).contentEquals(MAGIC)) { "not CFB" }
        sectorSize = 1 shl le16(h, 0x1E)
        require(sectorSize == 512 || sectorSize == 4096)
        miniSectorSize = 1 shl le16(h, 0x20)
        val fatSectors = le32(h, 0x2C)
        val dirStart = le32(h, 0x30)
        miniCutoff = (le32(h, 0x38).toLong() and 0xFFFFFFFFL)
        val miniFatStart = le32(h, 0x3C)
        val miniFatCount = le32(h, 0x40)
        var difatStart = le32(h, 0x44)
        val difatCount = le32(h, 0x48)
        require(fatSectors in 0..(1 shl 20))
        val fatList = ArrayList<Int>()
        for (i in 0 until 109) { val s = le32(h, 0x4C + i * 4); if (s >= 0) fatList.add(s) }
        var guard = 0
        while (difatStart >= 0 && guard++ < difatCount + 1 && fatList.size < fatSectors) {
            val d = sector(difatStart)
            val per = sectorSize / 4 - 1
            for (i in 0 until per) { val s = le32(d, i * 4); if (s >= 0) fatList.add(s) }
            difatStart = le32(d, per * 4)
        }
        val per = sectorSize / 4
        fat = IntArray(fatList.size * per)
        fatList.forEachIndexed { i, s -> val d = sector(s); for (j in 0 until per) fat[i * per + j] = le32(d, j * 4) }
        val dir = chain(dirStart, Long.MAX_VALUE)
        entries = (0 until dir.size / 128).map { i ->
            val o = i * 128
            val nameLen = le16(dir, o + 0x40).coerceIn(0, 64)
            val name = String(dir, o, (nameLen - 2).coerceAtLeast(0), Charsets.UTF_16LE)
            val size = if (sectorSize == 512) (le32(dir, o + 0x78).toLong() and 0xFFFFFFFFL) else (le32(dir, o + 0x78).toLong() and 0xFFFFFFFFL) or (le32(dir, o + 0x7C).toLong() shl 32)
            Entry(name, dir[o + 0x42].toInt(), le32(dir, o + 0x74), size, le32(dir, o + 0x44), le32(dir, o + 0x48), le32(dir, o + 0x4C))
        }
        if (miniFatCount > 0 && miniFatStart >= 0) {
            val mf = chain(miniFatStart, miniFatCount.toLong() * sectorSize)
            miniFat = IntArray(mf.size / 4) { le32(mf, it * 4) }
        }
        val root = entries.firstOrNull { it.type == 5 }
        if (root != null && root.start >= 0 && root.size > 0) miniStream = chain(root.start, root.size)
    }

    private fun chain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var n = 0
        val limit = minOf(size, MAX_STREAM)
        while (s >= 0 && s != END && s != FREE && out.size() < limit) {
            require(s < fat.size && n++ < fat.size) { "broken sector chain" }
            out.write(sector(s))
            s = fat[s]
        }
        val b = out.toByteArray()
        return if (size != Long.MAX_VALUE && b.size > limit) b.copyOf(limit.toInt()) else b
    }

    private fun miniChain(start: Int, size: Long): ByteArray {
        val out = ByteArray(size.toInt())
        var s = start
        var pos = 0
        var n = 0
        while (s >= 0 && pos < out.size) {
            require(s < miniFat.size && n++ <= miniFat.size) { "broken mini chain" }
            val off = s * miniSectorSize
            val len = minOf(miniSectorSize, out.size - pos)
            if (off + len > miniStream.size) break
            System.arraycopy(miniStream, off, out, pos, len)
            pos += len
            s = miniFat[s]
        }
        return out
    }

    /** A stream by name (any depth; first match), or null. */
    fun stream(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name.equals(name, ignoreCase = true) } ?: return null
        if (e.size > MAX_STREAM) throw OfficeParseException("This file is too big to open here.")
        return if (e.size < miniCutoff) miniChain(e.start, e.size) else chain(e.start, e.size)
    }

    fun has(name: String): Boolean = entries.any { it.type == 2 && it.name.equals(name, ignoreCase = true) }

    override fun close() = raf.close()
}

/** Little-endian helpers shared by the legacy readers. */
internal object Le {
    fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    fun i32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
    fun u32(b: ByteArray, o: Int) = i32(b, o).toLong() and 0xFFFFFFFFL
    fun f64(b: ByteArray, o: Int): Double {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return java.lang.Double.longBitsToDouble(v)
    }
}
