package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.ArchiveFormat
import com.clpstudio.filesmanager.data.Archives
import com.clpstudio.filesmanager.data.Rar5NotSupportedException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 7z and RAR4 (open, list, extract, passwords) and the RAR5 refusal, on real archives made by 7-Zip and WinRAR. */
class ArchivesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(name: String): File {
        val f = File(tmp.root, name)
        javaClass.getResourceAsStream("/fmt/$name")!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private val pw = "trip123".toCharArray()

    private fun checkTree(root: File) {
        assertEquals("hello archive ✓\n", File(root, "Trip/notes.txt").readText())
        assertEquals(20000L, File(root, "Trip/sub/data.bin").length())
        assertEquals(494L, File(root, "Trip/sub/pic.png").length())
        assertTrue(File(root, "Trip/empty").isDirectory)
    }

    @Test fun formatsFromBytesAndNames() {
        assertEquals(ArchiveFormat.SEVEN_Z, Archives.formatOf(fixture("sample.7z")))
        assertEquals(ArchiveFormat.RAR, Archives.formatOf(fixture("sample.rar")))
        assertEquals(ArchiveFormat.RAR5, Archives.formatOf(fixture("sample_rar5.rar")))
        // A renamed file still opens by its real format.
        val renamed = fixture("sample.7z").copyTo(File(tmp.root, "renamed.zip"))
        assertEquals(ArchiveFormat.SEVEN_Z, Archives.formatOf(renamed))
        assertTrue(Archives.canBrowse("a.7z") && Archives.canBrowse("a.RAR") && Archives.canBrowse("a.zip"))
        assertFalse(Archives.canBrowse("a.tar.gz"))
    }

    @Test fun sevenZListsAndExtracts() = runBlocking {
        for (name in listOf("sample.7z", "sample.rar")) {
            val f = fixture(name)
            val p = Archives.probe(f)
            assertFalse(name, p.encrypted)
            assertFalse(name, p.listLocked)
            val byPath = p.entries.associateBy { it.path }
            assertEquals(name, 18L, byPath["Trip/notes.txt"]!!.size)
            assertEquals(name, 20000L, byPath["Trip/sub/data.bin"]!!.size)
            val out = tmp.newFolder("out-$name")
            Archives.extract(f, null, out)
            checkTree(out)
            val one = Archives.extractEntry(f, null, "Trip/notes.txt", File(tmp.root, "one-$name.txt"))
            assertEquals("hello archive ✓\n", one.readText())
        }
    }

    @Test fun lockedFilesNeedThePassword() = runBlocking {
        for (name in listOf("locked_files.7z", "locked_files.rar")) {
            val f = fixture(name)
            val p = Archives.probe(f)
            assertTrue(name, p.encrypted)
            assertFalse(name, Archives.checkPassword(f, "nope".toCharArray()))
            assertTrue(name, Archives.checkPassword(f, pw))
            try {
                Archives.extract(f, "nope".toCharArray(), tmp.newFolder("bad-$name"))
                fail("$name: wrong password extracted")
            } catch (e: Exception) {
                assertTrue("$name: ${e.javaClass.name} ${e.message}", Archives.isWrongPassword(e) || e is com.clpstudio.filesmanager.data.WrongPasswordException)
            }
            val out = tmp.newFolder("ok-$name")
            Archives.extract(f, pw, out)
            checkTree(out)
        }
    }

    @Test fun lockedNamesListAfterPassword() {
        for (name in listOf("locked_headers.7z", "locked_headers.rar")) {
            val f = fixture(name)
            val p = Archives.probe(f)
            assertTrue(name, p.listLocked)
            assertFalse(name, Archives.checkPassword(f, "nope".toCharArray()))
            val entries = Archives.list(f, pw)
            assertTrue(name, entries.any { it.path == "Trip/sub/data.bin" && it.size == 20000L })
        }
    }

    @Test fun rar5IsRefusedClearly() = runBlocking {
        val f = fixture("sample_rar5.rar")
        val probe = runCatching { Archives.probe(f) }
        assertTrue(probe.exceptionOrNull() is Rar5NotSupportedException || probe.getOrNull()?.format == ArchiveFormat.RAR5)
        try {
            Archives.extract(f, null, tmp.newFolder("r5"))
            fail("RAR5 extracted")
        } catch (e: Rar5NotSupportedException) {
            assertEquals("RAR5 not supported", Archives.failureTitle(e))
        }
    }
}
