package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.DesignPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/** PSD / AI / CDR / DWG previews (the parser is pure JVM; Robolectric only decodes the results to check them). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class DesignPreviewTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(name: String): File {
        val f = File(tmp.root, name)
        javaClass.getResourceAsStream("/fmt/$name")!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private val expected: Bitmap by lazy { BitmapFactory.decodeStream(javaClass.getResourceAsStream("/fmt/art_expected.png"))!! }

    private fun decode(b: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(b, 0, b.size)

    /** Mean absolute difference per channel (0..255) against the expected art; [gray] compares luminance. */
    private fun diff(p: DesignPreview.Image.Pixels, gray: Boolean = false): Double {
        assertEquals(expected.width, p.width)
        assertEquals(expected.height, p.height)
        var sum = 0.0
        for (y in 0 until p.height) for (x in 0 until p.width) {
            val a = p.argb[y * p.width + x]
            val b = expected.getPixel(x, y)
            if (gray) {
                fun lum(c: Int) = 0.299 * ((c shr 16) and 255) + 0.587 * ((c shr 8) and 255) + 0.114 * (c and 255)
                sum += abs(lum(a) - lum(b))
            } else {
                sum += (abs(((a shr 16) and 255) - ((b shr 16) and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))) / 3.0
            }
        }
        return sum / (p.width * p.height)
    }

    private fun pixels(name: String, maxDim: Int = 2048): DesignPreview.Image.Pixels {
        val r = DesignPreview.read(fixture(name), maxDim = maxDim)
        assertTrue("$name: ${r.note}", r.image is DesignPreview.Image.Pixels)
        return r.image as DesignPreview.Image.Pixels
    }

    @Test fun psdRgbRleMatchesTheArt() {
        val d = diff(pixels("art_rgb.psd"))
        assertTrue("rgb diff $d", d < 3)
        val r = DesignPreview.read(fixture("art_rgb.psd"))
        assertEquals("Photoshop document (PSD)", r.format)
        assertTrue(r.details.contains("Dimensions" to "120 × 80"))
    }

    @Test fun psdRawMatchesTheArt() {
        val d = diff(pixels("art_raw.psd"))
        assertTrue("raw diff $d", d < 3)
    }

    @Test fun psdGrayscale() {
        val d = diff(pixels("art_gray.psd"), gray = true)
        assertTrue("gray diff $d", d < 6)
    }

    @Test fun psdCmyk() {
        val d = diff(pixels("art_cmyk.psd"))
        assertTrue("cmyk diff $d", d < 40)
    }

    @Test fun psdIsSampledDownForBigFiles() {
        val p = pixels("art_rgb.psd", maxDim = 40)
        assertEquals(40, p.width)
        assertEquals(27, p.height)
    }

    @Test fun dwgPreviewsFromRealFiles() {
        val r2000 = DesignPreview.read(fixture("real_r2000_prefix.dwg"))
        val bmp = r2000.image as DesignPreview.Image.Encoded
        assertEquals("BMP", DesignPreview.imageKind(bmp.bytes))
        val img = decode(bmp.bytes)!!
        assertTrue(img.width > 16 && img.height > 16)
        assertTrue(r2000.details.first().second.contains("AutoCAD 2000"))

        val r2018 = DesignPreview.read(fixture("real_2018_prefix.dwg"))
        val png = r2018.image as DesignPreview.Image.Encoded
        assertEquals("PNG", DesignPreview.imageKind(png.bytes))
        assertNotNull(decode(png.bytes))
    }

    @Test fun oldDwgHasNoPreview() {
        val f = tmp.newFile("old.dwg").apply { writeBytes("AC1009".toByteArray() + ByteArray(200)) }
        val r = DesignPreview.read(f)
        assertNull(r.image)
        assertTrue(r.note, r.note.contains("R12"))
    }

    private fun smallPng(): ByteArray {
        val img = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
        return ByteArrayOutputStream().also { img.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** A 10×7 24-bit BMP: left half red, right half blue. */
    private fun smallBmp(): ByteArray {
        val w = 10
        val h = 7
        val rowSize = (w * 3 + 3) / 4 * 4
        val data = ByteArray(rowSize * h)
        for (y in 0 until h) for (x in 0 until w) {
            val o = y * rowSize + x * 3
            if (x < 5) data[o + 2] = 0xFF.toByte() else data[o] = 0xFF.toByte() // BGR
        }
        fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        val info = le(40) + le(w) + le(h) + byteArrayOf(1, 0, 24, 0) + le(0) + le(data.size) + le(2835) + le(2835) + le(0) + le(0)
        return "BM".toByteArray() + le(14 + 40 + data.size) + le(0) + le(54) + info + data
    }

    @Test fun aiPdfCompatibleRendersAsPdf() {
        val f = tmp.newFile("logo.ai").apply { writeText("%PDF-1.6\n%âãÏÓ\n1 0 obj<<>>endobj\n") }
        assertTrue(DesignPreview.read(f).image is DesignPreview.Image.PdfPage)
    }

    @Test fun aiPostScriptUsesXmpThumbnail() {
        val b64 = Base64.getMimeEncoder(76, "\n".toByteArray()).encodeToString(smallPng()).replace("\n", "&#xA;")
        val f = tmp.newFile("old.ai").apply {
            writeText("%!PS-Adobe-3.0\n%%Creator: Adobe Illustrator(R) 10\n<x:xmpmeta><xmpGImg:width>8</xmpGImg:width><xmpGImg:image>$b64</xmpGImg:image></x:xmpmeta>\n%%EOF")
        }
        val r = DesignPreview.read(f)
        val enc = r.image as DesignPreview.Image.Encoded
        assertEquals("PNG", DesignPreview.imageKind(enc.bytes))
        assertEquals(8, decode(enc.bytes)!!.width)
    }

    @Test fun aiWithoutPreviewSaysSo() {
        val f = tmp.newFile("bare.ai").apply { writeText("%!PS-Adobe-3.0\n%%EOF") }
        val r = DesignPreview.read(f)
        assertNull(r.image)
        assertTrue(r.note.contains("PDF compatible"))
    }

    @Test fun cdrZipThumbnail() {
        val f = tmp.newFile("card.cdr")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("content/riffData.cdr")); z.write(ByteArray(100)); z.closeEntry()
            z.putNextEntry(ZipEntry("metadata/thumbnails/thumbnail.bmp")); z.write(smallBmp()); z.closeEntry()
        }
        val r = DesignPreview.read(f)
        val enc = r.image as DesignPreview.Image.Encoded
        assertEquals(10, decode(enc.bytes)!!.width)
    }

    @Test fun cdrRiffDispPreview() {
        val dib = smallBmp().copyOfRange(14, smallBmp().size)
        val disp = byteArrayOf(0, 0, 0, 0) + dib
        fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        val vrsn = "vrsn".toByteArray() + le(2) + byteArrayOf(0x20, 0x03)
        val dispChunk = "DISP".toByteArray() + le(disp.size) + disp + (if (disp.size % 2 == 1) byteArrayOf(0) else byteArrayOf())
        val body = "CDR9".toByteArray() + vrsn + dispChunk
        val f = tmp.newFile("old.cdr").apply { writeBytes("RIFF".toByteArray() + le(body.size) + body) }
        val r = DesignPreview.read(f)
        val enc = r.image as DesignPreview.Image.Encoded
        val img = decode(enc.bytes)!!
        assertEquals(10, img.width)
        assertEquals(0xFF0000, img.getPixel(1, 1) and 0xFFFFFF)
        assertTrue(r.details.contains("Version" to "CorelDRAW 9"))
    }

    @Test fun garbageNeverThrows() {
        for (n in listOf("x.psd", "x.ai", "x.cdr", "x.dwg")) {
            val f = tmp.newFile(n).apply { writeBytes(ByteArray(64) { it.toByte() }) }
            val r = DesignPreview.read(f)
            assertNull(n, r.image)
            assertTrue(n, r.note.isNotBlank())
        }
    }

    @Test fun dibToBmpRoundTrip() {
        val bmp = smallBmp()
        val rebuilt = DesignPreview.dibToBmp(bmp.copyOfRange(14, bmp.size))!!
        assertEquals(bmp.size, rebuilt.size)
        assertEquals(bmp.toList(), rebuilt.toList())
        assertEquals(0xFF0000FF.toInt(), decode(rebuilt)!!.getPixel(7, 3))
    }

    @Test fun packBits() {
        // 3 literal bytes, then 4× 0x7F
        val src = byteArrayOf(2, 1, 2, 3, -3, 0x7F)
        val out = ByteArray(7)
        DesignPreview.Psd.unpackBits(src, src.size, out)
        assertEquals(listOf<Byte>(1, 2, 3, 0x7F, 0x7F, 0x7F, 0x7F), out.toList())
    }
}
