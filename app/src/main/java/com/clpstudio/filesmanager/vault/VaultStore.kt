package com.clpstudio.filesmanager.vault

import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.FileTypes
import com.clpstudio.filesmanager.data.uniqueName
import com.clpstudio.filesmanager.security.CredentialType
import com.clpstudio.filesmanager.security.Kdf
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.Properties
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** One file inside the vault. Its name and where it came from are encrypted too (in the .m file). */
data class VaultItem(
    val id: String,
    val name: String,
    val originalPath: String,
    val size: Long,
    val modified: Long,
    val added: Long,
) {
    val kind: FileKind get() = FileTypes.kindOf(name, false)
    val mime: String get() = FileTypes.mimeOf(name)
}

class VaultLockedException : IOException("The vault is locked")

/**
 * The vault on disk (pure JVM, no Android): `dir/keys.properties` (the wrapped master key),
 * `dir/files/<id>.v` (the file, AES-256-GCM chunks, see [VaultFormat]) and `dir/files/<id>.m`
 * (its name, original path, size and dates, AES-256-GCM). Nothing in the folder is readable
 * without the PIN/password AND this phone's hardware key. The folder is app-private and not backed up.
 */
class VaultStore(
    val dir: File,
    wrapper: KeyWrapper,
    iterations: Int = Kdf.DEFAULT_ITERATIONS,
    private val chunk: Int = VaultFormat.DEFAULT_CHUNK,
) {
    val keyFile = VaultKeyFile(File(dir, "keys.properties"), wrapper, iterations)
    private val filesDir = File(dir, "files")
    @Volatile private var masterKey: ByteArray? = null
    private val openReaders = Collections.synchronizedSet(mutableSetOf<VaultReader>())

    val isSetUp: Boolean get() = keyFile.exists
    val isUnlocked: Boolean get() = masterKey != null
    val credentialType: CredentialType? get() = keyFile.type()

    /** Items in the vault, counted without unlocking (only the number is visible, never names). */
    fun count(): Int = filesDir.listFiles { f -> f.name.endsWith(".v") }?.size ?: 0

    fun usedBytes(): Long = filesDir.listFiles()?.sumOf { it.length() } ?: 0L

    @Synchronized
    fun setup(type: CredentialType, secret: String) {
        if (isSetUp) throw IllegalStateException("The vault is already set up")
        filesDir.mkdirs()
        masterKey = keyFile.create(type, secret)
    }

    /** Throws [WrongSecretException] for a wrong PIN/password, [VaultKeyLostException] when the key can't be opened at all. */
    @Synchronized
    fun unlock(secret: String) {
        val mk = keyFile.unlock(secret)
        masterKey?.fill(0)
        masterKey = mk
        filesDir.mkdirs()
    }

    /** Forgets the key and closes everything that was reading from the vault (players, viewers). */
    @Synchronized
    fun lock() {
        masterKey?.fill(0)
        masterKey = null
        val readers = synchronized(openReaders) { openReaders.toList().also { openReaders.clear() } }
        readers.forEach { runCatching { it.close() } }
    }

    fun changeSecret(oldSecret: String, newType: CredentialType, newSecret: String) = keyFile.change(oldSecret, newType, newSecret)

    /** Checks a PIN/password without changing the open/locked state. */
    fun checkSecret(secret: String): Boolean = try {
        keyFile.unlock(secret).fill(0); true
    } catch (e: WrongSecretException) {
        false
    }

    /** Deletes every file in the vault and its key. Nothing can bring them back. */
    @Synchronized
    fun deleteVault() {
        lock()
        filesDir.listFiles()?.forEach { it.delete() }
        filesDir.delete()
        keyFile.delete()
    }

    private fun mk(): ByteArray = masterKey ?: throw VaultLockedException()

    fun list(): List<VaultItem> {
        val key = mk()
        val metas = filesDir.listFiles { f -> f.name.endsWith(".m") } ?: return emptyList()
        return metas.mapNotNull { m ->
            val id = m.name.removeSuffix(".m")
            if (!File(filesDir, "$id.v").isFile) return@mapNotNull null
            runCatching { readMeta(key, id, m) }.getOrNull()
        }.sortedByDescending { it.added }
    }

    /**
     * Encrypts [length] bytes from [input] into the vault. The copy is decrypted once more and its SHA-256
     * compared with the source before this returns, so the caller can delete the original safely.
     */
    fun import(
        name: String,
        originalPath: String,
        length: Long,
        modified: Long,
        input: InputStream,
        onProgress: (Long) -> Unit = {},
        cancelled: () -> Boolean = { false },
        now: Long = System.currentTimeMillis(),
    ): VaultItem {
        val key = mk()
        filesDir.mkdirs()
        val id = UUID.randomUUID().toString().replace("-", "")
        val part = File(filesDir, "$id.part")
        val data = File(filesDir, "$id.v")
        val meta = File(filesDir, "$id.m")
        try {
            val sha = part.outputStream().buffered(256 * 1024).use { out ->
                VaultEncryptor.encrypt(input, length, out, key, chunk, onProgress, cancelled)
            }
            val check = VaultReader(FileSource(part), key).use { it.verifyAll(cancelled) }
            if (!MessageDigest.isEqual(sha, check)) throw IOException("The vault copy of $name didn't check out")
            val item = VaultItem(id, name, originalPath, length, modified, now)
            writeMeta(key, item, meta)
            if (!part.renameTo(data)) throw IOException("Couldn't save $name in the vault")
            return item
        } catch (e: Throwable) {
            part.delete(); data.delete(); meta.delete()
            throw e
        }
    }

    fun importFile(file: File, onProgress: (Long) -> Unit = {}, cancelled: () -> Boolean = { false }): VaultItem =
        file.inputStream().use { import(file.name, file.absolutePath, file.length(), file.lastModified(), it, onProgress, cancelled) }

    /** A random-access reader for [item]; closed automatically when the vault locks. */
    fun openReader(item: VaultItem): VaultReader = openReader(item.id)

    fun openReader(id: String): VaultReader {
        val key = mk()
        val f = File(filesDir, "$id.v")
        if (!f.isFile) throw java.io.FileNotFoundException("Not in the vault")
        val r = TrackedReader(FileSource(f), key)
        openReaders += r
        return r
    }

    fun find(id: String): VaultItem? {
        val key = mk()
        val m = File(filesDir, "$id.m")
        if (!m.isFile || !File(filesDir, "$id.v").isFile) return null
        return runCatching { readMeta(key, id, m) }.getOrNull()
    }

    /**
     * Decrypts [item] into [destDir] (a "name (1).ext" when the name is taken), keeps its date, and returns the new file.
     * The vault copy is NOT removed here (see [remove]); a half-written file is deleted on failure.
     */
    fun export(item: VaultItem, destDir: File, onProgress: (Long) -> Unit = {}, cancelled: () -> Boolean = { false }): File {
        if (!destDir.isDirectory && !destDir.mkdirs()) throw IOException("Can't create ${destDir.name}")
        val name = uniqueName(item.name, false) { File(destDir, it).exists() || File(destDir, ".$it.vaultpart").exists() }
        val tmp = File(destDir, ".$name.vaultpart")
        val out = File(destDir, name)
        try {
            openReader(item).use { r ->
                val input = r.inputStream()
                tmp.outputStream().buffered(256 * 1024).use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        if (cancelled()) throw kotlinx.coroutines.CancellationException("Stopped")
                        val n = input.read(buf)
                        if (n <= 0) break
                        o.write(buf, 0, n)
                        done += n
                        onProgress(done)
                    }
                }
            }
            if (tmp.length() != item.size) throw IOException("${item.name} came out the wrong size")
            if (!tmp.renameTo(out)) throw IOException("Couldn't save ${item.name}")
            if (item.modified > 0) out.setLastModified(item.modified)
            return out
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /** Removes [item] from the vault for good. */
    fun remove(item: VaultItem): Boolean = remove(item.id)

    fun remove(id: String): Boolean {
        val d = File(filesDir, "$id.v").delete()
        File(filesDir, "$id.m").delete()
        return d
    }

    // --- metadata: Properties, sealed with AES-GCM under a key derived from the master key, bound to the id ---

    private fun metaKey(mk: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(mk, "HmacSHA256"))
        return mac.doFinal("CLPVLT meta key".toByteArray())
    }

    private fun writeMeta(mk: ByteArray, item: VaultItem, file: File) {
        val p = Properties()
        p.setProperty("name", item.name)
        p.setProperty("original", item.originalPath)
        p.setProperty("size", item.size.toString())
        p.setProperty("modified", item.modified.toString())
        p.setProperty("added", item.added.toString())
        val bytes = java.io.ByteArrayOutputStream().also { p.store(it, null) }.toByteArray()
        val k = metaKey(mk)
        try {
            file.writeBytes(Aead.seal(k, bytes, ("meta:" + item.id).toByteArray()))
        } finally { k.fill(0) }
    }

    private fun readMeta(mk: ByteArray, id: String, file: File): VaultItem {
        val k = metaKey(mk)
        val plain = try { Aead.open(k, file.readBytes(), ("meta:$id").toByteArray()) } finally { k.fill(0) }
        val p = Properties().apply { load(plain.inputStream()) }
        return VaultItem(
            id = id,
            name = p.getProperty("name") ?: "file",
            originalPath = p.getProperty("original").orEmpty(),
            size = p.getProperty("size")?.toLongOrNull() ?: 0L,
            modified = p.getProperty("modified")?.toLongOrNull() ?: 0L,
            added = p.getProperty("added")?.toLongOrNull() ?: 0L,
        )
    }

    private inner class TrackedReader(source: SeekableSource, key: ByteArray) : VaultReader(source, key) {
        override fun close() {
            openReaders -= this
            super.close()
        }
    }
}
