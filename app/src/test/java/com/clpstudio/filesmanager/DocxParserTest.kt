package com.clpstudio.filesmanager

import android.app.Application
import android.graphics.Bitmap
import com.clpstudio.filesmanager.viewer.office.DocAlign
import com.clpstudio.filesmanager.viewer.office.DocParagraph
import com.clpstudio.filesmanager.viewer.office.DocRun
import com.clpstudio.filesmanager.viewer.office.DocTable
import com.clpstudio.filesmanager.viewer.office.DocxParser
import com.clpstudio.filesmanager.viewer.office.OfficeParseException
import com.clpstudio.filesmanager.viewer.office.decodeOfficeImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.clpstudio.filesmanager.SampleOffice.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DocxParserTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun parsesSampleDocument() {
        val doc = DocxParser.parse(SampleOffice.docx(tmp.newFile("sample.docx")))
        val paras = doc.blocks.filterIsInstance<DocParagraph>()

        // Heading via the Heading1 style (size 16pt from styles.xml).
        val heading = paras.first()
        assertEquals("Quarterly report", heading.text)
        assertEquals(1, heading.headingLevel)
        assertEquals(DocAlign.CENTER, heading.align)
        val hRun = heading.runs.first() as DocRun.Text
        assertEquals(16f, hRun.sizePt)
        assertTrue(hRun.bold)

        // Bold + italic run, plain run, coloured run.
        val body = paras.first { it.text.startsWith("Hello") }
        val runs = body.runs.filterIsInstance<DocRun.Text>()
        val bi = runs.first { it.text == "Hello " }
        assertTrue(bi.bold && bi.italic)
        assertFalse(bi.underline)
        assertEquals(11f, bi.sizePt) // docDefaults sz=22 half-points
        val red = runs.first { it.text == "red text" }
        assertEquals("#C00000", red.colorHex)
        assertEquals(14f, red.sizePt)
        assertTrue(red.underline && red.strike)

        // Bullet list (numId 1) and numbered list (numId 2) with levels.
        val bullets = paras.filter { it.listMarker == "•" }
        assertEquals(listOf("Apples", "Pears"), bullets.map { it.text })
        val numbered = paras.filter { it.text.startsWith("Step") }
        assertEquals(listOf("1.", "2.", "a)", "3."), numbered.map { it.listMarker })
        assertEquals(1, numbered[2].listLevel)
        assertTrue(numbered[2].indentStartPt > numbered[0].indentStartPt)

        // 2x2 table with borders.
        val table = doc.blocks.filterIsInstance<DocTable>().single()
        assertEquals(2, table.rows.size)
        assertEquals(2, table.rows[0].cells.size)
        assertEquals("B2", (table.rows[1].cells[1].blocks.single() as DocParagraph).text)
        assertTrue(table.borders)
        assertEquals(listOf(144f, 144f), table.columnWidthsPt)

        // Page break: the paragraph after the break starts a new page.
        val afterBreak = paras.first { it.text == "Second page" }
        assertTrue(afterBreak.pageBreakBefore)
        val pages = doc.pages()
        assertEquals(2, pages.size)
        assertTrue(pages[1].first() === afterBreak)

        // Hyperlink (relationship) shown as a link run.
        val link = paras.flatMap { it.runs }.filterIsInstance<DocRun.Text>().first { it.hyperlink != null }
        assertEquals("example site", link.text)
        assertEquals("https://example.com/", link.hyperlink)
        assertTrue(link.underline)
        assertEquals("#0563C1", link.colorHex)

        // Inline image with size from the drawing extent (EMU -> pt) and its bytes.
        val img = paras.flatMap { it.runs }.filterIsInstance<DocRun.Image>().single()
        assertEquals("word/media/image1.png", img.path)
        assertEquals(72f, img.widthPt, 0.01f)
        assertEquals(36f, img.heightPt, 0.01f)
        assertEquals("Logo", img.description)
        val bytes = doc.images[img.path]
        assertNotNull(bytes)
        val bmp = decodeOfficeImage(bytes!!)
        assertNotNull(bmp)
        assertEquals(4, bmp!!.width)

        // Hidden text and field instructions are not shown.
        assertFalse(paras.any { it.text.contains("SECRET") || it.text.contains("PAGE") })
        assertFalse(doc.truncated)
    }

    @Test fun malformedXmlKeepsWhatWasParsed() {
        val f = tmp.newFile("broken.docx")
        ZipOutputStream(f.outputStream()).use { z ->
            z.put("word/document.xml", """<?xml version="1.0"?><w:document xmlns:w="${SampleOffice.W}"><w:body><w:p><w:r><w:t>Still here</w:t></w:r></w:p><w:p><w:r><w:t>cut""")
        }
        val doc = DocxParser.parse(f)
        assertEquals("Still here", (doc.blocks.first() as DocParagraph).text)
        assertTrue(doc.truncated)
    }

    @Test(expected = OfficeParseException::class) fun notAZipIsAClearError() {
        val f = tmp.newFile("fake.docx")
        f.writeText("hello, not a zip")
        DocxParser.parse(f)
    }

    @Test(expected = OfficeParseException::class) fun zipWithoutDocumentIsAClearError() {
        val f = tmp.newFile("empty.docx")
        ZipOutputStream(f.outputStream()).use { z -> z.put("hello.txt", "hi") }
        DocxParser.parse(f)
    }

    @Test fun formatsNumbers() {
        assertNull(null)
        assertEquals("iv", DocxParser.formatNumber(4, "lowerRoman"))
        assertEquals("AA", DocxParser.formatNumber(27, "upperLetter"))
        assertEquals("03", DocxParser.formatNumber(3, "decimalZero"))
    }
}

/** Builders for small sample Office files used by the tests and screenshots. */
object SampleOffice {
    const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val PR = "http://schemas.openxmlformats.org/package/2006/relationships"

    fun ZipOutputStream.put(name: String, text: String) = put(name, text.toByteArray())
    fun ZipOutputStream.put(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    fun png(w: Int = 4, h: Int = 2, color: Int = 0xFF1E6FF0.toInt()): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun p(text: String, pPr: String = "", rPr: String = "") =
        """<w:p><w:pPr>$pPr</w:pPr><w:r><w:rPr>$rPr</w:rPr><w:t xml:space="preserve">$text</w:t></w:r></w:p>"""

    private fun cell(text: String) = """<w:tc><w:tcPr><w:tcW w:w="2880" w:type="dxa"/></w:tcPr>${p(text)}</w:tc>"""

    fun docx(f: File, image: ByteArray = png(), extraParagraphs: Int = 0): File {
        val body = buildString {
            append(p("Quarterly report", """<w:pStyle w:val="Heading1"/><w:jc w:val="center"/>"""))
            append("""<w:p><w:r><w:rPr><w:b/><w:i/></w:rPr><w:t xml:space="preserve">Hello </w:t></w:r>""")
            append("""<w:r><w:t xml:space="preserve">world, this is </w:t></w:r>""")
            append("""<w:r><w:rPr><w:color w:val="c00000"/><w:sz w:val="28"/><w:u w:val="single"/><w:strike/></w:rPr><w:t>red text</w:t></w:r>""")
            append("""<w:r><w:rPr><w:vanish/></w:rPr><w:t>SECRET</w:t></w:r>""")
            append("""<w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>.</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>""")
            append(p("Apples", """<w:pStyle w:val="ListParagraph"/><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr>"""))
            append(p("Pears", """<w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr>"""))
            append(p("Step one", """<w:numPr><w:ilvl w:val="0"/><w:numId w:val="2"/></w:numPr>"""))
            append(p("Step two", """<w:numPr><w:ilvl w:val="0"/><w:numId w:val="2"/></w:numPr>"""))
            append(p("Step two, part a", """<w:numPr><w:ilvl w:val="1"/><w:numId w:val="2"/></w:numPr>"""))
            append(p("Step three", """<w:numPr><w:ilvl w:val="0"/><w:numId w:val="2"/></w:numPr>"""))
            append("""<w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/><w:tblW w:w="0" w:type="auto"/></w:tblPr><w:tblGrid><w:gridCol w:w="2880"/><w:gridCol w:w="2880"/></w:tblGrid>""")
            append("""<w:tr>${cell("A1")}${cell("B1")}</w:tr><w:tr>${cell("A2")}${cell("B2")}</w:tr></w:tbl>""")
            append("""<w:p><w:r><w:t xml:space="preserve">Visit the </w:t></w:r><w:hyperlink r:id="rId9" w:history="1"><w:r><w:rPr><w:rStyle w:val="Hyperlink"/></w:rPr><w:t>example site</w:t></w:r></w:hyperlink><w:r><w:t>.</w:t></w:r></w:p>""")
            append("""<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="914400" cy="457200"/><wp:docPr id="1" name="Picture 1" descr="Logo"/><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="0" name="image1.png"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="rId5"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="914400" cy="457200"/></a:xfrm><a:prstGeom prst="rect"/></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>""")
            repeat(extraParagraphs) { i -> append(p("Paragraph ${i + 1}: the quick brown fox jumps over the lazy dog, again and again, to fill a page.")) }
            append("""<w:p><w:r><w:t>End of page one</w:t></w:r><w:r><w:br w:type="page"/></w:r><w:r><w:t>Second page</w:t></w:r></w:p>""")
            append(p("More text on the second page.", rPr = """<w:i/>"""))
            append("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>""")
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="$W" xmlns:r="$R" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>$body</w:body></w:document>"""
        val styles = """<?xml version="1.0" encoding="UTF-8"?>
<w:styles xmlns:w="$W"><w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="160"/></w:pPr></w:pPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="240" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:color w:val="1F3864"/><w:sz w:val="32"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/></w:style>
<w:style w:type="character" w:styleId="Hyperlink"><w:name w:val="Hyperlink"/><w:rPr><w:color w:val="0563C1"/><w:u w:val="single"/></w:rPr></w:style>
<w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/><w:tblPr><w:tblBorders><w:top w:val="single" w:sz="4"/><w:left w:val="single" w:sz="4"/><w:bottom w:val="single" w:sz="4"/><w:right w:val="single" w:sz="4"/><w:insideH w:val="single" w:sz="4"/><w:insideV w:val="single" w:sz="4"/></w:tblBorders></w:tblPr></w:style>
</w:styles>"""
        val numbering = """<?xml version="1.0" encoding="UTF-8"?>
<w:numbering xmlns:w="$W">
<w:abstractNum w:abstractNumId="10"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="&#61623;"/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr><w:rPr><w:rFonts w:ascii="Symbol" w:hAnsi="Symbol"/></w:rPr></w:lvl></w:abstractNum>
<w:abstractNum w:abstractNumId="20"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl><w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="lowerLetter"/><w:lvlText w:val="%2)"/><w:pPr><w:ind w:left="1440" w:hanging="360"/></w:pPr></w:lvl></w:abstractNum>
<w:num w:numId="1"><w:abstractNumId w:val="10"/></w:num>
<w:num w:numId="2"><w:abstractNumId w:val="20"/></w:num>
</w:numbering>"""
        val rels = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="$PR">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>
<Relationship Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/>
<Relationship Id="rId9" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://example.com/" TargetMode="External"/>
</Relationships>"""
        val rootRels = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="$PR"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
        val types = """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
        ZipOutputStream(f.outputStream()).use { z ->
            z.put("[Content_Types].xml", types)
            z.put("_rels/.rels", rootRels)
            z.put("word/document.xml", document)
            z.put("word/styles.xml", styles)
            z.put("word/numbering.xml", numbering)
            z.put("word/_rels/document.xml.rels", rels)
            z.put("word/media/image1.png", image)
        }
        return f
    }
}
