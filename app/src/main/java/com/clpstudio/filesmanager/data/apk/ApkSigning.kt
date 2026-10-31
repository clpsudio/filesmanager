package com.clpstudio.filesmanager.data.apk

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.zip.ZipFile

/** One signing certificate, ready to show. */
data class CertInfo(
    val sha256: String,
    val sha1: String,
    val subject: String,
    val issuer: String,
    val notBefore: Long,
    val notAfter: Long,
    val algorithm: String,
    val key: String,
    val serial: String,
) {
    /** Signed with the Android SDK's debug key (an app built for testing). */
    val isDebug: Boolean get() = subject.contains("CN=Android Debug", ignoreCase = true)
    fun expired(now: Long = System.currentTimeMillis()) = now > notAfter
}

/** What signs an APK: the schemes found (1 = JAR, 2, 3, 3.1) and the signer certificates. */
data class SigningFacts(val schemes: List<String>, val certs: List<CertInfo>)

/**
 * Reads an APK's signing certificates offline, without PackageManager: the APK Signing Block (v2 / v3 / v3.1,
 * placed just before the zip central directory) and the old JAR signature (META-INF/ *.RSA|DSA|EC, PKCS#7).
 */
object ApkSigning {
    private const val V2 = 0x7109871a
    private const val V3 = 0xf05368c0.toInt()
    private const val V31 = 0x1b93ad61
    private val MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)

    fun read(file: File): SigningFacts {
        val schemes = mutableListOf<String>()
        var certs: List<X509Certificate> = emptyList()
        runCatching {
            val blocks = signingBlock(file)
            blocks[V31]?.let { schemes += "v3.1"; certs = certs.ifEmpty { signerCerts(it, v3 = true) } }
            blocks[V3]?.let { schemes += "v3"; if (certs.isEmpty()) certs = signerCerts(it, v3 = true) }
            blocks[V2]?.let { schemes += "v2"; if (certs.isEmpty()) certs = signerCerts(it, v3 = false) }
        }
        runCatching {
            val v1 = jarCerts(file)
            if (v1.isNotEmpty()) { schemes += "v1"; if (certs.isEmpty()) certs = v1 }
        }
        return SigningFacts(schemes.sortedBy { order(it) }, certs.map { describe(it) })
    }

    private fun order(s: String) = when (s) { "v1" -> 0; "v2" -> 1; "v3" -> 2; else -> 3 }

    /** id -> value of each pair in the APK Signing Block (empty when the APK has none). */
    fun signingBlock(file: File): Map<Int, ByteArray> = RandomAccessFile(file, "r").use { raf ->
        val len = raf.length()
        val tailLen = minOf(len, 65_557L).toInt()
        val tail = ByteArray(tailLen)
        raf.seek(len - tailLen); raf.readFully(tail)
        var eocd = -1
        for (i in tailLen - 22 downTo 0) {
            if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() && tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()) { eocd = i; break }
        }
        if (eocd < 0) return emptyMap()
        val cdOffset = ByteBuffer.wrap(tail, eocd + 16, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        if (cdOffset < 32) return emptyMap()
        val footer = ByteArray(24)
        raf.seek(cdOffset - 24); raf.readFully(footer)
        if (!footer.copyOfRange(8, 24).contentEquals(MAGIC)) return emptyMap()
        val blockSize = ByteBuffer.wrap(footer, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        if (blockSize < 24 || blockSize > cdOffset || blockSize > 64L * 1024 * 1024) return emptyMap()
        val start = cdOffset - blockSize - 8
        val pairsLen = (blockSize - 24).toInt()
        val pairs = ByteArray(pairsLen)
        raf.seek(start + 8); raf.readFully(pairs)
        val b = ByteBuffer.wrap(pairs).order(ByteOrder.LITTLE_ENDIAN)
        val out = LinkedHashMap<Int, ByteArray>()
        while (b.remaining() >= 12) {
            val l = b.long
            if (l < 4 || l > b.remaining()) break
            val id = b.int
            val v = ByteArray((l - 4).toInt()); b.get(v)
            out[id] = v
        }
        out
    }

    private fun ByteBuffer.lp(): ByteBuffer {
        val n = int
        require(n in 0..remaining()) { "Bad length" }
        val s = slice().order(ByteOrder.LITTLE_ENDIAN); s.limit(n)
        position(position() + n)
        return s
    }

    /** The first signer's certificates of a v2 / v3 block. */
    private fun signerCerts(value: ByteArray, v3: Boolean): List<X509Certificate> {
        val signers = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).lp()
        val cf = CertificateFactory.getInstance("X.509")
        while (signers.hasRemaining()) {
            val signer = signers.lp()
            val signed = signer.lp()
            signed.lp() // digests
            val certSeq = signed.lp()
            val list = mutableListOf<X509Certificate>()
            while (certSeq.hasRemaining()) {
                val c = certSeq.lp()
                val bytes = ByteArray(c.remaining()); c.get(bytes)
                list += cf.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
            }
            if (list.isNotEmpty()) return list
        }
        return emptyList()
    }

    /** JAR (v1) signature certificates from META-INF. */
    fun jarCerts(file: File): List<X509Certificate> = ZipFile(file).use { zip ->
        val e = zip.entries().asSequence().firstOrNull {
            val n = it.name.uppercase()
            n.startsWith("META-INF/") && n.count { c -> c == '/' } == 1 && (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))
        } ?: return emptyList()
        val bytes = zip.getInputStream(e).use { it.readBytes() }
        CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(bytes)).filterIsInstance<X509Certificate>()
    }

    fun fromDer(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    fun describe(c: X509Certificate): CertInfo {
        val der = c.encoded
        val key = when (val k = c.publicKey) {
            is RSAPublicKey -> "RSA ${k.modulus.bitLength()}-bit"
            is ECPublicKey -> "EC ${k.params.order.bitLength()}-bit"
            else -> k.algorithm
        }
        return CertInfo(
            sha256 = fingerprint(der, "SHA-256"),
            sha1 = fingerprint(der, "SHA-1"),
            subject = c.subjectX500Principal.name,
            issuer = c.issuerX500Principal.name,
            notBefore = c.notBefore.time,
            notAfter = c.notAfter.time,
            algorithm = c.sigAlgName,
            key = key,
            serial = BigInteger(1, c.serialNumber.toByteArray()).toString(16).uppercase(),
        )
    }

    /** "AB:CD:…" upper-case hex with colons, like apksigner / keytool print it. */
    fun fingerprint(bytes: ByteArray, alg: String): String =
        MessageDigest.getInstance(alg).digest(bytes).joinToString(":") { "%02X".format(it) }
}
