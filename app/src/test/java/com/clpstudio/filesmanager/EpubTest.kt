package com.clpstudio.filesmanager

import android.app.Application
import com.clpstudio.filesmanager.data.Epub
import com.clpstudio.filesmanager.viewer.EpubPositions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds small EPUB 3 / EPUB 2 books for the tests and screenshots. */
object SampleEpub {
    fun build(out: File, epub3: Boolean = true, cover: ByteArray? = null): File {
        ZipOutputStream(out.outputStream()).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            fun putBytes(name: String, b: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(b); z.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="${if (epub3) "3.0" else "2.0"}" unique-identifier="id">
 <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
  <dc:title>The Offline Garden</dc:title><dc:creator>Asha Rao</dc:creator><dc:language>en</dc:language>
  <meta name="cover" content="cover-img"/>
 </metadata>
 <manifest>
  ${if (epub3) """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""" else ""}
  <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
  <item id="c1" href="Text/chapter1.xhtml" media-type="application/xhtml+xml"/>
  <item id="c2" href="Text/chapter%202.xhtml" media-type="application/xhtml+xml"/>
  <item id="notes" href="Text/notes.xhtml" media-type="application/xhtml+xml"/>
  <item id="css" href="Styles/book.css" media-type="text/css"/>
  ${if (cover != null) """<item id="cover-img" href="Images/cover.png" media-type="image/png"/>""" else ""}
 </manifest>
 <spine toc="ncx"><itemref idref="c1"/><itemref idref="notes" linear="no"/><itemref idref="c2"/></spine>
</package>""",
            )
            if (epub3) put(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body>
<nav epub:type="toc"><ol><li><a href="Text/chapter1.xhtml">1. Seeds &amp; soil</a><ol><li><a href="Text/chapter1.xhtml#water">Watering</a></li></ol></li><li><a href="Text/chapter%202.xhtml">2. Harvest</a></li></ol></nav>
<nav epub:type="landmarks"><ol><li><a href="Text/notes.xhtml">Notes</a></li></ol></nav></body></html>""",
            )
            put(
                "OEBPS/toc.ncx",
                """<?xml version="1.0" encoding="UTF-8"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><navMap>
<navPoint id="p1" playOrder="1"><navLabel><text>One: Seeds</text></navLabel><content src="Text/chapter1.xhtml"/>
 <navPoint id="p1a" playOrder="2"><navLabel><text>Watering</text></navLabel><content src="Text/chapter1.xhtml#water"/></navPoint></navPoint>
<navPoint id="p2" playOrder="3"><navLabel><text>Two: Harvest</text></navLabel><content src="Text/chapter%202.xhtml"/></navPoint>
</navMap></ncx>""",
            )
            put("OEBPS/Text/chapter1.xhtml", """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Seeds</title><link rel="stylesheet" href="../Styles/book.css"/></head><body><h1>Seeds &amp; soil</h1><p>Every garden starts with a handful of seeds and patience.</p><h2 id="water">Watering</h2><p>Water early in the morning.</p></body></html>""")
            put("OEBPS/Text/chapter 2.xhtml", """<html><head><title>Harvest</title></head><body><h1>Harvest</h1><p>Pick tomatoes when they are deep red.</p></body></html>""")
            put("OEBPS/Text/notes.xhtml", """<html><head></head><body><p>Notes</p></body></html>""")
            put("OEBPS/Styles/book.css", "h1{font-size:1.6em}")
            if (cover != null) putBytes("OEBPS/Images/cover.png", cover)
        }
        return out
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EpubTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun epub3NavAndMetadata() {
        val book = Epub.open(SampleEpub.build(tmp.newFile("garden.epub"), cover = SampleOffice.png(60, 90)))
        assertEquals("The Offline Garden", book.title)
        assertEquals("Asha Rao", book.author)
        assertEquals(listOf("OEBPS/Text/chapter1.xhtml", "OEBPS/Text/chapter 2.xhtml"), book.spine) // linear="no" skipped
        assertEquals(listOf("1. Seeds & soil", "Watering", "2. Harvest"), book.toc.map { it.title })
        assertEquals(listOf(0, 1, 0), book.toc.map { it.depth })
        assertEquals("water", book.toc[1].fragment)
        assertEquals(1, book.spineIndexOf(book.toc[2].path))
        assertEquals("OEBPS/Images/cover.png", book.coverPath)
        assertEquals("text/css", book.mediaTypeOf("OEBPS/Styles/book.css"))
    }

    @Test fun epub2UsesNcx() {
        val book = Epub.open(SampleEpub.build(tmp.newFile("old.epub"), epub3 = false))
        assertEquals(listOf("One: Seeds", "Watering", "Two: Harvest"), book.toc.map { it.title })
        assertEquals(listOf(0, 1, 0), book.toc.map { it.depth })
        assertNull(book.coverPath)
    }

    @Test fun notAnEpub() {
        val f = tmp.newFile("x.epub").apply { writeBytes(ByteArray(10)) }
        assertTrue(runCatching { Epub.open(f) }.isFailure)
    }

    @Test fun resolvePaths() {
        assertEquals("OEBPS/Images/a b.png", Epub.resolve("OEBPS/Text", "../Images/a%20b.png"))
        assertEquals("OEBPS/Text/c.xhtml", Epub.resolve("OEBPS/Text", "./c.xhtml#frag"))
        assertEquals("x.css", Epub.resolve("", "../../x.css")) // never leaves the root
        assertEquals("a+b.xhtml", Epub.resolve("", "a+b.xhtml"))
    }

    @Test fun styleGoesIntoHead() {
        val s = Epub.styled("<html><head><title>t</title></head><body>x</body></html>", night = true)
        assertTrue(s.indexOf("<style>") < s.indexOf("</head>"))
        assertTrue(s.contains("#121212"))
        val noHead = Epub.styled("<html><body>x</body></html>", night = false)
        assertTrue(noHead.indexOf("<style>") > noHead.indexOf("<body>"))
    }

    @Test fun positionsRoundTrip() {
        assertEquals(EpubPositions.Position(3, 0.5f), EpubPositions.parse(EpubPositions.format(EpubPositions.Position(3, 0.5f))))
        assertNull(EpubPositions.parse("x"))
        assertEquals(EpubPositions.Position(0, 1f), EpubPositions.parse("-2:7"))
    }
}
