package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.vault.BytesSource
import com.clpstudio.filesmanager.vault.VaultEncryptor
import com.clpstudio.filesmanager.vault.VaultFormat
import com.clpstudio.filesmanager.vault.VaultReader
import com.clpstudio.filesmanager.vault.VaultTamperException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest
import java.util.Random

/** The vault file format: AES-256-GCM chunks, random-access reads, every kind of tampering refused. */
class VaultCryptoTest {
    private val rnd = Random(42)
    private val key = ByteArray(32).also { rnd.nextBytes(it) }
    private fun data(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private fun roundTrip(n: Int, chunk: Int) {
        val plain = data(n)
        val enc = VaultEncryptor.encryptBytes(plain, key, chunk)
        assertEquals("encrypted size for $n", VaultFormat.encryptedSize(n.toLong(), chunk), enc.size.toLong())
        VaultReader(BytesSource(enc), key).use { r ->
            assertEquals(n.toLong(), r.size)
            assertArrayEquals("round trip $n / $chunk", plain, r.readAll())
            assertArrayEquals(sha(plain), r.verifyAll())
        }
    }

    @Test fun roundTripEdgeSizes() {
        val c = 1024
        for (n in listOf(0, 1, 15, c - 1, c, c + 1, 3 * c, 3 * c + 7)) roundTrip(n, c)
    }

    @Test fun roundTripFiveMegabytesDefaultChunk() = roundTrip(5 * 1024 * 1024 + 123, VaultFormat.DEFAULT_CHUNK)

    @Test fun encryptReturnsPlaintextSha() {
        val plain = data(10_000)
        val out = java.io.ByteArrayOutputStream()
        val digest = VaultEncryptor.encrypt(plain.inputStream(), plain.size.toLong(), out, key, 1024)
        assertArrayEquals(sha(plain), digest)
    }

    @Test fun ciphertextHidesPlaintext() {
        val plain = ByteArray(4096) { 'A'.code.toByte() }
        val enc = VaultEncryptor.encryptBytes(plain, key, 1024)
        val run = "AAAAAAAAAAAAAAAA".toByteArray()
        val s = String(enc, Charsets.ISO_8859_1)
        assertEquals(-1, s.indexOf(String(run, Charsets.ISO_8859_1)))
        // Same file twice gives different bytes (fresh salt + nonce prefix per file).
        val enc2 = VaultEncryptor.encryptBytes(plain, key, 1024)
        assertEquals(false, enc.contentEquals(enc2))
    }

    @Test fun randomOffsetReads() {
        val plain = data(200_000)
        val enc = VaultEncryptor.encryptBytes(plain, key, 4096)
        VaultReader(BytesSource(enc), key).use { r ->
            repeat(500) {
                val pos = rnd.nextInt(plain.size)
                val len = 1 + rnd.nextInt(20_000)
                val buf = ByteArray(len)
                val n = r.read(pos.toLong(), buf, 0, len)
                val expect = minOf(len, plain.size - pos)
                assertEquals(expect, n)
                assertArrayEquals(plain.copyOfRange(pos, pos + expect), buf.copyOf(n))
            }
            assertEquals(-1, r.read(plain.size.toLong(), ByteArray(4), 0, 4))
            // stream from the middle
            val tail = r.inputStream(150_000).readBytes()
            assertArrayEquals(plain.copyOfRange(150_000, plain.size), tail)
        }
    }

    private fun assertTampered(enc: ByteArray, k: ByteArray = key) {
        assertThrows(VaultTamperException::class.java) {
            VaultReader(BytesSource(enc), k).use { it.readAll() }
        }
    }

    @Test fun flippedCiphertextByteIsRefused() {
        val enc = VaultEncryptor.encryptBytes(data(5000), key, 1024)
        for (pos in listOf(VaultFormat.HEADER_SIZE, VaultFormat.HEADER_SIZE + 1500, enc.size - 1)) {
            val t = enc.clone(); t[pos] = (t[pos].toInt() xor 1).toByte()
            assertTampered(t)
        }
    }

    @Test fun changedHeaderIsRefused() {
        val enc = VaultEncryptor.encryptBytes(data(5000), key, 1024)
        // salt byte, nonce-prefix byte, magic byte
        for (pos in listOf(30, VaultFormat.HEADER_SIZE - 1, 0)) {
            val t = enc.clone(); t[pos] = (t[pos].toInt() xor 0x40).toByte()
            assertTampered(t)
        }
    }

    @Test fun truncatedOrExtendedIsRefused() {
        val enc = VaultEncryptor.encryptBytes(data(5000), key, 1024)
        assertTampered(enc.copyOf(enc.size - 1))
        assertTampered(enc.copyOf(enc.size - (1024 + 16)))
        assertTampered(enc + ByteArray(1040))
        assertTampered(enc.copyOf(10))
    }

    @Test fun cuttingWholeLastChunksIsRefusedEvenWithFixedLength() {
        // Rewrite the header's length so the size check passes: the last-chunk flag / AAD still fails.
        val enc = VaultEncryptor.encryptBytes(data(4096), key, 1024)
        val cut = enc.copyOf(VaultFormat.HEADER_SIZE + 2 * (1024 + 16))
        java.nio.ByteBuffer.wrap(cut).putLong(8 + 1 + 4, 2048L)
        assertTampered(cut)
    }

    @Test fun swappedChunksAreRefused() {
        val enc = VaultEncryptor.encryptBytes(data(4096), key, 1024)
        val c = 1024 + 16
        val h = VaultFormat.HEADER_SIZE
        val t = enc.clone()
        System.arraycopy(enc, h, t, h + c, c)
        System.arraycopy(enc, h + c, t, h, c)
        assertTampered(t)
    }

    @Test fun wrongKeyIsRefused() {
        val enc = VaultEncryptor.encryptBytes(data(3000), key, 1024)
        val other = key.clone(); other[0] = (other[0].toInt() xor 1).toByte()
        assertTampered(enc, other)
    }

    @Test fun emptyFileStillAuthenticated() {
        val enc = VaultEncryptor.encryptBytes(ByteArray(0), key, 1024)
        assertEquals(VaultFormat.HEADER_SIZE + 16, enc.size)
        VaultReader(BytesSource(enc), key).use { assertEquals(0, it.readAll().size) }
        val t = enc.clone(); t[t.size - 1] = (t[t.size - 1].toInt() xor 1).toByte()
        assertThrows(VaultTamperException::class.java) { VaultReader(BytesSource(t), key).use { it.verifyAll() } }
    }

    @Test fun shortInputFails() {
        val out = java.io.ByteArrayOutputStream()
        assertThrows(java.io.IOException::class.java) {
            VaultEncryptor.encrypt(ByteArray(10).inputStream(), 20, out, key, 1024)
        }
        assertThrows(java.io.IOException::class.java) {
            VaultEncryptor.encrypt(ByteArray(30).inputStream(), 20, java.io.ByteArrayOutputStream(), key, 1024)
        }
    }

    @Test fun closedReaderRefusesReads() {
        val enc = VaultEncryptor.encryptBytes(data(3000), key, 1024)
        val r = VaultReader(BytesSource(enc), key)
        r.close()
        assertThrows(java.io.IOException::class.java) { r.read(0, ByteArray(10), 0, 10) }
    }
}
