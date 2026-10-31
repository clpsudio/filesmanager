package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.security.CredentialType
import com.clpstudio.filesmanager.vault.SoftwareKeyWrapper
import com.clpstudio.filesmanager.vault.VaultLockedException
import com.clpstudio.filesmanager.vault.VaultStore
import com.clpstudio.filesmanager.vault.WrongSecretException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/** Move in → move out gives the identical file back; names are encrypted; locking closes readers. */
class VaultStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val hw = SoftwareKeyWrapper()
    private fun store() = VaultStore(File(tmp.root, "vault"), hw, iterations = 2000, chunk = 4096)
    private fun file(name: String, size: Int, dir: File = tmp.newFolder()): File =
        File(dir, name).apply { writeBytes(ByteArray(size).also { Random(size.toLong()).nextBytes(it) }); setLastModified(1_700_000_000_000L) }

    @Test fun setupImportListExportIdentical() {
        val s = store()
        assertFalse(s.isSetUp)
        s.setup(CredentialType.PIN, "4321")
        assertTrue(s.isSetUp && s.isUnlocked)
        val src = file("holiday photo.jpg", 50_000)
        val bytes = src.readBytes()
        val item = s.importFile(src)
        assertEquals("holiday photo.jpg", item.name)
        assertEquals(src.absolutePath, item.originalPath)
        assertEquals(50_000L, item.size)
        assertEquals(1, s.count())
        assertEquals(listOf(item.id), s.list().map { it.id })
        // Read back through a reader and through export
        s.openReader(item).use { assertArrayEquals(bytes, it.readAll()) }
        src.delete()
        val out = s.export(item, src.parentFile!!)
        assertEquals(src.absolutePath, out.absolutePath)
        assertArrayEquals(bytes, out.readBytes())
        assertEquals(1_700_000_000_000L, out.lastModified())
        assertTrue(s.remove(item))
        assertEquals(0, s.count())
        assertTrue(s.list().isEmpty())
    }

    @Test fun exportDoesntOverwriteAnExistingFile() {
        val s = store().apply { setup(CredentialType.PIN, "4321") }
        val src = file("a.txt", 100)
        val item = s.importFile(src)
        // a different file now has the same name
        src.writeText("other")
        val out = s.export(item, src.parentFile!!)
        assertEquals("a (1).txt", out.name)
        assertEquals("other", src.readText())
        assertEquals(100L, out.length())
        assertTrue(src.parentFile!!.listFiles()!!.none { it.name.endsWith(".vaultpart") })
    }

    @Test fun namesAndContentAreNotOnDiskInTheClear() {
        val s = store().apply { setup(CredentialType.PIN, "4321") }
        val dir = tmp.newFolder()
        val src = File(dir, "SECRET_NAME_42.txt").apply { writeText("TOP SECRET CONTENT ".repeat(100)) }
        s.importFile(src)
        File(tmp.root, "vault").walkTopDown().filter { it.isFile }.forEach { f ->
            assertFalse(f.name.contains("SECRET_NAME"))
            val text = String(f.readBytes(), Charsets.ISO_8859_1)
            assertFalse("${f.name} leaks the name", text.contains("SECRET_NAME_42"))
            assertFalse("${f.name} leaks content", text.contains("TOP SECRET"))
        }
    }

    @Test fun lockForgetsKeyAndClosesReaders() {
        val s = store().apply { setup(CredentialType.PIN, "4321") }
        val item = s.importFile(file("v.mp4", 20_000))
        val reader = s.openReader(item)
        s.lock()
        assertFalse(s.isUnlocked)
        assertThrows(java.io.IOException::class.java) { reader.read(0, ByteArray(10), 0, 10) }
        assertThrows(VaultLockedException::class.java) { s.list() }
        assertThrows(VaultLockedException::class.java) { s.openReader(item) }
        assertEquals(1, s.count()) // the number is still known while locked
        assertThrows(WrongSecretException::class.java) { s.unlock("0000") }
        s.unlock("4321")
        assertEquals(1, s.list().size)
    }

    @Test fun reopenWithNewStoreObject() {
        val s = store().apply { setup(CredentialType.PASSWORD, "password1") }
        val src = file("doc.pdf", 9000)
        val bytes = src.readBytes()
        s.importFile(src)
        s.lock()
        val again = store()
        assertTrue(again.isSetUp)
        assertFalse(again.isUnlocked)
        assertEquals(CredentialType.PASSWORD, again.credentialType)
        again.unlock("password1")
        val item = again.list().single()
        again.openReader(item).use { assertArrayEquals(bytes, it.readAll()) }
    }

    @Test fun changeSecretKeepsFiles() {
        val s = store().apply { setup(CredentialType.PIN, "1111") }
        s.importFile(file("x.bin", 5000))
        s.changeSecret("1111", CredentialType.PIN, "2222")
        s.lock()
        assertFalse(s.checkSecret("1111"))
        assertTrue(s.checkSecret("2222"))
        s.unlock("2222")
        assertEquals(1, s.list().size)
    }

    @Test fun deleteVaultRemovesEverything() {
        val s = store().apply { setup(CredentialType.PIN, "1111") }
        s.importFile(file("x.bin", 5000))
        s.deleteVault()
        assertFalse(s.isSetUp)
        assertFalse(s.isUnlocked)
        assertEquals(0, s.count())
        // can be set up again from scratch
        s.setup(CredentialType.PIN, "9999")
        assertTrue(s.list().isEmpty())
    }

    @Test fun failedImportLeavesNothingBehind() {
        val s = store().apply { setup(CredentialType.PIN, "1111") }
        val input = object : java.io.InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 5000) 1 else -1
        }
        assertThrows(java.io.IOException::class.java) { s.import("short.bin", "/x/short.bin", 10_000, 0, input) }
        assertEquals(0, File(tmp.root, "vault/files").listFiles()!!.size)
    }

    @Test fun emptyFileRoundTrip() {
        val s = store().apply { setup(CredentialType.PIN, "1111") }
        val src = file("empty.txt", 0)
        val item = s.importFile(src)
        src.delete()
        val out = s.export(item, src.parentFile!!)
        assertEquals(0L, out.length())
    }
}
