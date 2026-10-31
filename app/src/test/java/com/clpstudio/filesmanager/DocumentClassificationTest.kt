package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.FileTypes
import com.clpstudio.filesmanager.data.StorageWalker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DocumentClassificationTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun documentExtensions() {
        for (n in listOf("a.pdf", "B.DOCX", "c.doc", "d.xlsx", "e.xls", "f.pptx", "g.ppt", "h.txt", "i.csv", "j.rtf", "k.odt", "l.ods", "m.odp", "n.epub", "o.json", "p.xml", "q.html", "r.md")) {
            assertTrue(n, FileTypes.isDocument(n))
        }
        for (n in listOf("a.jpg", "b.mp4", "c.apk", "d.zip", "e", ".pdf", "f.kt", "g.log")) {
            assertFalse(n, FileTypes.isDocument(n))
        }
        assertTrue(FileTypes.isApk("app.APK"))
        assertTrue(FileTypes.isArchive("x.zip"))
    }

    @Test fun walkerFindsDocumentsInNoMediaFoldersButSkipsHiddenAndAndroidData() {
        val root = tmp.newFolder("storage")
        fun file(rel: String) = File(root, rel).apply { parentFile!!.mkdirs(); writeText("x") }
        file("Documents/cv.pdf")
        file("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents/bill.docx")
        file("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents/.nomedia")
        file("Android/data/com.app/files/secret.pdf")
        file(".hidden/notes.txt")
        file("Download/photo.jpg")
        file("Download/sheet.xlsx")
        val found = StorageWalker.walk(listOf(root), showHidden = false, privateRoots = emptyList(), accept = FileTypes::isDocument)
            .map { it.name }.toSet()
        assertEquals(setOf("cv.pdf", "bill.docx", "sheet.xlsx"), found)
        val withHidden = StorageWalker.walk(listOf(root), showHidden = true, privateRoots = emptyList(), accept = FileTypes::isDocument)
            .map { it.name }.toSet()
        assertTrue("notes.txt" in withHidden)
        assertFalse("secret.pdf" in withHidden)
    }

    @Test fun mergeDeduplicatesByPath() {
        val a = com.clpstudio.filesmanager.data.FileItem("/s/a.pdf", "a.pdf", false, 1, 10)
        val b = com.clpstudio.filesmanager.data.FileItem("/s/b.pdf", "b.pdf", false, 1, 20)
        assertEquals(listOf("/s/b.pdf", "/s/a.pdf"), StorageWalker.merge(listOf(a, b), listOf(a)).map { it.path })
    }
}
