package com.clpstudio.filesmanager.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

data class ArchiveEntry(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val time: Long,
    val encrypted: Boolean,
)

class WrongPasswordException : IOException("Wrong password")

/**
 * ZIP tools (zip4j): browse, extract all or selected entries, and create ZIPs, with passwords.
 * Reads AES-128/192/256 and classic ZipCrypto archives; new locked ZIPs use AES-256.
 * Everything streams with progress and can be stopped. Paths that would escape the target
 * folder ("zip slip") are refused.
 */
object ArchiveOps {
    const val MIN_PASSWORD = 4

    /** The new folder a ZIP extracts into: next to it, named after it ("Photos.zip" → "Photos", then "Photos (1)"). */
    fun newFolderFor(zip: File, parent: File = zip.parentFile ?: File(".")): File =
        File(parent, uniqueName(zip.nameWithoutExtension, true) { File(parent, it).exists() })

    fun isZip(name: String): Boolean = FileTypes.extension(name) in setOf("zip", "jar")

    fun open(zip: File, password: CharArray? = null): ZipFile =
        if (password != null && password.isNotEmpty()) ZipFile(zip, password) else ZipFile(zip)

    fun isEncrypted(zip: File): Boolean = open(zip).use { z -> z.isEncrypted || z.fileHeaders.any { it.isEncrypted } }

    fun entries(zip: ZipFile): List<ArchiveEntry> = zip.fileHeaders.map { h ->
        val name = h.fileName.replace('\\', '/').trimEnd('/')
        ArchiveEntry(name, name.substringAfterLast('/'), h.isDirectory, h.uncompressedSize, h.lastModifiedTimeEpoch, h.isEncrypted)
    }

    /** Direct children of [dir] ("" = root), folders first; folders implied by deeper paths are included. */
    fun children(all: List<ArchiveEntry>, dir: String): List<ArchiveEntry> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val out = LinkedHashMap<String, ArchiveEntry>()
        for (e in all) {
            if (!e.path.startsWith(prefix) || e.path == dir) continue
            val rest = e.path.removePrefix(prefix)
            if (rest.isEmpty()) continue
            val first = rest.substringBefore('/')
            val isDir = rest.contains('/') || e.isDir
            val path = prefix + first
            if (!out.containsKey(path)) out[path] = ArchiveEntry(path, first, isDir, if (isDir) 0 else e.size, e.time, e.encrypted)
        }
        return out.values.sortedWith(compareBy<ArchiveEntry> { !it.isDir }.thenBy { it.name.lowercase() })
    }

    /** True if [password] opens the archive (or it isn't encrypted). */
    fun checkPassword(zip: File, password: CharArray?): Boolean = open(zip, password).use { z ->
        val h = z.fileHeaders.firstOrNull { it.isEncrypted && !it.isDirectory } ?: return true
        try {
            z.getInputStream(h).use { input -> drain(input) }
            true
        } catch (e: Exception) {
            if (isWrongPassword(e)) false else throw e
        }
    }

    private fun drain(input: InputStream) {
        val buf = ByteArray(64 * 1024)
        while (input.read(buf) >= 0) { /* reading to the end verifies the password / CRC */ }
    }

    fun isWrongPassword(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is WrongPasswordException) return true
            if (t is ZipException && (t.type == ZipException.Type.WRONG_PASSWORD || t.type == ZipException.Type.CHECKSUM_MISMATCH)) return true
            val m = t.message?.lowercase().orEmpty()
            if ("wrong password" in m || "password is required" in m || "checksum" in m || "crc" in m) return true
            t = t.cause
        }
        return false
    }

    /**
     * Extracts [paths] (entries or folders inside the archive; null = everything) into [target].
     * [stripPrefix] is removed from each entry path (extracting from inside a sub-folder).
     */
    suspend fun extract(
        zip: File,
        password: CharArray?,
        target: File,
        paths: List<String>? = null,
        stripPrefix: String = "",
        onProgress: (OpProgress) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        open(zip, password).use { z ->
            val headers = z.fileHeaders.filter { h ->
                val name = h.fileName.replace('\\', '/').trimEnd('/')
                paths == null || paths.any { p -> name == p || name.startsWith("$p/") }
            }
            val total = headers.filter { !it.isDirectory }.sumOf { it.uncompressedSize.coerceAtLeast(0) }
            val root = target.canonicalFile
            root.mkdirs()
            var done = 0L
            var count = 0
            val title = "Extracting " + zip.name
            onProgress(OpProgress(title, totalBytes = total, totalItems = headers.size))
            for (h in headers) {
                coroutineContext.ensureActive()
                var rel = h.fileName.replace('\\', '/')
                if (stripPrefix.isNotEmpty() && rel.startsWith("$stripPrefix/")) rel = rel.removePrefix("$stripPrefix/")
                val out = File(root, rel).canonicalFile
                if (out != root && !out.path.startsWith(root.path + File.separator)) continue // zip slip
                if (h.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                try {
                    z.getInputStream(h).use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(128 * 1024)
                            var last = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                done += n
                                if (done - last > 512 * 1024) {
                                    last = done
                                    coroutineContext.ensureActive()
                                    onProgress(OpProgress(title, rel.substringAfterLast('/'), done, total, count, headers.size))
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    out.delete()
                    if (isWrongPassword(e)) throw WrongPasswordException()
                    throw e
                }
                if (h.lastModifiedTimeEpoch > 0) out.setLastModified(h.lastModifiedTimeEpoch)
                count++
            }
            onProgress(OpProgress(title, "", total, total, count, headers.size))
            count
        }
    }

    /** Extracts one entry to [out] (opening a file from inside a ZIP). */
    suspend fun extractEntry(zip: File, password: CharArray?, entry: String, out: File): File = withContext(Dispatchers.IO) {
        open(zip, password).use { z ->
            val h: FileHeader = z.getFileHeader(entry) ?: z.fileHeaders.firstOrNull { it.fileName.replace('\\', '/') == entry }
                ?: throw IOException("Not found in the archive: $entry")
            out.parentFile?.mkdirs()
            try {
                z.getInputStream(h).use { i -> out.outputStream().use { i.copyTo(it, 128 * 1024) } }
            } catch (e: Exception) {
                out.delete()
                if (isWrongPassword(e)) throw WrongPasswordException()
                throw e
            }
            out
        }
    }

    /** Creates [out] from [sources] (files and folders). A password makes it an AES-256 locked ZIP. */
    suspend fun compress(
        sources: List<File>,
        out: File,
        password: CharArray? = null,
        onProgress: (OpProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        require(password == null || password.size >= MIN_PASSWORD) { "The password needs at least $MIN_PASSWORD characters" }
        val files = ArrayList<Pair<File, String>>() // file -> name in zip
        for (src in sources) {
            if (src.isDirectory) {
                val base = src.parentFile
                src.walkTopDown().forEach { f ->
                    val rel = f.relativeTo(base ?: src).path.replace('\\', '/')
                    files += f to if (f.isDirectory) "$rel/" else rel
                }
            } else {
                files += src to src.name
            }
        }
        val total = files.filter { it.first.isFile }.sumOf { it.first.length() }
        val title = "Compressing " + out.name
        var done = 0L
        var count = 0
        onProgress(OpProgress(title, totalBytes = total, totalItems = files.size))
        val tmp = File(out.parentFile, out.name + ".part")
        try {
            ZipOutputStream(tmp.outputStream().buffered(256 * 1024), password?.takeIf { it.isNotEmpty() }).use { zos ->
                val buf = ByteArray(128 * 1024)
                for ((f, name) in files) {
                    coroutineContext.ensureActive()
                    val p = ZipParameters().apply {
                        fileNameInZip = name
                        lastModifiedFileTime = f.lastModified()
                        if (f.isDirectory) {
                            compressionMethod = CompressionMethod.STORE
                        } else {
                            compressionMethod = CompressionMethod.DEFLATE
                            if (password != null) {
                                isEncryptFiles = true
                                encryptionMethod = EncryptionMethod.AES
                                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                            }
                        }
                    }
                    zos.putNextEntry(p)
                    if (f.isFile) {
                        f.inputStream().use { input ->
                            var last = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                zos.write(buf, 0, n)
                                done += n
                                if (done - last > 512 * 1024) {
                                    last = done
                                    coroutineContext.ensureActive()
                                    onProgress(OpProgress(title, f.name, done, total, count, files.size))
                                }
                            }
                        }
                    }
                    zos.closeEntry()
                    count++
                }
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) throw IOException("Couldn't save ${out.name}")
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
        out
    }

    /** "Photos.zip" for one item, else "<folder name>.zip" / "Archive.zip". */
    fun defaultZipName(sources: List<File>): String {
        val base = when {
            sources.size == 1 -> if (sources[0].isDirectory) sources[0].name else sources[0].nameWithoutExtension
            else -> sources.firstOrNull()?.parentFile?.name?.takeIf { it.isNotBlank() } ?: "Archive"
        }
        return "$base.zip"
    }
}
