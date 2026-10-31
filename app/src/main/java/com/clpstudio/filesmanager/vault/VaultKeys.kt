package com.clpstudio.filesmanager.vault

import com.clpstudio.filesmanager.security.CredentialType
import com.clpstudio.filesmanager.security.Kdf
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The hardware layer around the master key. On a phone: an Android Keystore AES key (StrongBox when available). */
interface KeyWrapper {
    /** Short description for diagnostics/settings ("StrongBox", "Hardware (TEE)", "Software"). */
    val kind: String
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
    /** Forget the hardware key (vault deleted). */
    fun destroy() {}
}

/** Tests (and a fallback that is never used on a real device): AES-GCM under an in-memory key. */
class SoftwareKeyWrapper(private val key: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }) : KeyWrapper {
    override val kind = "Software"
    override fun wrap(plain: ByteArray): ByteArray = Aead.seal(key, plain, AAD)
    override fun unwrap(wrapped: ByteArray): ByteArray = Aead.open(key, wrapped, AAD)
    private companion object { val AAD = "hw-wrap".toByteArray() }
}

/** AES-256-GCM with a random 12-byte IV prepended. */
internal object Aead {
    private val random = SecureRandom()
    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return iv + c.doFinal(plain)
    }
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        if (sealed.size < 12 + 16) throw AEADBadTagException("short")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, 12))
        c.updateAAD(aad)
        return c.doFinal(sealed, 12, sealed.size - 12)
    }
}

class WrongSecretException : Exception("Wrong PIN or password")
class VaultKeyLostException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The vault's key file (vault/keys.properties, app-private, not backed up). It holds no secret on its own:
 * masterKey → AES-GCM under PBKDF2(PIN/password, salt) → AES-GCM under the Keystore key.
 * Both layers are needed: the PIN alone doesn't open it off this phone, the phone alone doesn't open it without the PIN.
 */
class VaultKeyFile(private val file: File, private val wrapper: KeyWrapper, private val iterations: Int = Kdf.DEFAULT_ITERATIONS) {
    private val random = SecureRandom()

    val exists: Boolean get() = file.isFile

    fun type(): CredentialType? = load()?.getProperty("type")?.let { runCatching { CredentialType.valueOf(it) }.getOrNull() }

    /** Where the wrapping key lives ("StrongBox", "Hardware (TEE)"), as recorded at setup. */
    fun hardware(): String? = load()?.getProperty("hardware")

    /** Creates a new master key protected by [secret]. Returns the master key (the vault is open after setup). */
    fun create(type: CredentialType, secret: String): ByteArray {
        val mk = ByteArray(32).also(random::nextBytes)
        save(type, secret, mk)
        return mk
    }

    /** The master key, or [WrongSecretException]. */
    fun unlock(secret: String): ByteArray {
        val p = load() ?: throw VaultKeyLostException("The vault isn't set up")
        val salt = b64(p, "salt")
        val iters = p.getProperty("iterations")?.toIntOrNull() ?: throw VaultKeyLostException("Damaged key file")
        val outer = b64(p, "wrapped")
        val inner = try {
            wrapper.unwrap(outer)
        } catch (e: Exception) {
            // The hardware key is gone (data cleared, phone reset) or the file was changed: nothing can open it.
            throw VaultKeyLostException("The vault key can't be opened on this phone", e)
        }
        val kek = Kdf.derive(secret, salt, iters)
        try {
            return Aead.open(kek, inner, AAD)
        } catch (e: AEADBadTagException) {
            throw WrongSecretException()
        } finally {
            kek.fill(0)
        }
    }

    /** Re-protects the same master key with a new PIN/password; files don't need re-encrypting. */
    fun change(oldSecret: String, newType: CredentialType, newSecret: String) {
        val mk = unlock(oldSecret)
        val bio = biometric() // same master key: the fingerprint copy stays valid
        try { save(newType, newSecret, mk) } finally { mk.fill(0) }
        if (bio != null && biometric() == null) saveBiometric(bio.first, bio.second)
    }

    /** Biometric copy of the master key (wrapped by a separate auth-bound Keystore key), stored beside. */
    fun saveBiometric(iv: ByteArray, wrapped: ByteArray) {
        val p = load() ?: return
        p.setProperty("bio_iv", Base64.getEncoder().encodeToString(iv))
        p.setProperty("bio_wrapped", Base64.getEncoder().encodeToString(wrapped))
        write(p)
    }

    fun biometric(): Pair<ByteArray, ByteArray>? {
        val p = load() ?: return null
        val iv = p.getProperty("bio_iv") ?: return null
        val w = p.getProperty("bio_wrapped") ?: return null
        return Base64.getDecoder().decode(iv) to Base64.getDecoder().decode(w)
    }

    fun clearBiometric() {
        val p = load() ?: return
        p.remove("bio_iv"); p.remove("bio_wrapped")
        write(p)
    }

    /** A value that proves a master key is this vault's (checks a biometric unlock). */
    fun checkMasterKey(mk: ByteArray): Boolean {
        val p = load() ?: return false
        val expect = p.getProperty("check")?.let { Base64.getDecoder().decode(it) } ?: return false
        return Kdf.equalsConstantTime(expect, check(mk))
    }

    fun delete() {
        file.delete()
        runCatching { wrapper.destroy() }
    }

    private fun save(type: CredentialType, secret: String, mk: ByteArray) {
        val salt = Kdf.salt(16)
        val kek = Kdf.derive(secret, salt, iterations)
        val inner = try { Aead.seal(kek, mk, AAD) } finally { kek.fill(0) }
        val outer = wrapper.wrap(inner)
        val p = load() ?: Properties()
        p.setProperty("version", "1")
        p.setProperty("type", type.name)
        p.setProperty("kdf", "PBKDF2-HMAC-SHA256")
        p.setProperty("iterations", iterations.toString())
        p.setProperty("salt", Base64.getEncoder().encodeToString(salt))
        p.setProperty("wrapped", Base64.getEncoder().encodeToString(outer))
        p.setProperty("hardware", wrapper.kind)
        p.setProperty("check", Base64.getEncoder().encodeToString(check(mk)))
        write(p)
    }

    private fun check(mk: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(mk, "HmacSHA256"))
        return mac.doFinal("CLPVLT check".toByteArray())
    }

    private fun load(): Properties? {
        if (!file.isFile) return null
        return Properties().apply { file.inputStream().use { load(it) } }
    }

    private fun write(p: Properties) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { p.store(it, null) }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun b64(p: Properties, key: String): ByteArray =
        p.getProperty(key)?.let { Base64.getDecoder().decode(it) } ?: throw VaultKeyLostException("Damaged key file")

    private companion object { val AAD = "CLPVLT master key v1".toByteArray() }
}
