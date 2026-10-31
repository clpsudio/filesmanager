package com.clpstudio.filesmanager.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.clpstudio.filesmanager.vault.KeyWrapper
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * The vault's hardware layer: an AES-256-GCM key that lives inside the phone's Android Keystore
 * (StrongBox secure chip when the phone has one, else the TEE). The key never leaves the hardware;
 * only data is sent in to be wrapped / unwrapped. Clearing app data or uninstalling deletes it.
 */
class KeystoreKeyWrapper(private val alias: String = "vault_wrap") : KeyWrapper {
    private val ks: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    override val kind: String
        get() = runCatching {
            val key = key()
            val info = SecretKeyFactory.getInstance(key.algorithm, PROVIDER)
                .getKeySpec(key, android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
            when {
                Build.VERSION.SDK_INT >= 31 && info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                Build.VERSION.SDK_INT >= 31 && info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "Hardware (TEE)"
                Build.VERSION.SDK_INT >= 31 -> "Software keystore"
                insideHardware(info) -> if (strongBoxMade) "StrongBox" else "Hardware (TEE)"
                else -> "Software keystore"
            }
        }.getOrDefault("Android Keystore")

    override fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        val ivLen = wrapped[0].toInt()
        require(ivLen in 12..16 && wrapped.size > 1 + ivLen + 16) { "damaged" }
        val c = Cipher.getInstance(TRANSFORM)
        val k = ks.getKey(alias, null) as? SecretKey ?: throw IllegalStateException("The vault's hardware key is gone")
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, wrapped, 1, ivLen))
        return c.doFinal(wrapped, 1 + ivLen, wrapped.size - 1 - ivLen)
    }

    override fun destroy() {
        runCatching { ks.deleteEntry(alias) }
    }

    @Suppress("DEPRECATION")
    private fun insideHardware(info: android.security.keystore.KeyInfo) = info.isInsideSecureHardware

    private fun key(): SecretKey {
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        fun spec(strongBox: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
            if (strongBox && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
            return b.build()
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                gen.init(spec(true))
                val k = gen.generateKey()
                strongBoxMade = true
                return k
            } catch (e: StrongBoxUnavailableException) {
                // no secure chip on this phone: TEE below
            } catch (e: java.security.ProviderException) {
                // some phones report StrongBox but fail to create the key
            }
        }
        gen.init(spec(false))
        return gen.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        @Volatile var strongBoxMade = false
    }
}
