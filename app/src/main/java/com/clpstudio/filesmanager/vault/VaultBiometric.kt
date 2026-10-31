package com.clpstudio.filesmanager.vault

import android.app.Activity
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Item 5 (F11): fingerprint opens the vault. A second copy of the vault's master key is wrapped by an Android
 * Keystore AES-256-GCM key that only works right after a strong fingerprint (auth per use, CryptoObject) and is
 * destroyed by Android when a new fingerprint is enrolled. The files' AES-256-GCM encryption and the PIN / password /
 * pattern stay exactly as they are (fingerprint is an extra way in, never a recovery: a forgotten PIN still can't be
 * recovered — the fingerprint only works while that same key exists). Android 10+.
 */
object VaultBiometric {
    private const val ALIAS = "files_vault_biometric_v1"

    fun status(context: Context): com.clpstudio.filesmanager.security.Biometrics.Status =
        com.clpstudio.filesmanager.security.Biometrics.status(context, com.clpstudio.filesmanager.security.Biometrics.Purpose.VAULT_KEY)

    /** The phone has a strong fingerprint set up and is Android 10+ (a busy sensor still counts). */
    fun available(context: Context): Boolean = com.clpstudio.filesmanager.security.Biometrics.usable(status(context))

    /** Fingerprint unlock is turned on for the vault (a wrapped copy exists). */
    fun enabled(): Boolean = runCatching { Vault.store.keyFile.biometric() != null }.getOrDefault(false)

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun newKey(): SecretKey {
        runCatching { keyStore().deleteEntry(ALIAS) }
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        else @Suppress("DEPRECATION") b.setUserAuthenticationValidityDurationSeconds(-1)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(b.build()) }.generateKey()
    }

    private fun key(): SecretKey? = runCatching { keyStore().getKey(ALIAS, null) as? SecretKey }.getOrNull()

    /** Turns fingerprint off: the wrapped copy and the Keystore key go. */
    fun disable() {
        runCatching { Vault.store.keyFile.clearBiometric() }
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    @androidx.annotation.RequiresApi(29)
    private fun prompt(
        activity: Activity, title: String, cipher: Cipher, negative: String,
        onCipher: (Cipher) -> Unit, onError: (String) -> Unit,
    ): android.os.CancellationSignal? = com.clpstudio.filesmanager.security.Biometrics.prompt(
        activity, title, negative, com.clpstudio.filesmanager.security.Biometrics.Purpose.VAULT_KEY,
        crypto = android.hardware.biometrics.BiometricPrompt.CryptoObject(cipher),
        onSuccess = { result ->
            val c = result?.cryptoObject?.cipher
            if (c == null) onError("Fingerprint didn't unlock the key") else onCipher(c)
        },
        onMessage = { m -> if (m != null) onError(m) },
    )

    /** The Keystore key is gone for good (new fingerprint enrolled / all removed). */
    fun invalidated(e: Throwable): Boolean = e is KeyPermanentlyInvalidatedException || e.cause is KeyPermanentlyInvalidatedException

    fun unlockErrorMessage(e: Throwable): String =
        if (invalidated(e)) "A new fingerprint was added, so fingerprint unlock was turned off. Unlock with your PIN and turn it on again."
        else "Fingerprint isn't available: ${e.message ?: e.javaClass.simpleName}"

    /** Turn on (the vault must be unlocked): one fingerprint to seal a copy of the master key. */
    fun enable(activity: Activity, onDone: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 29) return onDone("Needs Android 10 or newer")
        val mk = runCatching { Vault.store.masterKeyCopy() }.getOrNull() ?: return onDone("Unlock the vault first")
        val cipher = runCatching { Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, newKey()) } }
            .getOrElse { mk.fill(0); return onDone("This phone's key store refused: ${it.message}") }
        prompt(activity, "Turn on fingerprint for the vault", cipher, "Cancel", onCipher = { c ->
            val r = runCatching {
                val wrapped = c.doFinal(mk)
                Vault.store.keyFile.saveBiometric(c.iv, wrapped)
            }
            mk.fill(0)
            onDone(r.exceptionOrNull()?.let { "Couldn't turn it on: ${it.message}" })
        }, onError = { mk.fill(0); onDone(it) })
    }

    /** Unlock with a fingerprint. [onDone] gets null when the vault is open, else a message. */
    fun unlock(activity: Activity, negative: String, onDone: (String?) -> Unit): android.os.CancellationSignal? {
        if (Build.VERSION.SDK_INT < 29) { onDone("Needs Android 10 or newer"); return null }
        if (Vault.remainingMs() > 0) { onDone("Too many wrong tries — wait for the countdown"); return null }
        val (iv, wrapped) = Vault.store.keyFile.biometric() ?: run { onDone("Fingerprint isn't turned on for the vault"); return null }
        val k = key() ?: run { disable(); onDone("Fingerprint was reset on this phone. Unlock with your PIN and turn it on again."); return null }
        val cipher = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv)) }
        } catch (e: Exception) {
            // A new fingerprint added on the phone invalidates the key (KeyPermanentlyInvalidatedException): turn it
            // off cleanly; turning it on again makes a new key.
            if (invalidated(e)) disable()
            onDone(unlockErrorMessage(e)); return null
        }
        return prompt(activity, "Open the vault", cipher, negative, onCipher = { c ->
            val mk = runCatching { c.doFinal(wrapped) }.getOrNull()
            if (mk == null || !Vault.store.keyFile.checkMasterKey(mk)) {
                mk?.fill(0)
                onDone("Fingerprint key didn't match this vault. Unlock with your PIN.")
            } else {
                Vault.unlockWithMasterKey(mk)
                onDone(null)
            }
        }, onError = onDone)
    }
}
