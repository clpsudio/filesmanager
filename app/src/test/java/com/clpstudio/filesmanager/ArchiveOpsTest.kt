package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.data.ArchiveOps
import com.clpstudio.filesmanager.data.WrongPasswordException
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class ArchiveOpsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sources(): Pair<File, File> {
        val dir = tmp.newFolder("Trip photos")
        File(dir, "notes.txt").writeText("hello zip ✓")
        File(dir, "sub").mkdirs()
        File(dir, "sub/big.bin").writeBytes(Random(7).nextBytes(3_000_000))
        File(dir, "empty").mkdirs()
        val single = tmp.newFile("report.pdf").apply { writeBytes(Random(3).nextBytes(20_000)) }
        return dir to single
    }

    /** Locked zip → password → extract into a NEW folder named after the zip ("Photos (1)" when taken). */
    @Test fun lockedZipExtractsToNewFolder() = runBlocking {
        val (dir, _) = sources()
        val zip = File(tmp.root, "Photos.zip")
        ArchiveOps.compress(listOf(dir), zip, "pa55word".toCharArray())
        val first = ArchiveOps.newFolderFor(zip)
        assertEquals("Photos", first.name)
        assertEquals(tmp.root, first.parentFile)
        assertEquals(2, ArchiveOps.extract(zip, "pa55word".toCharArray(), first))
        assertEquals("hello zip ✓", File(first, "Trip photos/notes.txt").readText())
        // The folder exists now, so the next extraction gets a fresh one.
        val second = ArchiveOps.newFolderFor(zip)
        assertEquals("Photos (1)", second.name)
        assertEquals(2, ArchiveOps.extract(zip, "pa55word".toCharArray(), second))
        assertTrue(File(second, "Trip photos/sub/big.bin").length() == 3_000_000L)
    }

    @Test fun lockedZipRoundTrip() = runBlocking {
        val (dir, single) = sources()
        val zip = File(tmp.root, "locked.zip")
        var progressCalls = 0
        ArchiveOps.compress(listOf(dir, single), zip, "s3cret".toCharArray()) { progressCalls++ }
        assertTrue(zip.exists())
        assertTrue(progressCalls > 1)
        assertTrue(ArchiveOps.isEncrypted(zip))
        // AES-256 on every file entry.
        ZipFile(zip).use { z ->
            z.fileHeaders.filter { !it.isDirectory }.forEach {
                assertTrue(it.isEncrypted)
                assertEquals(EncryptionMethod.AES, it.encryptionMethod)
                assertEquals(net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256, it.aesExtraDataRecord.aesKeyStrength)
            }
        }
        assertFalse(ArchiveOps.checkPassword(zip, "wrong!".toCharArray()))
        assertTrue(ArchiveOps.checkPassword(zip, "s3cret".toCharArray()))

        val out = tmp.newFolder("out")
        try {
            ArchiveOps.extract(zip, "nope1".toCharArray(), out)
            fail("wrong password must fail")
        } catch (_: WrongPasswordException) {
        }
        val n = ArchiveOps.extract(zip, "s3cret".toCharArray(), out)
        assertEquals(3, n)
        assertEquals("hello zip ✓", File(out, "Trip photos/notes.txt").readText())
        assertArrayEquals(File(dir, "sub/big.bin").readBytes(), File(out, "Trip photos/sub/big.bin").readBytes())
        assertArrayEquals(single.readBytes(), File(out, "report.pdf").readBytes())
        assertTrue(File(out, "Trip photos/empty").isDirectory)
    }

    @Test fun extractSelectedEntriesOnly() = runBlocking {
        val (dir, _) = sources()
        val zip = File(tmp.root, "sel.zip")
        ArchiveOps.compress(listOf(dir), zip, "abcd".toCharArray())
        val out = tmp.newFolder("sel")
        // From inside the "Trip photos" folder, pick only the "sub" folder.
        ArchiveOps.extract(zip, "abcd".toCharArray(), out, paths = listOf("Trip photos/sub"), stripPrefix = "Trip photos")
        assertTrue(File(out, "sub/big.bin").exists())
        assertFalse(File(out, "notes.txt").exists())
    }

    @Test fun plainZipAndZipCrypto() = runBlocking {
        val (dir, single) = sources()
        val plain = File(tmp.root, "plain.zip")
        ArchiveOps.compress(listOf(single), plain, null)
        assertFalse(ArchiveOps.isEncrypted(plain))
        val out = tmp.newFolder("plain")
        ArchiveOps.extract(plain, null, out)
        assertArrayEquals(single.readBytes(), File(out, single.name).readBytes())

        // Classic ZipCrypto archives (made by other apps) open too.
        val legacy = File(tmp.root, "legacy.zip")
        ZipFile(legacy, "pass1".toCharArray()).use { z ->
            z.addFile(File(dir, "notes.txt"), ZipParameters().apply { isEncryptFiles = true; encryptionMethod = EncryptionMethod.ZIP_STANDARD })
        }
        assertTrue(ArchiveOps.isEncrypted(legacy))
        assertFalse(ArchiveOps.checkPassword(legacy, "bad".toCharArray()))
        val out2 = tmp.newFolder("legacy")
        ArchiveOps.extract(legacy, "pass1".toCharArray(), out2)
        assertEquals("hello zip ✓", File(out2, "notes.txt").readText())
    }

    @Test fun refusesZipSlip() = runBlocking {
        val evil = File(tmp.root, "evil.zip")
        ZipFile(evil).use { z ->
            val src = tmp.newFile("x.txt").apply { writeText("x") }
            z.addFile(src, ZipParameters().apply { fileNameInZip = "../../escaped.txt" })
        }
        val out = tmp.newFolder("slip")
        ArchiveOps.extract(evil, null, out)
        assertFalse(File(tmp.root.parentFile, "escaped.txt").exists())
        assertFalse(File(out.parentFile, "escaped.txt").exists())
    }

    @Test fun shortPasswordRejected() = runBlocking {
        try {
            ArchiveOps.compress(listOf(tmp.newFile("a.txt")), File(tmp.root, "a.zip"), "abc".toCharArray())
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }
}
