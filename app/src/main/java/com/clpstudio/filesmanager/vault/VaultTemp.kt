package com.clpstudio.filesmanager.vault

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * Item 13 (F11): vault files of every type open in the app's own viewers. PDF / text / photos / video / audio are
 * read straight from the encrypted file (no plaintext copy, as before). Other types (Word / Excel / PowerPoint,
 * EPUB, archives, APKs, fonts, design files …) need a real file for their viewers: the file is decrypted into the
 * app's private storage (not shared storage, not the cache other apps' cleaners touch, never in MediaStore),
 * and wiped — overwritten, then deleted — when its viewer closes, when the vault locks, and at the next start.
 */
object VaultTemp {
    private const val DIR = "vault-open"
    @Volatile private var root: File? = null

    fun init(context: Context) {
        root = File(context.noBackupFilesDir, DIR)
        wipeAll() // left over from a crash / kill
    }

    fun rootOrNull(): File? = root

    /** Is [path] a decrypted vault file (its viewer must act like a vault screen)? */
    fun isTemp(path: String?, base: File? = root): Boolean {
        if (path.isNullOrEmpty() || base == null) return false
        return runCatching { File(path).canonicalPath.startsWith(base.canonicalPath + File.separator) }.getOrDefault(false)
    }

    /** Decrypts [item] for its viewer; the name stays (viewers pick the format by extension). */
    fun export(item: VaultItem, store: VaultStore = Vault.store, base: File = root ?: error("VaultTemp not initialised")): File {
        val dir = File(base, UUID.randomUUID().toString()).apply { mkdirs() }
        val safe = item.name.replace('/', '_').replace('\\', '_').ifBlank { "file" }
        val out = File(dir, safe)
        store.openReader(item).use { r -> r.inputStream().use { input -> out.outputStream().use { input.copyTo(it, 256 * 1024) } } }
        return out
    }

    /** Overwrites [path] with zeros, then deletes it (and its folder). */
    fun wipe(path: String, base: File? = root) {
        if (!isTemp(path, base)) return
        val f = File(path)
        shred(f)
        f.parentFile?.let { p -> p.listFiles()?.forEach { shredTree(it) }; p.delete() }
    }

    fun wipeAll(base: File? = root) {
        val b = base ?: return
        b.listFiles()?.forEach { shredTree(it) }
    }

    private fun shredTree(f: File) {
        if (f.isDirectory) { f.listFiles()?.forEach { shredTree(it) }; f.delete() } else shred(f)
    }

    private fun shred(f: File) {
        if (!f.isFile) return
        runCatching {
            RandomAccessFile(f, "rw").use { raf ->
                val zeros = ByteArray(64 * 1024)
                var left = raf.length()
                raf.seek(0)
                while (left > 0) { val n = minOf(left, zeros.size.toLong()).toInt(); raf.write(zeros, 0, n); left -= n }
                raf.fd.sync()
            }
        }
        f.delete()
    }
}
