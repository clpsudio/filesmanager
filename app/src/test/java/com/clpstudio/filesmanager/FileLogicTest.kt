package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.FileTypes
import com.clpstudio.filesmanager.data.HiddenFilter
import com.clpstudio.filesmanager.data.MagicSniffer
import com.clpstudio.filesmanager.data.SortBy
import com.clpstudio.filesmanager.data.groupByFolder
import com.clpstudio.filesmanager.data.isValidFileName
import com.clpstudio.filesmanager.data.sortItems
import com.clpstudio.filesmanager.data.uniqueName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileLogicTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun kinds() {
        assertEquals(FileKind.VIDEO, FileTypes.kindOf("movie.MKV"))
        assertEquals(FileKind.AUDIO, FileTypes.kindOf("song.opus"))
        assertEquals(FileKind.AUDIO, FileTypes.kindOf("voice.amr"))
        assertEquals(FileKind.IMAGE, FileTypes.kindOf("IMG.HEIC"))
        assertEquals(FileKind.PDF, FileTypes.kindOf("a.pdf"))
        assertEquals(FileKind.DOCUMENT, FileTypes.kindOf("a.docx"))
        assertEquals(FileKind.APK, FileTypes.kindOf("app.apk"))
        assertEquals(FileKind.ARCHIVE, FileTypes.kindOf("a.7z"))
        assertEquals(FileKind.TEXT, FileTypes.kindOf("data.json"))
        assertEquals(FileKind.OTHER, FileTypes.kindOf("noext"))
        assertEquals(FileKind.FOLDER, FileTypes.kindOf("dir", isDirectory = true))
    }

    @Test fun naturalSortFoldersFirst() {
        val items = listOf(
            FileItem("/a/file10.txt", "file10.txt", false, 1, 3),
            FileItem("/a/file2.txt", "file2.txt", false, 5, 1),
            FileItem("/a/Zdir", "Zdir", true, 0, 2),
        )
        assertEquals(listOf("Zdir", "file2.txt", "file10.txt"), sortItems(items, SortBy.NAME, true).map { it.name })
        assertEquals(listOf("Zdir", "file2.txt", "file10.txt"), sortItems(items, SortBy.SIZE, false).map { it.name })
        assertEquals(listOf("Zdir", "file10.txt", "file2.txt"), sortItems(items, SortBy.DATE, false).map { it.name })
    }

    @Test fun uniqueNames() {
        val taken = setOf("a.txt", "a (1).txt", "dir")
        assertEquals("a (2).txt", uniqueName("a.txt", false) { it in taken })
        assertEquals("b.txt", uniqueName("b.txt", false) { it in taken })
        assertEquals("dir (1)", uniqueName("dir", true) { it in taken })
    }

    @Test fun validNames() {
        assertTrue(isValidFileName("Report 2026.pdf"))
        assertFalse(isValidFileName(""))
        assertFalse(isValidFileName("a/b"))
        assertFalse(isValidFileName(".."))
    }

    @Test fun grouping() {
        val g = groupByFolder(
            listOf(
                FileItem("/s/DCIM/Camera/1.jpg", "1.jpg", false, 1, 10),
                FileItem("/s/DCIM/Camera/2.jpg", "2.jpg", false, 1, 30),
                FileItem("/s/Pictures/WhatsApp/3.jpg", "3.jpg", false, 1, 20),
            ),
        )
        assertEquals(listOf("Camera", "WhatsApp"), g.map { it.name })
        assertEquals(2, g[0].count)
        assertEquals("2.jpg", g[0].items.first().name)
    }

    @Test fun sniffing() {
        assertEquals("application/pdf", MagicSniffer.sniff("%PDF-1.7".toByteArray()))
        assertEquals("image/png", MagicSniffer.sniff(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        assertEquals("image/jpeg", MagicSniffer.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("audio/flac", MagicSniffer.sniff("fLaC....".toByteArray()))
        assertEquals("audio/amr", MagicSniffer.sniff("#!AMR\n".toByteArray()))
        assertEquals("application/zip", MagicSniffer.sniff(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertEquals("video/mp4", MagicSniffer.sniff(byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray()))
        assertEquals("image/heic", MagicSniffer.sniff(byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray()))
    }

    @Test fun mimeResolution() {
        // A specific type from the sending app wins.
        assertEquals("application/pdf", MagicSniffer.resolveMime("application/pdf", "x.bin", null))
        // A generic type falls back to the name, then to the bytes.
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", MagicSniffer.resolveMime("application/octet-stream", "Letter.docx", null))
        assertEquals("application/pdf", MagicSniffer.resolveMime("*/*", "document", "%PDF-1.4".toByteArray()))
        assertEquals("application/octet-stream", MagicSniffer.resolveMime(null, "blob", byteArrayOf(1, 2, 3)))
    }

    @Test fun hiddenFilesHiddenByDefaultAndShownWhenOn() {
        val root = tmp.newFolder("storage")
        val dot = File(root, ".secret").apply { writeText("x") }
        val noMediaDir = File(root, "Stickers").apply { mkdirs(); File(this, ".nomedia").writeText("") }
        val normal = File(root, "photo.jpg").apply { writeText("x") }
        val off = HiddenFilter(false, storageRoots = listOf(root.absolutePath))
        assertFalse(off.visibleEntry(dot))
        assertFalse(off.visibleEntry(noMediaDir))
        assertTrue(off.visibleEntry(normal))
        assertFalse(off.visiblePath(File(noMediaDir, "a.webp").absolutePath))
        val on = HiddenFilter(true, storageRoots = listOf(root.absolutePath))
        assertTrue(on.visibleEntry(dot))
        assertTrue(on.visibleEntry(noMediaDir))
    }

    @Test fun vaultNeverListedEvenWithHiddenFilesOn() {
        val app = tmp.newFolder("appdata")
        val vault = File(app, "no_backup/vault").apply { mkdirs(); File(this, "enc1.bin").writeText("cipher") }
        val bin = File(app, "bin").apply { mkdirs() }
        val f = HiddenFilter(true, privateRoots = listOf(vault.absolutePath, bin.absolutePath))
        assertFalse(f.visibleEntry(vault))
        assertFalse(f.visiblePath(File(vault, "enc1.bin").absolutePath))
        assertFalse(f.visibleEntry(bin))
        // A listing of the parent with hidden files ON still leaves the vault out.
        val listed = File(app, "no_backup").listFiles()!!.filter { f.visibleEntry(it) }
        assertTrue(listed.none { it.absolutePath == vault.absolutePath })
    }
}
