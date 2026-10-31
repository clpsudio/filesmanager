package com.clpstudio.filesmanager

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.viewer.hasOfficeViewer
import com.clpstudio.filesmanager.viewer.office.Cfb
import com.clpstudio.filesmanager.viewer.office.DocParagraph
import com.clpstudio.filesmanager.viewer.office.DocRun
import com.clpstudio.filesmanager.viewer.office.DocTable
import com.clpstudio.filesmanager.viewer.office.DocxContent
import com.clpstudio.filesmanager.viewer.office.DocxParser
import com.clpstudio.filesmanager.viewer.office.LegacyXls
import com.clpstudio.filesmanager.viewer.office.OfficeFormat
import com.clpstudio.filesmanager.viewer.office.OfficeFormats
import com.clpstudio.filesmanager.viewer.office.OfficeParseException
import com.clpstudio.filesmanager.viewer.office.OfficeThumbnails
import com.clpstudio.filesmanager.viewer.office.PptxContent
import com.clpstudio.filesmanager.viewer.office.PptxParser
import com.clpstudio.filesmanager.viewer.office.SlideItem
import com.clpstudio.filesmanager.viewer.office.XlsxContent
import com.clpstudio.filesmanager.viewer.office.XlsxParser
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** F11 items 10 + 11: Word / Excel / PowerPoint 97–2003 in the app's viewers, and Excel / PowerPoint thumbnails. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class LegacyOfficeTest {
    @get:Rule val compose = createComposeRule()
    private val shots = File("build/screenshots").apply { mkdirs() }.path

    companion object {
        fun res(name: String, as_: String = name): File {
            val dir = File(System.getProperty("java.io.tmpdir"), "legacy-" + System.nanoTime()).apply { mkdirs(); deleteOnExit() }
            val out = File(dir, as_)
            out.deleteOnExit()
            LegacyOfficeTest::class.java.getResourceAsStream("/legacy/$name")!!.use { i -> out.outputStream().use { i.copyTo(it) } }
            return out
        }
    }

    private fun text(p: DocParagraph) = p.runs.joinToString("") { (it as DocRun.Text).text }

    @Test fun oldFormatsGoToTheInAppViewers() {
        for (n in listOf("a.doc", "a.dot", "a.xls", "a.ppt", "a.pps")) assertTrue(n, hasOfficeViewer(n))
        assertEquals(OfficeFormat.DOCX, OfficeFormats.byMime("application/msword"))
        assertEquals(OfficeFormat.XLSX, OfficeFormats.byMime("application/vnd.ms-excel"))
        assertEquals(OfficeFormat.PPTX, OfficeFormats.byMime("application/vnd.ms-powerpoint"))
        // A .doc shared without an extension is recognised from the streams inside.
        assertEquals(OfficeFormat.DOCX, OfficeFormats.sniff(res("SampleDoc.doc", "shared")))
        assertEquals(OfficeFormat.XLSX, OfficeFormats.sniff(res("SampleSS.xls", "shared")))
        assertEquals(OfficeFormat.PPTX, OfficeFormats.sniff(res("SampleShow.ppt", "shared")))
        assertTrue(Cfb.isCfb(res("SampleDoc.doc")))
    }

    @Test fun wordDocTextTablesAndPages() {
        val d = DocxParser.parse(res("SampleDoc.doc"))
        val paras = d.blocks.filterIsInstance<DocParagraph>()
        assertEquals("I am a test document", text(paras[0]))
        assertTrue(paras.any { text(it) == "This is page two" })
        assertTrue("page 2 starts a new page", d.pages().size >= 2)
        val t = DocxParser.parse(res("simple-table.doc"))
        val table = t.blocks.filterIsInstance<DocTable>().single()
        assertEquals(2, table.rows.size)
        assertEquals(listOf("Cell 1,1", "Cell 1,2", "Cell 1,3"), table.rows[0].cells.map { c -> c.blocks.joinToString("") { text(it as DocParagraph) } })
        assertEquals("This text is below the table.", text(t.blocks.last() as DocParagraph))
    }

    @Test fun excelXlsValuesDatesAndSheets() {
        val w = XlsxParser.parse(res("SampleSS.xls"))
        assertEquals(listOf("First Sheet", "Sheet Number 2", "Sheet3"), w.sheets.map { it.name })
        assertEquals("Test spreadsheet", w.sheets[0].cell(0, 0)!!.text)
        assertEquals("2nd row 2nd column", w.sheets[0].cell(1, 1)!!.text)
        val s2 = w.sheets[1]
        assertTrue(s2.cell(3, 0)!!.bold)
        assertEquals("13", s2.cell(6, 3)!!.text) // a formula's result
        assertTrue(s2.cell(6, 3)!!.isNumber)
        val m = XlsxParser.parse(res("SimpleMultiCell.xls")).sheets[0]
        assertEquals("15", m.cell(4, 4)!!.text)
        val dates = XlsxParser.parse(res("DateFormats.xls")).sheets[0]
        assertEquals("2007-08-10", dates.cell(0, 1)!!.text)
        assertEquals(1.5, LegacyXls.rk((150 shl 2) or 0x03), 1e-9)
        assertEquals("0.1", LegacyXls.plain(0.1))
    }

    @Test fun powerPointPptSlidesTitlesAndPictures() {
        val p = PptxParser.parse(res("SampleShow.ppt"))
        assertEquals(2, p.slides.size)
        assertEquals("Title of the first slide", p.slides[0].title)
        assertTrue(p.slides[1].items.filterIsInstance<SlideItem.Paragraph>().any { it.text == "It has bullet points on it" && it.bullet == "•" })
        assertEquals(720f, p.slideWidthPt)
        val box = PptxParser.parse(res("with_textbox.ppt"))
        assertTrue(box.slides[0].items.filterIsInstance<SlideItem.Paragraph>().any { it.text == "Hello, World!!!" }) // a text box, not a placeholder
        val pics = PptxParser.parse(res("pictures.ppt"))
        assertEquals(5, pics.slides.size)
        assertTrue(pics.images.size >= 2)
        pics.images.values.forEach { b -> assertNotNull(android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)) }
    }

    @Test fun damagedOldFileSaysSo() {
        val f = File.createTempFile("broken", ".doc").apply { writeBytes(Cfb.MAGIC + ByteArray(600)) }
        assertTrue(runCatching { DocxParser.parse(f) }.exceptionOrNull() is OfficeParseException)
    }

    @Test fun thumbnailsForExcelPowerPointAndOldFiles_render() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val xlsx = File(ctx.cacheDir, "budget.xlsx").apply { writeBytes(sampleXlsx()) }
        val thumbs = listOf(
            OfficeThumbnails.load(xlsx, OfficeFormat.XLSX, 256),
            OfficeThumbnails.load(res("SampleSS.xls"), OfficeFormat.XLSX, 256),
            OfficeThumbnails.load(res("SampleShow.ppt"), OfficeFormat.PPTX, 256),
            OfficeThumbnails.load(res("pictures.ppt"), OfficeFormat.PPTX, 256),
            OfficeThumbnails.load(res("simple-table.doc"), OfficeFormat.DOCX, 256),
        )
        thumbs.forEachIndexed { i, b -> assertNotNull("thumb $i", b) }
        val strip = Bitmap.createBitmap(thumbs.sumOf { it!!.width + 10 } + 10, thumbs.maxOf { it!!.height } + 20, Bitmap.Config.ARGB_8888)
        val c = Canvas(strip)
        c.drawColor(0xFFEDEFF2.toInt())
        var x = 10f
        for (b in thumbs) { c.drawBitmap(b!!, x, 10f, null); x += b.width + 10 }
        File(shots).mkdirs()
        File(shots, "files_f11_office_thumbs.png").outputStream().use { strip.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A small .xlsx with no stored thumbnail (so the snapshot is drawn). */
    private fun sampleXlsx(): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            fun put(n: String, s: String) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/></Types>""")
            put("_rels/.rels", """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Budget" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            put("xl/_rels/workbook.xml.rels", """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
            val rows = listOf(listOf("Item", "Jan", "Feb", "Mar"), listOf("Rent", "1200", "1200", "1200"), listOf("Food", "430", "385", "512"), listOf("Travel", "90", "260", "75"), listOf("Total", "1720", "1845", "1787"))
            val sheet = StringBuilder("""<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
            rows.forEachIndexed { r, row ->
                sheet.append("<row r=\"${r + 1}\">")
                row.forEachIndexed { c, v ->
                    val ref = "${'A' + c}${r + 1}"
                    if (v.toDoubleOrNull() != null) sheet.append("<c r=\"$ref\"><v>$v</v></c>") else sheet.append("<c r=\"$ref\" t=\"inlineStr\"><is><t>$v</t></is></c>")
                }
                sheet.append("</row>")
            }
            sheet.append("</sheetData></worksheet>")
            put("xl/worksheets/sheet1.xml", sheet.toString())
        }
        return bos.toByteArray()
    }

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { content() } }
        compose.waitUntil(8000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Loading", substring = true)).fetchSemanticsNodes().isEmpty() }
        Thread.sleep(300)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$shots/$name")
    }

    @Test fun oldWordInViewer_render() = shoot("files_f11_doc_viewer.png") { DocxContent(res("simple-table.doc"), Modifier.fillMaxSize()) }

    @Test fun oldExcelInViewer_render() = shoot("files_f11_xls_viewer.png") { XlsxContent(res("SampleSS.xls"), Modifier.fillMaxSize()) }

    @Test fun oldPowerPointInViewer_render() = shoot("files_f11_ppt_viewer.png") { PptxContent(res("SampleShow.ppt"), Modifier.fillMaxSize()) }
}
