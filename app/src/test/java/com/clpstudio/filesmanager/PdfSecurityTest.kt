package com.clpstudio.filesmanager

import androidx.test.core.app.ApplicationProvider
import com.clpstudio.filesmanager.data.PdfSecurity
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PdfSecurityTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() = PdfSecurity.init(ApplicationProvider.getApplicationContext())

    private fun samplePdf(pages: Int = 3): File {
        val f = File(tmp.root, "Invoice.pdf")
        PDDocument().use { doc ->
            repeat(pages) { doc.addPage(PDPage()) }
            doc.save(f)
        }
        return f
    }

    @Test fun encryptThenDecryptRoundTrip() {
        val original = samplePdf()
        assertEquals(PdfSecurity.Lock.NONE, PdfSecurity.lockState(original))

        val locked = PdfSecurity.copyName(original, "_locked")
        assertEquals("Invoice_locked.pdf", locked.name)
        PdfSecurity.encrypt(original, "open1234", locked, ownerPassword = "owner5678")
        assertTrue(locked.exists())
        assertEquals(PdfSecurity.Lock.USER_PASSWORD, PdfSecurity.lockState(locked))
        assertFalse(PdfSecurity.checkPassword(locked, "wrong"))
        assertTrue(PdfSecurity.checkPassword(locked, "open1234"))
        assertTrue(PdfSecurity.checkPassword(locked, "owner5678"))
        try {
            PDDocument.load(locked).close()
            fail("must need a password")
        } catch (_: InvalidPasswordException) {
        }
        PDDocument.load(locked, "open1234").use { doc ->
            assertTrue(doc.isEncrypted)
            assertEquals(256, doc.encryption.length)
            assertEquals(3, doc.numberOfPages)
        }

        try {
            PdfSecurity.decrypt(locked, "bad-pass", File(tmp.root, "x.pdf"))
            fail("wrong password must fail")
        } catch (_: InvalidPasswordException) {
        }
        val unlocked = PdfSecurity.copyName(locked, "_unlocked")
        assertEquals("Invoice_unlocked.pdf", unlocked.name)
        PdfSecurity.decrypt(locked, "open1234", unlocked)
        assertEquals(PdfSecurity.Lock.NONE, PdfSecurity.lockState(unlocked))
        PDDocument.load(unlocked).use { assertEquals(3, it.numberOfPages) }
    }

    @Test fun replaceOriginalInPlace() {
        val original = samplePdf(1)
        PdfSecurity.encrypt(original, "abcd", original)
        assertEquals(PdfSecurity.Lock.USER_PASSWORD, PdfSecurity.lockState(original))
        PdfSecurity.decrypt(original, "abcd", original)
        assertEquals(PdfSecurity.Lock.NONE, PdfSecurity.lockState(original))
        assertEquals(listOf("Invoice.pdf"), tmp.root.list()!!.toList())
    }
}
