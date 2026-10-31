package com.clpstudio.filesmanager

import android.app.Application
import com.clpstudio.filesmanager.viewer.office.OfficeParseException
import com.clpstudio.filesmanager.viewer.office.PptxParser
import com.clpstudio.filesmanager.viewer.office.SlideItem
import com.clpstudio.filesmanager.viewer.office.XlsxParser
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class XlsxPptxParserTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun parsesWorkbook() {
        val book = XlsxParser.parse(SampleOffice2.xlsx(tmp.newFile("s.xlsx")))
        assertEquals(listOf("Sales", "Notes"), book.sheets.map { it.name })
        val s = book.sheets[0]
        assertEquals(5, s.rowCount)
        assertEquals(5, s.columnCount)
        assertEquals("Item", s.cell(0, 0)?.text)
        assertTrue(s.cell(0, 0)!!.bold)
        assertEquals("#DDEBF7", s.cell(0, 0)?.fillHex)
        assertEquals("Share", s.cell(0, 3)?.text)
        assertEquals("Apple pie", s.cell(1, 0)?.text)
        assertEquals("₹1,234.50", s.cell(1, 1)?.text)
        assertTrue(s.cell(1, 1)!!.isNumber)
        assertEquals("31/10/2026", s.cell(1, 2)?.text) // serial 46326
        assertEquals("12.50%", s.cell(1, 3)?.text)
        assertEquals("0.1", s.cell(2, 3)?.text)
        assertTrue(s.rows[3].isEmpty())
        assertEquals("Total", s.cell(4, 0)?.text)
        assertEquals("1333.5", s.cell(4, 1)?.text)
        assertEquals("TRUE", s.cell(4, 4)?.text)
        assertEquals(20f, s.columnWidths[0])
        assertEquals("Just a note", book.sheets[1].cell(0, 0)?.text)
        assertEquals("AA", XlsxParser.columnName(26))
    }

    @Test fun parsesPresentation() {
        val deck = PptxParser.parse(SampleOffice2.pptx(tmp.newFile("s.pptx")))
        assertEquals(2, deck.slides.size)
        assertEquals(960f, deck.slideWidthPt, 0.1f)
        val s1 = deck.slides[0]
        assertEquals("Files app", s1.title)
        val paras = s1.items.filterIsInstance<SlideItem.Paragraph>()
        assertTrue(paras.first().isTitle)
        assertEquals("Fast and offline", paras[1].text)
        assertTrue(paras[1].runs.first().bold)
        assertEquals("•", paras[1].bullet)
        assertEquals(1, paras[2].level)
        assertEquals("#C00000", paras[2].runs.first().colorHex)
        assertEquals(16f, paras[2].runs.first().sizePt)
        assertEquals(listOf("1.", "2."), paras.filter { it.text == "First" || it.text == "Second" }.map { it.bullet })
        assertFalse(paras.any { it.text == "FOOTER" })

        val s2 = deck.slides[1]
        assertEquals("Second slide", s2.title)
        val pic = s2.items.filterIsInstance<SlideItem.Picture>().single()
        assertEquals("ppt/media/image1.png", pic.path)
        assertEquals("Chart", pic.description)
        assertNotNull(deck.images[pic.path])
        val table = s2.items.filterIsInstance<SlideItem.Table>().single()
        assertEquals(listOf(listOf("Name", "Size"), listOf("photo.jpg", "2 MB")), table.rows)
        // Order: title, picture, table.
        assertTrue(s2.items.indexOf(pic) < s2.items.indexOf(table))
    }

    @Test(expected = OfficeParseException::class) fun xlsxNotZip() {
        val f = tmp.newFile("x.xlsx")
        f.writeText("nope")
        XlsxParser.parse(f)
    }

    @Test(expected = OfficeParseException::class) fun pptxWithoutPresentation() {
        val f = tmp.newFile("x.pptx")
        SampleOffice2.xlsx(f)
        assertNull(PptxParser.parse(f))
    }
}
