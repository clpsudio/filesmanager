package com.clpstudio.filesmanager

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.clpstudio.filesmanager.data.ArchiveFormat
import com.clpstudio.filesmanager.data.Archives
import com.clpstudio.filesmanager.data.PdfSecurity
import com.clpstudio.filesmanager.data.Rar5NotSupportedException
import com.clpstudio.filesmanager.viewer.PdfDocument
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Feature verification for the open-source-readiness build (Nov 2026): archives made fresh with the real
 * tools (RAR 6.24 with -ma4 / -ma5, Info-ZIP, p7zip 16.02, GNU tar) are opened through the app's own code,
 * and the bundled 2-page PDF goes through the app's PDF paths.
 *
 * Fixtures (app/src/test/resources/fmt/fresh*): the same tree "Fresh/" in every archive —
 * readme.txt (compressible text), deep/er/random.bin (16000 random bytes), "deep/ünïcode ✓.txt", emptydir/.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FeatureVerificationTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(name: String): File {
        val f = File(tmp.root, name)
        javaClass.getResourceAsStream("/fmt/$name")!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private val expected = mapOf(
        "Fresh/readme.txt" to "177eab2aa03efddfa1fbf786c05229cfc9b0b21a1f9c0a8ec30df9bdbc1f7014",
        "Fresh/deep/er/random.bin" to "5a599aaf207fed073d6ee2fcaf4a99f1dde42a338f4c997dcc7f112d429696e4",
        "Fresh/deep/ünïcode ✓.txt" to "4d16031f3e6c2df9e93003911a8b0f8a19f5dc03f5ffe0bb759f52254a0c678f",
    )

    private fun checkExtracted(root: File, label: String) {
        for ((path, hash) in expected) {
            val f = File(root, path)
            assertTrue("$label: $path extracted", f.isFile)
            assertEquals("$label: $path content", hash, sha256(f))
        }
        assertTrue("$label: empty folder kept", File(root, "Fresh/emptydir").isDirectory)
    }

    private fun listsAndExtracts(name: String, format: ArchiveFormat, password: CharArray? = null, singleEntry: Boolean = true) = runBlocking {
        val f = fixture(name)
        assertEquals(name, format, Archives.formatOf(f))
        val entries = Archives.list(f, password).associateBy { it.path }
        assertEquals(name, 16000L, entries.getValue("Fresh/deep/er/random.bin").size)
        assertTrue(name, entries.containsKey("Fresh/deep/ünïcode ✓.txt"))
        val out = tmp.newFolder("out-$name")
        Archives.extract(f, password, out)
        checkExtracted(out, name)
        if (singleEntry) {
            val one = Archives.extractEntry(f, password, "Fresh/deep/er/random.bin", File(tmp.root, "one-$name.bin"))
            assertEquals(name, expected.getValue("Fresh/deep/er/random.bin"), sha256(one))
        }
    }

    @Test fun rar4FreshFromRar624() = listsAndExtracts("fresh_rar4.rar", ArchiveFormat.RAR)

    /**
     * Solid RAR4: listing and full extraction work. Opening ONE file out of a solid RAR (extractEntry) fails
     * inside Junrar 7.5.5 (a solid archive must be decoded from its first file); that is the same in 1.0.23
     * and is reported separately, not changed here.
     */
    @Test fun rar4SolidFromRar624() = listsAndExtracts("fresh_rar4_solid.rar", ArchiveFormat.RAR, singleEntry = false)

    @Test fun rar4PasswordFromRar624() {
        val f = fixture("fresh_rar4_password.rar")
        assertTrue(Archives.probe(f).encrypted)
        assertTrue(!Archives.checkPassword(f, "wrong".toCharArray()) && Archives.checkPassword(f, "trip123".toCharArray()))
        listsAndExtracts("fresh_rar4_password.rar", ArchiveFormat.RAR, "trip123".toCharArray())
    }

    /** RAR5 (RAR 5.0+ default format) is detected and refused with a clear message: Junrar reads RAR4 only. */
    @Test fun rar5FromRar624IsRefusedClearly() = runBlocking {
        val f = fixture("fresh_rar5.rar")
        assertEquals(ArchiveFormat.RAR5, Archives.formatOf(f))
        try {
            Archives.extract(f, null, tmp.newFolder("r5"))
            fail("RAR5 extracted")
        } catch (e: Rar5NotSupportedException) {
            assertEquals("RAR5 not supported", Archives.failureTitle(e))
        }
    }

    @Test fun sevenZipFreshFromP7zip() = listsAndExtracts("fresh.7z", ArchiveFormat.SEVEN_Z)

    /** ZIP with UTF-8 names (flag set, as Android, macOS and 7-Zip write them). */
    @Test fun zipFreshFrom7zipUtf8Names() = listsAndExtracts("fresh_utf8.zip", ArchiveFormat.ZIP)

    /**
     * ZIP from Info-ZIP 3.0 on Linux: the contents extract correctly. This zip build stores the non-ASCII
     * name as UTF-8 bytes without the UTF-8 flag, so (per the ZIP spec) it is read as code page 437 and that
     * one name shows garbled; same behaviour as 1.0.23 (zip4j), reported separately.
     */
    @Test fun zipFreshFromInfoZip() = runBlocking {
        val f = fixture("fresh.zip")
        assertEquals(ArchiveFormat.ZIP, Archives.formatOf(f))
        val entries = Archives.list(f, null)
        assertEquals(16000L, entries.single { it.path == "Fresh/deep/er/random.bin" }.size)
        val out = tmp.newFolder("out-infozip")
        Archives.extract(f, null, out)
        assertEquals(expected.getValue("Fresh/readme.txt"), sha256(File(out, "Fresh/readme.txt")))
        assertEquals(expected.getValue("Fresh/deep/er/random.bin"), sha256(File(out, "Fresh/deep/er/random.bin")))
        val odd = File(out, "Fresh/deep").listFiles()!!.single { it.isFile }
        assertEquals(expected.getValue("Fresh/deep/ünïcode ✓.txt"), sha256(odd))
    }

    /** tar / tar.gz are not a feature of Files (spec: ZIP browse/zip/unzip; 7z and RAR4 were added): refused clearly, not crashed. */
    @Test fun tarGzIsNotSupportedAndSaysSo() {
        val f = fixture("fresh.tar.gz")
        assertEquals(ArchiveFormat.OTHER, Archives.formatOf(f))
        assertTrue(!Archives.canBrowse("fresh.tar.gz") && !Archives.canBrowse("x.tar") && !Archives.canBrowse("x.tgz"))
        try {
            Archives.probe(f)
            fail("tar.gz opened")
        } catch (e: IOException) {
            assertEquals("This kind of archive can't be opened in Files.", e.message)
        }
    }

    /** The PDF reader's parsing path for text/locks (PdfBox-Android) on the bundled 2-page PDF. */
    @Test fun pdfParsesWithPdfBox() {
        PdfSecurity.init(ApplicationProvider.getApplicationContext())
        val f = fixture("fresh_2pages.pdf")
        assertEquals(PdfSecurity.Lock.NONE, PdfSecurity.lockState(f))
        PDDocument.load(f).use { doc ->
            assertEquals(2, doc.numberOfPages)
            assertTrue(PDFTextStripper().getText(doc).contains("Files PDF page 1"))
        }
    }

    /**
     * The on-screen PDF pages are drawn by android.graphics.pdf.PdfRenderer (PdfDocument / PdfThumbFetcher).
     * Its native renderer (pdfium) does not exist in the JVM test runtime: Robolectric fails inside the
     * framework class before any app code runs. This test pins that fact, so the page rendering is known to be
     * NOT covered by JVM tests (it needs a phone or an emulator); the R8 check covers that the classes ship.
     */
    @Test fun pdfRendererNeedsADevice() = runBlocking {
        val f = fixture("fresh_2pages.pdf")
        val r = runCatching {
            PdfDocument.open(ApplicationProvider.getApplicationContext(), Uri.fromFile(f), f.name).use { it.pageCount }
        }
        assertTrue("PdfRenderer unexpectedly worked under Robolectric: ${r.getOrNull()} pages", r.isFailure)
    }
}
