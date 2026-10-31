package com.clpstudio.filesmanager.security

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * One fingerprint / face prompt for the app lock and the vault (F11 fix for "App lock with biometric not working").
 *
 * What was wrong in 1.0.21 (all silent, so the person just saw nothing happen):
 *  - the automatic prompt was asked for the moment the lock screen was drawn, before the window had focus and
 *    while the PIN keyboard was opening; many phones cancel such a prompt at once (error "canceled"),
 *  - every error from the prompt was ignored (no message, no retry), and exceptions were swallowed,
 *  - on phones where the prompt pauses / stops the app, coming back from a successful fingerprint counted as
 *    "opened the app again" and locked it again straight away ("Ask after: immediately").
 * Now: the prompt waits for window focus, a system cancel right after showing is retried once, every other
 * error is shown on the lock screen, and the moment of a fingerprint unlock is not counted as leaving the app.
 */
object Biometrics {
    enum class Purpose { APP_LOCK, VAULT_KEY }

    /** App lock: any biometric the phone offers (many phones' face unlock is "weak"). Vault: strong only — it opens a Keystore key. */
    fun authenticators(p: Purpose): Int = when (p) {
        Purpose.APP_LOCK -> BiometricManager.Authenticators.BIOMETRIC_WEAK
        Purpose.VAULT_KEY -> BiometricManager.Authenticators.BIOMETRIC_STRONG
    }

    enum class Status { READY, NOT_ENROLLED, NO_HARDWARE, BUSY, UPDATE_NEEDED, OLD_ANDROID }

    fun status(code: Int): Status = when (code) {
        BiometricManager.BIOMETRIC_SUCCESS -> Status.READY
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Status.NOT_ENROLLED
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Status.NO_HARDWARE
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Status.BUSY
        15 /* BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED */ -> Status.UPDATE_NEEDED
        else -> Status.NO_HARDWARE
    }

    fun status(context: android.content.Context, p: Purpose): Status {
        if (Build.VERSION.SDK_INT < 29) return Status.OLD_ANDROID
        val bm = context.getSystemService(BiometricManager::class.java) ?: return Status.NO_HARDWARE
        val code = runCatching {
            if (Build.VERSION.SDK_INT >= 30) bm.canAuthenticate(authenticators(p)) else @Suppress("DEPRECATION") bm.canAuthenticate()
        }.getOrDefault(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE)
        return status(code)
    }

    /** The button stays when the sensor is only busy for a moment (it used to vanish silently). */
    fun usable(s: Status): Boolean = s == Status.READY || s == Status.BUSY

    fun statusText(s: Status, p: Purpose): String = when (s) {
        Status.READY, Status.BUSY -> if (p == Purpose.APP_LOCK) "Your PIN, password or pattern always works too." else "Your vault PIN, password or pattern always works too."
        Status.NOT_ENROLLED -> if (p == Purpose.VAULT_KEY) "Add a fingerprint in the phone's Settings → Security first (face unlock isn't secure enough to open the vault's key on most phones)."
            else "Add a fingerprint or face in the phone's Settings → Security first."
        Status.NO_HARDWARE -> "This phone has no fingerprint sensor Files can use."
        Status.UPDATE_NEEDED -> "The phone needs a security update before fingerprint can be used."
        Status.OLD_ANDROID -> "Needs Android 10 or newer."
    }

    sealed interface Outcome {
        data object Silent : Outcome
        data object Retry : Outcome
        data class Show(val message: String) : Outcome
    }

    /** What to do with an error from the prompt, [shownMs] after it was asked for. */
    fun onError(code: Int, text: CharSequence?, shownMs: Long, retried: Boolean): Outcome = when (code) {
        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, 13 /* NEGATIVE_BUTTON */ -> Outcome.Silent
        BiometricPrompt.BIOMETRIC_ERROR_CANCELED ->
            if (shownMs < 1500 && !retried) Outcome.Retry else Outcome.Silent
        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT -> Outcome.Show("Too many tries with fingerprint. Wait 30 seconds, or use your PIN.")
        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> Outcome.Show("Fingerprint is locked until you unlock the phone itself. Use your PIN here.")
        BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS -> Outcome.Show("No fingerprint is set up on this phone any more.")
        BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Outcome.Show("The fingerprint sensor is busy. Tap \"Use fingerprint\" to try again.")
        BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT -> Outcome.Show("Fingerprint timed out. Tap \"Use fingerprint\" to try again.")
        else -> Outcome.Show(text?.toString()?.takeIf { it.isNotBlank() } ?: "Fingerprint didn't work. Use your PIN.")
    }

    /** True while a prompt is on screen (leaving the app for it isn't leaving the app). */
    @Volatile var promptOpen: Boolean = false
        private set

    /** Waits (up to [timeoutMs]) until the activity's window has focus, then a beat for the keyboard to settle. */
    suspend fun awaitFocus(activity: Activity, timeoutMs: Long = 3000) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (!activity.hasWindowFocus() && SystemClock.elapsedRealtime() < end) delay(50)
        delay(250)
    }

    /**
     * Shows the system prompt. [onSuccess] gets the result (its crypto object for the vault). [onMessage] gets a
     * message to show (or null to clear it). Returns a handle that cancels the prompt.
     */
    fun prompt(
        activity: Activity,
        title: String,
        negative: String,
        purpose: Purpose,
        crypto: BiometricPrompt.CryptoObject? = null,
        onSuccess: (BiometricPrompt.AuthenticationResult?) -> Unit,
        onMessage: (String?) -> Unit,
        retried: Boolean = false,
    ): CancellationSignal? {
        if (Build.VERSION.SDK_INT < 29) { onMessage("Needs Android 10 or newer"); return null }
        if (activity.isFinishing || activity.isDestroyed) return null
        val signal = CancellationSignal()
        val asked = SystemClock.elapsedRealtime()
        return try {
            val b = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setNegativeButton(negative, activity.mainExecutor) { _, _ -> promptOpen = false }
            if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(authenticators(purpose))
            if (Build.VERSION.SDK_INT >= 29) b.setConfirmationRequired(false)
            val cb = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    promptOpen = false
                    onMessage(null)
                    onSuccess(result)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    promptOpen = false
                    when (val o = onError(errorCode, errString, SystemClock.elapsedRealtime() - asked, retried)) {
                        Outcome.Silent -> Unit
                        Outcome.Retry -> activity.window?.decorView?.postDelayed({
                            if (!activity.isFinishing && activity.hasWindowFocus()) prompt(activity, title, negative, purpose, crypto, onSuccess, onMessage, retried = true)
                        }, 400)
                        is Outcome.Show -> onMessage(o.message)
                    }
                }
            }
            promptOpen = true
            if (crypto != null) b.build().authenticate(crypto, signal, activity.mainExecutor, cb)
            else b.build().authenticate(signal, activity.mainExecutor, cb)
            signal
        } catch (e: Exception) {
            promptOpen = false
            onMessage("Fingerprint isn't available: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }
}
