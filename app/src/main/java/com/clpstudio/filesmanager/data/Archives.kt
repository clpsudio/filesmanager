package com.clpstudio.filesmanager.data

import com.github.junrar.Archive
import com.github.junrar.exception.CrcErrorException
import com.github.junrar.exception.CorruptHeaderException
import com.github.junrar.exception.InitDeciphererFailedException
import com.github.junrar.exception.UnsupportedRarV5Exception
import com.github.junrar.rarfile.FileHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/** The archive families the app can open inside itself. */
enum class ArchiveFormat(val label: String) {
    ZIP("ZIP"), SEVEN_Z("7z"), RAR("RAR"), RAR5("RAR5"), OTHER("Archive");

    val browsable: Boolean get() = this == ZIP || this == SEVEN_Z || this == RAR
}

/** A RAR5 archive: no offline pure-Java reader exists for it, so the app says so clearly. */
class Rar5NotSupportedException : IOException("RAR5 archives can't be opened in Files yet. RAR4 archives work.")

/** The archive's file list itself is locked (7z "encrypt file names", RAR "-hp"): it needs the password to list. */
class ArchivePasswordRequiredException : IOException("Password required")

/** What opening an archive without a password found. */
data class ArchiveProbe(
    val format: ArchiveFormat,
    /** Entries readable without a password (empty when [listLocked]). */
    val entries: List<ArchiveEntry>,
    /** Some file inside needs a password. */
    val encrypted: Boolean,
    /** Even the list of names needs the password. */
    val listLocked: Boolean,
)

/**
 * One door for every archive the app browses: ZIP (zip4j, see [ArchiveOps]), 7z (Apache Commons Compress + XZ for
 * LZMA/LZMA2, AES-256 passwords) and RAR4 (junrar, passwords incl. locked names). RAR5 is detected and refused with
 * [Rar5NotSupportedException]. All offline, pure Java. Paths that would escape the target folder are skipped.
 */
object Archives {
    private val SEVEN_Z_MAGIC = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
    private val RAR_MAGIC = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)

    fun formatOfName(name: String): ArchiveFormat = when (FileTypes.extension(name)) {
        "zip", "jar" -> ArchiveFormat.ZIP
        "7z" -> ArchiveFormat.SEVEN_Z
        "rar" -> ArchiveFormat.RAR
        else -> ArchiveFormat.OTHER
    }

    /** True when the app has its own browser for this file name (zip, jar, 7z, rar). */
    fun canBrowse(name: String): Boolean = formatOfName(name).browsable

    /** The real format from the first bytes (a renamed file still opens right), else from the name. */
    fun formatOf(file: File): ArchiveFormat {
        val head = ByteArray(8)
        val n = runCatching { file.inputStream().use { readFully(it, head) } }.getOrDefault(0)
        return formatOfHead(head, n) ?: formatOfName(file.name)
    }

    fun formatOfHead(head: ByteArray, n: Int): ArchiveFormat? = when {
        n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2].toInt() in 1..7 -> ArchiveFormat.ZIP
        n >= 6 && head.startsWith(SEVEN_Z_MAGIC) -> ArchiveFormat.SEVEN_Z
        n >= 8 && head.startsWith(RAR_MAGIC) && head[6].toInt() == 1 && head[7].toInt() == 0 -> ArchiveFormat.RAR5
        n >= 7 && head.startsWith(RAR_MAGIC) && head[6].toInt() == 0 -> ArchiveFormat.RAR
        else -> null
    }

    private fun ByteArray.startsWith(p: ByteArray) = size >= p.size && p.indices.all { this[it] == p[it] }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    /** Opens the archive without a password: format, the entries (when the list isn't locked), and whether it's locked. */
    fun probe(file: File): ArchiveProbe = when (val f = formatOf(file)) {
        ArchiveFormat.ZIP -> ArchiveOps.open(file).use { z ->
            ArchiveProbe(f, ArchiveOps.entries(z), z.isEncrypted || z.fileHeaders.any { it.isEncrypted }, false)
        }
        ArchiveFormat.SEVEN_Z -> try {
            openSevenZ(file, null).use { z ->
                val entries = z.entries.map { sevenZEntry(it) }
                ArchiveProbe(f, entries, sevenZNeedsPassword(z), false)
            }
        } catch (e: PasswordRequiredException) {
            ArchiveProbe(f, emptyList(), encrypted = true, listLocked = true)
        }
        ArchiveFormat.RAR -> openRar(file, null).use { a ->
            if (a.isEncrypted) ArchiveProbe(f, emptyList(), encrypted = true, listLocked = true)
            else ArchiveProbe(f, a.fileHeaders.map { rarEntry(it) }, a.isPasswordProtected || a.fileHeaders.any { it.isEncrypted }, false)
        }
        ArchiveFormat.RAR5 -> throw Rar5NotSupportedException()
        ArchiveFormat.OTHER -> throw IOException("This kind of archive can't be opened in Files.")
    }

    /** The full entry list (a locked list needs [password]; a wrong one throws [WrongPasswordException]). */
    fun list(file: File, password: CharArray?): List<ArchiveEntry> = when (formatOf(file)) {
        ArchiveFormat.ZIP -> ArchiveOps.open(file, password).use { ArchiveOps.entries(it) }
        ArchiveFormat.SEVEN_Z -> wrapPassword(password) { openSevenZ(file, password).use { z -> z.entries.map { sevenZEntry(it) } } }
        ArchiveFormat.RAR -> wrapPassword(password) {
            openRar(file, password).use { a ->
                if (a.isEncrypted && a.fileHeaders.isEmpty()) {
                    if (password == null || password.isEmpty()) throw ArchivePasswordRequiredException() else throw WrongPasswordException()
                }
                a.fileHeaders.map { rarEntry(it) }
            }
        }
        ArchiveFormat.RAR5 -> throw Rar5NotSupportedException()
        ArchiveFormat.OTHER -> throw IOException("This kind of archive can't be opened in Files.")
    }

    /** True when [password] opens the archive (or nothing in it is locked). Reads the smallest locked file to be sure. */
    fun checkPassword(file: File, password: CharArray?): Boolean = when (formatOf(file)) {
        ArchiveFormat.ZIP -> ArchiveOps.checkPassword(file, password)
        ArchiveFormat.SEVEN_Z -> try {
            openSevenZ(file, password).use { z ->
                val e = z.entries.filter { !it.isDirectory && it.hasStream() }.minByOrNull { it.size }
                if (e != null) z.getInputStream(e).use { drain(it) }
                true
            }
        } catch (e: Exception) {
            if (e is PasswordRequiredException || isWrongPassword(e)) false else throw e
        }
        ArchiveFormat.RAR -> try {
            openRar(file, password).use { a ->
                if (a.isEncrypted && a.fileHeaders.isEmpty()) return false
                val h = a.fileHeaders.filter { !it.isDirectory && it.isEncrypted }.minByOrNull { it.fullUnpackSize } ?: return true
                a.extractFile(h, NullOut)
                true
            }
        } catch (e: Exception) {
            if (isWrongPassword(e)) false else throw e
        }
        ArchiveFormat.RAR5 -> throw Rar5NotSupportedException()
        ArchiveFormat.OTHER -> false
    }

    fun isWrongPassword(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is WrongPasswordException || t is CrcErrorException || t is CorruptHeaderException ||
                t is InitDeciphererFailedException || t is org.tukaani.xz.CorruptedInputException
            ) return true
            if (ArchiveOps.isWrongPassword(t)) return true
            val m = t.message?.lowercase().orEmpty()
            if ("checksum" in m || "corrupt" in m || "bad 7z" in m) return true
            t = t.cause
        }
        return false
    }

    /** Extracts [paths] (null = everything) into [target], like [ArchiveOps.extract], for every browsable format. */
    suspend fun extract(
        file: File,
        password: CharArray?,
        target: File,
        paths: List<String>? = null,
        stripPrefix: String = "",
        onProgress: (OpProgress) -> Unit = {},
    ): Int {
        return when (formatOf(file)) {
            ArchiveFormat.ZIP -> ArchiveOps.extract(file, password, target, paths, stripPrefix, onProgress)
            ArchiveFormat.SEVEN_Z, ArchiveFormat.RAR -> withContext(Dispatchers.IO) { extractOther(file, password, target, paths, stripPrefix, onProgress) }
            ArchiveFormat.RAR5 -> throw Rar5NotSupportedException()
            ArchiveFormat.OTHER -> throw IOException("This kind of archive can't be opened in Files.")
        }
    }

    private fun wanted(name: String, paths: List<String>?) = paths == null || paths.any { p -> name == p || name.startsWith("$p/") }

    /** Where [name] goes under [root] (null = it would escape: "zip slip"). */
    private fun outFile(root: File, name: String, stripPrefix: String): File? {
        var rel = name
        if (stripPrefix.isNotEmpty() && rel.startsWith("$stripPrefix/")) rel = rel.removePrefix("$stripPrefix/")
        val out = File(root, rel).canonicalFile
        return if (out == root || out.path.startsWith(root.path + File.separator)) out else null
    }

    private suspend fun extractOther(
        file: File,
        password: CharArray?,
        target: File,
        paths: List<String>?,
        stripPrefix: String,
        onProgress: (OpProgress) -> Unit,
    ): Int {
        val root = target.canonicalFile
        SafAccess.mkdirsAny(SafAccess.appContext(), root)
        val title = "Extracting " + file.name
        var done = 0L
        var count = 0
        var last = 0L
        fun progressing(out: OutputStream, total: Long, items: Int, name: String) = object : OutputStream() {
            override fun write(b: Int) { out.write(b); done++ }
            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len)
                done += len
                if (done - last > 512 * 1024) {
                    last = done
                    onProgress(OpProgress(title, name, done, total, count, items))
                }
            }
            override fun close() = out.close()
        }
        when (formatOf(file)) {
            ArchiveFormat.SEVEN_Z -> wrapPassword(password) {
                openSevenZ(file, password).use { z ->
                    val all = z.entries.filter { wanted(sevenZName(it), paths) }
                    val total = all.filter { !it.isDirectory }.sumOf { it.size.coerceAtLeast(0) }
                    onProgress(OpProgress(title, totalBytes = total, totalItems = all.size))
                    val want = all.toHashSet()
                    // Sequential pass: the fast way through solid 7z blocks.
                    while (true) {
                        val e = z.nextEntry ?: break
                        if (e !in want) continue
                        coroutineContext.ensureActive()
                        val out = outFile(root, sevenZName(e), stripPrefix) ?: continue
                        if (e.isDirectory) { SafAccess.mkdirsAny(SafAccess.appContext(), out); continue }
                        out.parentFile?.let { SafAccess.mkdirsAny(SafAccess.appContext(), it) }
                        try {
                            progressing(SafAccess.outputAny(SafAccess.appContext(), out).buffered(128 * 1024), total, all.size, out.name).use { o ->
                                if (e.hasStream()) z.getInputStream(e).copyTo(o, 128 * 1024)
                            }
                        } catch (ex: Exception) {
                            out.delete(); throw ex
                        }
                        if (e.hasLastModifiedDate) runCatching { out.setLastModified(e.lastModifiedDate.time) }
                        count++
                    }
                    onProgress(OpProgress(title, "", total, total, count, all.size))
                }
            }
            ArchiveFormat.RAR -> wrapPassword(password) {
                openRar(file, password).use { a ->
                    if (a.isEncrypted && a.fileHeaders.isEmpty()) throw if (password == null) ArchivePasswordRequiredException() else WrongPasswordException()
                    val all = a.fileHeaders.filter { wanted(rarName(it), paths) }
                    val total = all.filter { !it.isDirectory }.sumOf { it.fullUnpackSize.coerceAtLeast(0) }
                    onProgress(OpProgress(title, totalBytes = total, totalItems = all.size))
                    for (h in all) {
                        coroutineContext.ensureActive()
                        val out = outFile(root, rarName(h), stripPrefix) ?: continue
                        if (h.isDirectory) { SafAccess.mkdirsAny(SafAccess.appContext(), out); continue }
                        out.parentFile?.let { SafAccess.mkdirsAny(SafAccess.appContext(), it) }
                        try {
                            progressing(SafAccess.outputAny(SafAccess.appContext(), out).buffered(128 * 1024), total, all.size, out.name).use { o -> a.extractFile(h, o) }
                        } catch (ex: Exception) {
                            out.delete(); throw ex
                        }
                        h.mTime?.let { runCatching { out.setLastModified(it.time) } }
                        count++
                    }
                    onProgress(OpProgress(title, "", total, total, count, all.size))
                }
            }
            else -> Unit
        }
        return count
    }

    /** Extracts one entry to [out] (opening a file from inside an archive). */
    suspend fun extractEntry(file: File, password: CharArray?, entry: String, out: File): File = withContext(Dispatchers.IO) {
        when (formatOf(file)) {
            ArchiveFormat.ZIP -> return@withContext ArchiveOps.extractEntry(file, password, entry, out)
            ArchiveFormat.SEVEN_Z -> wrapPassword(password) {
                openSevenZ(file, password).use { z ->
                    val e = z.entries.firstOrNull { sevenZName(it) == entry && !it.isDirectory } ?: throw IOException("Not found in the archive: $entry")
                    out.parentFile?.let { SafAccess.mkdirsAny(SafAccess.appContext(), it) }
                    try {
                        SafAccess.outputAny(SafAccess.appContext(), out).use { o -> if (e.hasStream()) z.getInputStream(e).use { it.copyTo(o, 128 * 1024) } }
                    } catch (ex: Exception) { out.delete(); throw ex }
                }
            }
            ArchiveFormat.RAR -> wrapPassword(password) {
                openRar(file, password).use { a ->
                    val h = a.fileHeaders.firstOrNull { rarName(it) == entry && !it.isDirectory } ?: throw IOException("Not found in the archive: $entry")
                    out.parentFile?.let { SafAccess.mkdirsAny(SafAccess.appContext(), it) }
                    try {
                        SafAccess.outputAny(SafAccess.appContext(), out).use { o -> a.extractFile(h, o) }
                    } catch (ex: Exception) { out.delete(); throw ex }
                }
            }
            ArchiveFormat.RAR5 -> throw Rar5NotSupportedException()
            ArchiveFormat.OTHER -> throw IOException("This kind of archive can't be opened in Files.")
        }
        out
    }

    /** "Locked ZIP" / "7z archive" ... for the top bar. */
    fun subtitle(format: ArchiveFormat, locked: Boolean): String = if (locked) "Locked ${format.label}" else "${format.label} archive"

    /** The headline for an archive that couldn't be opened. */
    fun failureTitle(e: Throwable): String = when (e) {
        is Rar5NotSupportedException -> "RAR5 not supported"
        is WrongPasswordException -> "Wrong password"
        else -> "Can't open this archive"
    }

    /** The message for an archive that couldn't be opened. */
    fun failureMessage(e: Throwable): String = when {
        e is Rar5NotSupportedException -> "This archive uses the newer RAR5 format, which Files can't open yet. RAR4, ZIP and 7z archives work."
        e is WrongPasswordException -> "The password doesn't open this archive."
        e is OutOfMemoryError -> "Not enough memory to open this archive."
        else -> "It may be damaged or not a supported archive."
    }

    // --- 7z ---

    private fun openSevenZ(file: File, password: CharArray?): SevenZFile =
        SevenZFile.builder().setFile(file).apply { if (password != null && password.isNotEmpty()) setPassword(password) }.get()

    private fun sevenZName(e: SevenZArchiveEntry) = e.name.orEmpty().replace('\\', '/').trimEnd('/')

    private fun sevenZEntry(e: SevenZArchiveEntry): ArchiveEntry {
        val name = sevenZName(e)
        val time = if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0L
        return ArchiveEntry(name, name.substringAfterLast('/'), e.isDirectory, if (e.isDirectory) 0 else e.size, time, false)
    }

    /** 7z keeps the AES coder per block; opening the smallest file's stream without a password tells (cheap: fails at setup). */
    private fun sevenZNeedsPassword(z: SevenZFile): Boolean {
        val e = z.entries.filter { !it.isDirectory && it.hasStream() }.minByOrNull { it.size } ?: return false
        return try {
            z.getInputStream(e).use { it.read() }
            false
        } catch (_: PasswordRequiredException) {
            true
        }
    }

    // --- RAR4 ---

    private fun openRar(file: File, password: CharArray?): Archive = try {
        Archive(file, password?.takeIf { it.isNotEmpty() }?.let { String(it) })
    } catch (e: UnsupportedRarV5Exception) {
        throw Rar5NotSupportedException()
    }

    private fun rarName(h: FileHeader) = h.fileName.orEmpty().replace('\\', '/').trimEnd('/')

    private fun rarEntry(h: FileHeader): ArchiveEntry {
        val name = rarName(h)
        return ArchiveEntry(name, name.substringAfterLast('/'), h.isDirectory, if (h.isDirectory) 0 else h.fullUnpackSize, h.mTime?.time ?: 0L, h.isEncrypted)
    }

    // --- helpers ---

    private inline fun <T> wrapPassword(password: CharArray?, block: () -> T): T = try {
        block()
    } catch (e: Rar5NotSupportedException) {
        throw e
    } catch (e: ArchivePasswordRequiredException) {
        throw e
    } catch (e: PasswordRequiredException) {
        throw ArchivePasswordRequiredException()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        if (password != null && password.isNotEmpty() && isWrongPassword(e)) throw WrongPasswordException()
        if (password == null && e is InitDeciphererFailedException) throw ArchivePasswordRequiredException()
        throw e
    }

    private fun drain(input: InputStream) {
        val buf = ByteArray(64 * 1024)
        while (input.read(buf) >= 0) { /* reading to the end checks the CRC */ }
    }

    private object NullOut : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}
