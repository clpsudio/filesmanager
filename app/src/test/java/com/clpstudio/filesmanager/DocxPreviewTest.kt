package com.clpstudio.filesmanager

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.thumbs.OfficeThumb
import com.clpstudio.filesmanager.thumbs.thumbModel
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.viewer.OfficeBody
import com.clpstudio.filesmanager.viewer.hasOfficeViewer
import com.clpstudio.filesmanager.viewer.office.DocParagraph
import com.clpstudio.filesmanager.viewer.office.DocRun
import com.clpstudio.filesmanager.viewer.office.DocTable
import com.clpstudio.filesmanager.viewer.office.DocBlock
import com.clpstudio.filesmanager.viewer.office.DocxParser
import com.clpstudio.filesmanager.viewer.office.DocxSnapshot
import com.clpstudio.filesmanager.viewer.office.OfficeFormat
import com.clpstudio.filesmanager.viewer.office.OfficeFormats
import com.clpstudio.filesmanager.viewer.office.OfficeThumbnails
import com.github.takahirom.roborazzi.captureRoboImage
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * "Preview not showing for docx": real-world .docx samples (python-docx, content controls +
 * mc:AlternateContent, Google-Docs style) parse, render, get a thumbnail, and open by MIME type.
 * ./gradlew :app:testDebugUnitTest --tests '*DocxPreviewTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class DocxPreviewTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun sample(name: String, saveAs: String = name): File {
        val f = File(tmp.root, saveAs)
        javaClass.classLoader!!.getResourceAsStream("docx/$name")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    private fun allParagraphs(blocks: List<DocBlock>): List<DocParagraph> = blocks.flatMap { b ->
        when (b) {
            is DocParagraph -> listOf(b)
            is DocTable -> b.rows.flatMap { r -> r.cells.flatMap { allParagraphs(it.blocks) } }
        }
    }

    private fun texts(p: DocParagraph) = p.runs.filterIsInstance<DocRun.Text>()

    @Test fun pythonDocxSampleParses() {
        val doc = DocxParser.parse(sample("word_sample.docx"))
        val paras = doc.blocks.filterIsInstance<DocParagraph>()
        assertEquals("Quarterly Project Report", paras.first().text)
        assertEquals(1, paras.first().headingLevel) // Title
        assertEquals(1, paras.first { it.text == "1. Summary" }.headingLevel)
        assertEquals(2, paras.first { it.text == "2. Numbers" }.headingLevel)
        val body = texts(paras.first { it.text.startsWith("This report") })
        assertTrue(body.first { it.text == "bold" }.bold)
        assertTrue(body.first { it.text == "italic" }.italic)
        assertTrue(body.first { it.text == "underlined" }.underline)
        assertEquals("#1E6FF0", body.first { it.text == "Blue text." }.colorHex)
        val bullets = paras.filter { it.text.endsWith("bullet point") }
        assertEquals(3, bullets.size)
        assertTrue(bullets.all { it.listMarker != null })
        val numbered = paras.filter { it.text.startsWith("Step") }
        assertEquals(listOf("1.", "2."), numbered.map { it.listMarker })
        val table = doc.blocks.filterIsInstance<DocTable>().single()
        assertEquals(3, table.rows.size)
        assertEquals("Mangoes", allParagraphs(table.rows[2].cells[0].blocks).first().text)
        val img = paras.flatMap { it.runs }.filterIsInstance<DocRun.Image>().single()
        assertNotNull(doc.images[img.path])
        assertTrue(img.widthPt > 100f)
    }

    @Test fun contentControlsAlternateContentAndTrackedChanges() {
        val doc = DocxParser.parse(sample("wrapped_sdt_altcontent.docx"))
        val all = allParagraphs(doc.blocks)
        val text = all.joinToString("\n") { it.text }
        assertEquals(1, all.first { it.text == "Wrapped Contract Title" }.headingLevel)
        assertTrue(texts(all.first { it.text.startsWith("Client") }).first { it.text == "Ravi Kumar" }.bold)
        assertTrue(text.contains("Client: Ravi Kumar from Hyderabad"))
        assertTrue(text.contains("Choice text shown"))
        assertFalse(text.contains("FALLBACK"))
        assertTrue(text.contains("Inserted words."))
        assertFalse(text.contains("DELETED"))
        assertTrue(text.contains("Inner cell")) // table inside a table
        assertTrue(text.contains("Signature block"))
        assertEquals("https://example.com", texts(all.first { it.text.contains("our site") }).first { it.text == "our site" }.hyperlink)
        assertEquals(1, all.flatMap { it.runs }.filterIsInstance<DocRun.Image>().size) // Choice drawing, not the VML fallback too
        assertEquals(1, doc.images.size)
    }

    @Test fun googleDocsStyleParses() {
        val doc = DocxParser.parse(sample("google_docs_style.docx"))
        val paras = doc.blocks.filterIsInstance<DocParagraph>()
        assertEquals("Trip Plan Goa", paras.first().text)
        assertEquals(1, paras.first().headingLevel)
        assertEquals(26f, (paras.first().runs.first() as DocRun.Text).sizePt)
        assertEquals(1, paras.first { it.text == "Packing" }.headingLevel)
        assertEquals("●", paras.first { it.text == "Sunscreen" }.listMarker)
        assertEquals("○", paras.first { it.text == "SPF 50" }.listMarker)
        assertEquals(1, paras.first { it.text == "SPF 50" }.listLevel)
        assertEquals(listOf("1.", "2."), paras.filter { it.text.startsWith("Book") }.map { it.listMarker })
        assertTrue(texts(paras.first { it.text.startsWith("Budget") }).first().bold)
        assertTrue(texts(paras.first { it.text.startsWith("Budget") })[1].italic)
        assertEquals(3, doc.blocks.filterIsInstance<DocTable>().single().rows.size)
        // Image with an absolute target ("/word/media/image1.png").
        val img = paras.flatMap { it.runs }.filterIsInstance<DocRun.Image>().single()
        assertNotNull(doc.images[img.path])
        assertEquals(612f, doc.page.widthPt, 0.5f) // US Letter
    }

    @Test fun formatDetection() {
        assertEquals(OfficeFormat.DOCX, OfficeFormats.byExtension("Report.DOCX"))
        assertEquals(OfficeFormat.DOCX, OfficeFormats.byExtension("macro.docm"))
        assertEquals(OfficeFormat.DOCX, OfficeFormats.byExtension("old.doc")) // F11 item 11
        assertNull(OfficeFormats.byExtension("old.odt"))
        assertEquals(OfficeFormat.DOCX, OfficeFormats.byMime("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals(OfficeFormat.XLSX, OfficeFormats.byMime("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        assertEquals(OfficeFormat.PPTX, OfficeFormats.byMime("application/vnd.openxmlformats-officedocument.presentationml.presentation"))
        assertEquals(OfficeFormat.DOCX, OfficeFormats.byMime("application/msword")) // F11 item 11: .doc opens in-app
        // Open with: a content:// file named without an extension still gets the Word viewer.
        assertTrue(hasOfficeViewer("DOC-20261031-WA0003", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertFalse(hasOfficeViewer("DOC-20261031-WA0003", "application/octet-stream"))
        assertEquals(OfficeFormat.DOCX, OfficeFormats.sniff(sample("word_sample.docx", "no_extension")))
        assertEquals(OfficeFormat.DOCX, OfficeFormats.detect("shared", null, sample("google_docs_style.docx", "shared")))
        assertNull(OfficeFormats.sniff(sample("legacy.doc")))
        assertTrue(OfficeFormats.noPreviewMessage("old.odt").contains(".odt"))
        assertFalse(OfficeFormats.noPreviewMessage("old.odt").contains("next update"))
    }

    @Test fun thumbnailModels() {
        val d = sample("word_sample.docx")
        assertEquals(OfficeThumb(d.path, 5L), thumbModel(FileItem(d.path, d.name, false, d.length(), 5L)))
        assertNotNull(thumbModel(FileItem("/x/old.doc", "old.doc", false, 10, 5L))) // F11: .doc gets a snapshot thumbnail
        assertNull(thumbModel(FileItem("/x/old.odt", "old.odt", false, 10, 5L)))
        assertEquals(FileKind.DOCUMENT, FileItem("/x/a.docm", "a.docm", false, 1, 1).kind)
    }

    private fun darkPixels(b: Bitmap): Int {
        var n = 0
        for (y in 0 until b.height step 2) for (x in 0 until b.width step 2) {
            val c = b.getPixel(x, y)
            if (Color.red(c) + Color.green(c) + Color.blue(c) < 300) n++
        }
        return n
    }

    private fun save(b: Bitmap, name: String) = FileOutputStream(File(out, name)).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    @Test fun embeddedThumbnailWins() {
        val bmp = OfficeThumbnails.load(sample("with_thumbnail.docx"), OfficeFormat.DOCX, 256)!!
        assertEquals(180, bmp.width)
        assertEquals(240, bmp.height)
        val c = bmp.getPixel(90, 40)
        assertTrue(Color.red(c) > 180 && Color.green(c) < 80) // the red bar we stored, not a snapshot
    }

    @Test fun firstPageSnapshotWhenNoThumbnail() {
        val shots = listOf("word_sample.docx", "wrapped_sdt_altcontent.docx", "google_docs_style.docx").map { name ->
            val bmp = OfficeThumbnails.load(sample(name), OfficeFormat.DOCX, 256)!!
            assertEquals(256, bmp.width)
            assertTrue(bmp.height > bmp.width) // a portrait page
            assertEquals(Color.WHITE, bmp.getPixel(bmp.width / 2, bmp.height - 4)) // white paper
            assertTrue("$name has text on it", darkPixels(bmp) > 60)
            bmp
        }
        // One strip with the three thumbnails (the snapshot at real thumbnail size) for a visual check.
        val strip = Bitmap.createBitmap(shots.sumOf { it.width } + 40, shots.maxOf { it.height } + 20, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(strip)
        canvas.drawColor(0xFFEDEFF2.toInt())
        var x = 10f
        for (b in shots) { canvas.drawBitmap(b, x, 10f, null); x += b.width + 10 }
        save(strip, "files_docx_thumbs.png")
        // Damaged / not-a-zip: no thumbnail, no crash (the list shows the type icon).
        assertNull(OfficeThumbnails.load(sample("legacy.doc", "fake.docx"), OfficeFormat.DOCX, 256))
        assertNotNull(DocxSnapshot.render(DocxParser.parse(sample("word_sample.docx"), maxBlocks = 3), 128))
    }

    private fun shot(name: String, marker: String) {
        compose.waitUntil(15_000) { runCatching { compose.onAllNodes(hasText(marker, substring = true)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(out, "$name.png").path)
    }

    @Test fun pagerPageRendersPythonDocx() {
        val f = sample("word_sample.docx")
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { OfficeBody(f, Modifier.fillMaxSize(), 8.dp) { } } }
        shot("files_docx_page_python", "Quarterly Project Report")
        compose.onNode(hasText("Mangoes")).assertExists()
        compose.onNode(hasText("Third bullet point", substring = true)).assertExists()
    }

    @Test fun pagerPageRendersWrappedDocx() {
        val f = sample("wrapped_sdt_altcontent.docx")
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { OfficeBody(f, Modifier.fillMaxSize(), 8.dp) { } } }
        shot("files_docx_page_wrapped", "Wrapped Contract Title")
        compose.onNode(hasText("Inner cell")).assertExists()
    }

    @Test fun openWithExtensionlessFileByMime() {
        // What Open with gets from a chat app: a cached copy named without ".docx" plus the docx MIME type.
        val f = sample("google_docs_style.docx", "DOC-20261031-WA0003")
        val format = OfficeFormats.detect(f.name, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", f)
        compose.setContent { FilesTheme(ThemeMode.DARK, liquidGlass = false) { OfficeBody(f, Modifier.fillMaxSize(), 8.dp, format = format) { } } }
        shot("files_docx_openwith_gdocs", "Trip Plan Goa")
        compose.onNode(hasText("Saturday")).assertExists()
    }
}
