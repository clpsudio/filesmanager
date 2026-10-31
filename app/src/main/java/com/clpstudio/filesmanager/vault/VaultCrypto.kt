package com.clpstudio.filesmanager.vault

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A vault file failed its authentication check: changed, cut short, or not ours. Never shown as data. */
class VaultTamperException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The vault's file format: AES-256-GCM in independent chunks, so any byte range can be read (video seeking)
 * without decrypting the file from the start, and nothing plaintext is ever written to disk.
 *
 * Header (61 bytes, authenticated as part of every chunk's AAD):
 *   magic "CLPVLT01" (8) | version (1) | chunk size (4) | plaintext length (8) | file salt (32) | nonce prefix (8)
 * Then chunk i = AES-GCM(fileKey, nonce = prefix ‖ i (4 bytes BE), aad = header ‖ i ‖ last-flag) → ciphertext ‖ 16-byte tag.
 * fileKey = HMAC-SHA256(masterKey, "CLPVLT file key" ‖ salt): a fresh key per file, so nonces never repeat under one key.
 * The last-flag and the length in the header make cutting, extending, re-ordering or swapping chunks fail the tag.
 * An empty file still has one (empty, final) chunk so its length is authenticated too.
 */
object VaultFormat {
    val MAGIC = "CLPVLT01".toByteArray(Charsets.US_ASCII)
    const val VERSION: Byte = 1
    const val HEADER_SIZE = 8 + 1 + 4 + 8 + 32 + 8
    const val TAG_SIZE = 16
    const val DEFAULT_CHUNK = 64 * 1024
    const val MAX_CHUNK = 4 * 1024 * 1024

    fun chunkCount(length: Long, chunk: Int): Long = if (length == 0L) 1 else (length + chunk - 1) / chunk

    fun encryptedSize(length: Long, chunk: Int = DEFAULT_CHUNK): Long = HEADER_SIZE + length + chunkCount(length, chunk) * TAG_SIZE

    internal fun fileKey(masterKey: ByteArray, salt: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(masterKey, "HmacSHA256"))
        mac.update("CLPVLT file key".toByteArray(Charsets.US_ASCII))
        return mac.doFinal(salt)
    }

    internal fun nonce(prefix: ByteArray, index: Long): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(index.toInt()).array()

    internal fun aad(header: ByteArray, index: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(header.size + 5).put(header).putInt(index.toInt()).put(if (last) 1 else 0).array()
}

/** Random-access bytes: a file on disk in the app, a byte array in tests. */
interface SeekableSource : Closeable {
    val size: Long
    /** Reads exactly [len] bytes at [position] or throws. */
    fun readFully(position: Long, buf: ByteArray, off: Int, len: Int)
}

class FileSource(file: File) : SeekableSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()
    @Synchronized
    override fun readFully(position: Long, buf: ByteArray, off: Int, len: Int) {
        raf.seek(position)
        raf.readFully(buf, off, len)
    }
    override fun close() = raf.close()
}

class BytesSource(private val bytes: ByteArray) : SeekableSource {
    override val size: Long get() = bytes.size.toLong()
    override fun readFully(position: Long, buf: ByteArray, off: Int, len: Int) {
        if (position < 0 || position + len > bytes.size) throw java.io.EOFException()
        System.arraycopy(bytes, position.toInt(), buf, off, len)
    }
    override fun close() {}
}

object VaultEncryptor {
    private val random = SecureRandom()

    /**
     * Encrypts exactly [length] bytes of [input] into [output]. Returns the SHA-256 of the plaintext
     * (the caller checks it against a full decrypt before the original is deleted).
     */
    fun encrypt(
        input: InputStream,
        length: Long,
        output: OutputStream,
        masterKey: ByteArray,
        chunk: Int = VaultFormat.DEFAULT_CHUNK,
        onProgress: (Long) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): ByteArray {
        require(chunk in 16..VaultFormat.MAX_CHUNK) { "chunk size" }
        require(length >= 0)
        val salt = ByteArray(32).also(random::nextBytes)
        val prefix = ByteArray(8).also(random::nextBytes)
        val header = ByteBuffer.allocate(VaultFormat.HEADER_SIZE)
            .put(VaultFormat.MAGIC).put(VaultFormat.VERSION).putInt(chunk).putLong(length).put(salt).put(prefix).array()
        output.write(header)
        val key = SecretKeySpec(VaultFormat.fileKey(masterKey, salt), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val digest = MessageDigest.getInstance("SHA-256")
        val count = VaultFormat.chunkCount(length, chunk)
        val buf = ByteArray(chunk)
        var done = 0L
        for (i in 0 until count) {
            if (cancelled()) throw kotlinx.coroutines.CancellationException("Stopped")
            val n = minOf(chunk.toLong(), length - done).toInt()
            readExactly(input, buf, n)
            digest.update(buf, 0, n)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, VaultFormat.nonce(prefix, i)))
            cipher.updateAAD(VaultFormat.aad(header, i, i == count - 1))
            output.write(cipher.doFinal(buf, 0, n))
            done += n
            onProgress(done)
        }
        if (input.read() != -1) throw IOException("The file changed while it was being moved")
        return digest.digest()
    }

    fun encryptBytes(plain: ByteArray, masterKey: ByteArray, chunk: Int = VaultFormat.DEFAULT_CHUNK): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        encrypt(plain.inputStream(), plain.size.toLong(), out, masterKey, chunk)
        return out.toByteArray()
    }

    private fun readExactly(input: InputStream, buf: ByteArray, n: Int) {
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw IOException("The file changed while it was being moved")
            off += r
        }
    }
}

/**
 * Reads plaintext at any offset of a vault file. Decrypts only the chunks a read touches (two cached),
 * checking each chunk's tag; a failed tag throws [VaultTamperException], never returns bytes.
 */
open class VaultReader(private val source: SeekableSource, masterKey: ByteArray) : Closeable {
    val size: Long
    private val chunk: Int
    private val header = ByteArray(VaultFormat.HEADER_SIZE)
    private val prefix: ByteArray
    private val key: SecretKeySpec
    private val count: Long
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var cacheA: Pair<Long, ByteArray>? = null
    private var cacheB: Pair<Long, ByteArray>? = null
    @Volatile private var closed = false

    init {
        if (source.size < VaultFormat.HEADER_SIZE) throw VaultTamperException("Not a vault file")
        source.readFully(0, header, 0, header.size)
        val b = ByteBuffer.wrap(header)
        val magic = ByteArray(8).also { b.get(it) }
        if (!magic.contentEquals(VaultFormat.MAGIC)) throw VaultTamperException("Not a vault file")
        val version = b.get()
        if (version != VaultFormat.VERSION) throw VaultTamperException("Unknown vault file version $version")
        chunk = b.int
        size = b.long
        if (chunk < 16 || chunk > VaultFormat.MAX_CHUNK || size < 0) throw VaultTamperException("Damaged vault file header")
        val salt = ByteArray(32).also { b.get(it) }
        prefix = ByteArray(8).also { b.get(it) }
        count = VaultFormat.chunkCount(size, chunk)
        if (count > Int.MAX_VALUE) throw VaultTamperException("Damaged vault file header")
        if (source.size != VaultFormat.encryptedSize(size, chunk)) throw VaultTamperException("The vault file was cut short or changed")
        key = SecretKeySpec(VaultFormat.fileKey(masterKey, salt), "AES")
    }

    @Synchronized
    private fun chunkAt(index: Long): ByteArray {
        if (closed) throw IOException("The vault is locked")
        cacheA?.let { if (it.first == index) return it.second }
        cacheB?.let { if (it.first == index) { val t = cacheA; cacheA = it; cacheB = t; return it.second } }
        val plainLen = if (size == 0L) 0 else minOf(chunk.toLong(), size - index * chunk).toInt()
        val enc = ByteArray(plainLen + VaultFormat.TAG_SIZE)
        source.readFully(VaultFormat.HEADER_SIZE + index * (chunk + VaultFormat.TAG_SIZE), enc, 0, enc.size)
        val plain = try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, VaultFormat.nonce(prefix, index)))
            cipher.updateAAD(VaultFormat.aad(header, index, index == count - 1))
            cipher.doFinal(enc)
        } catch (e: AEADBadTagException) {
            throw VaultTamperException("This vault file is damaged or was changed (part ${index + 1})", e)
        } catch (e: java.security.GeneralSecurityException) {
            throw VaultTamperException("This vault file can't be read", e)
        }
        cacheB = cacheA
        cacheA = index to plain
        return plain
    }

    /** Reads up to [len] bytes at [position]; returns -1 at the end. */
    fun read(position: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (position >= size || len == 0) return if (len == 0) 0 else -1
        var pos = position
        var o = off
        var left = minOf(len.toLong(), size - position).toInt()
        val total = left
        while (left > 0) {
            val idx = pos / chunk
            val c = chunkAt(idx)
            val within = (pos - idx * chunk).toInt()
            val n = minOf(left, c.size - within)
            System.arraycopy(c, within, buf, o, n)
            pos += n; o += n; left -= n
        }
        return total
    }

    fun inputStream(from: Long = 0L): InputStream = object : InputStream() {
        var pos = from
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = this@VaultReader.read(pos, b, off, len)
            if (n > 0) pos += n
            return n
        }
        override fun skip(n: Long): Long {
            val s = minOf(n, (size - pos).coerceAtLeast(0))
            pos += s
            return s
        }
        override fun available(): Int = minOf(Int.MAX_VALUE.toLong(), (size - pos).coerceAtLeast(0)).toInt()
    }

    /** Decrypts everything once (every tag checked); returns the plaintext SHA-256. */
    fun verifyAll(cancelled: () -> Boolean = { false }): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (i in 0 until count) {
            if (cancelled()) throw kotlinx.coroutines.CancellationException("Stopped")
            val c = chunkAt(i)
            digest.update(c)
        }
        return digest.digest()
    }

    fun readAll(): ByteArray {
        if (size > Int.MAX_VALUE - 16) throw IOException("Too large to open in memory")
        val out = ByteArray(size.toInt())
        var p = 0L
        while (p < size) { val n = read(p, out, p.toInt(), (size - p).toInt()); if (n <= 0) break; p += n }
        return out
    }

    override fun close() {
        closed = true
        synchronized(this) { cacheA = null; cacheB = null }
        source.close()
    }
}
