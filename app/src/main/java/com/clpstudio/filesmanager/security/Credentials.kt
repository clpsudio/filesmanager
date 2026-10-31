package com.clpstudio.filesmanager.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PIN (4–10 digits) or password (8–72 characters), the same rules for the app lock and the vault. */
enum class CredentialType { PIN, PASSWORD }

object CredentialRules {
    const val PIN_MIN = 4
    const val PIN_MAX = 10
    const val PASSWORD_MIN = 8
    const val PASSWORD_MAX = 72

    /** Error text for a new PIN/password, or null when it's fine. */
    fun error(type: CredentialType, value: String): String? = when (type) {
        CredentialType.PIN -> when {
            value.isEmpty() -> "Enter a PIN"
            !value.all { it in '0'..'9' } -> "Digits only"
            value.length < PIN_MIN -> "At least $PIN_MIN digits"
            value.length > PIN_MAX -> "At most $PIN_MAX digits"
            else -> null
        }
        CredentialType.PASSWORD -> when {
            value.isEmpty() -> "Enter a password"
            value.length < PASSWORD_MIN -> "At least $PASSWORD_MIN characters"
            value.length > PASSWORD_MAX -> "At most $PASSWORD_MAX characters"
            value.isBlank() -> "Spaces only isn't a password"
            else -> null
        }
    }

    /** New value typed twice: the first problem, or null. */
    fun confirmError(type: CredentialType, value: String, repeat: String): String? =
        error(type, value) ?: if (value != repeat) (if (type == CredentialType.PIN) "The PINs don't match" else "The passwords don't match") else null

    /** What the field accepts while typing (a PIN field drops non-digits and stops at 10). */
    fun filterInput(type: CredentialType, value: String): String = when (type) {
        CredentialType.PIN -> value.filter { it in '0'..'9' }.take(PIN_MAX)
        CredentialType.PASSWORD -> value.take(PASSWORD_MAX)
    }
}

/** PBKDF2-HMAC-SHA256 with a random salt; the iteration count is stored beside the result. */
object Kdf {
    const val DEFAULT_ITERATIONS = 200_000
    private val random = SecureRandom()

    fun salt(size: Int = 16): ByteArray = ByteArray(size).also(random::nextBytes)

    fun derive(secret: String, salt: ByteArray, iterations: Int, bits: Int = 256): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, bits)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun equalsConstantTime(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
}

/**
 * Wrong-attempt handling: 5 free tries, then a wait that doubles (30 s, 1 min, 2 min … up to 15 min).
 * Kept on disk by the caller, so closing the app doesn't reset it.
 */
data class AttemptState(val failures: Int = 0, val lockedUntil: Long = 0L)

object AttemptLimiter {
    const val FREE_TRIES = 5
    const val FIRST_WAIT_MS = 30_000L
    const val MAX_WAIT_MS = 15 * 60_000L

    fun waitAfter(failures: Int): Long {
        if (failures < FREE_TRIES) return 0L
        val shift = (failures - FREE_TRIES).coerceAtMost(10)
        return (FIRST_WAIT_MS shl shift).coerceAtMost(MAX_WAIT_MS)
    }

    fun onFailure(s: AttemptState, now: Long): AttemptState {
        val f = s.failures + 1
        val wait = waitAfter(f)
        return AttemptState(f, if (wait > 0) now + wait else 0L)
    }

    fun onSuccess(): AttemptState = AttemptState()

    /** Milliseconds until another try is allowed (0 = now). */
    fun remaining(s: AttemptState, now: Long): Long = (s.lockedUntil - now).coerceAtLeast(0L)

    /** Tries left before the first wait (null once waits have started). */
    fun triesLeft(s: AttemptState): Int? = (FREE_TRIES - s.failures).takeIf { it > 0 }

    fun message(s: AttemptState, now: Long): String? {
        val r = remaining(s, now)
        if (r > 0) {
            val sec = (r + 999) / 1000
            return "Too many wrong tries. Try again in " + if (sec >= 60) "${(sec + 59) / 60} min" else "$sec s"
        }
        if (s.failures == 0) return null
        val left = triesLeft(s)
        return if (left != null) "Wrong. $left ${if (left == 1) "try" else "tries"} left" else "Wrong"
    }
}

/** "Ask after": how long the app may stay in the background before it asks again. */
enum class AskAfter(val label: String, val ms: Long) {
    IMMEDIATELY("Immediately", 0L),
    SEC_30("After 30 seconds", 30_000L),
    MIN_1("After 1 minute", 60_000L),
    MIN_5("After 5 minutes", 5 * 60_000L),
    MIN_15("After 15 minutes", 15 * 60_000L),
    HOUR_1("After 1 hour", 60 * 60_000L),
}

/** Lock timing. Times are a monotonic clock (elapsedRealtime) so changing the phone's clock doesn't skip the lock. */
class LockTimer {
    var locked: Boolean = false
        private set
    private var backgroundAt: Long? = null

    /** Cold start: locked whenever the lock is on. */
    fun onAppStart(enabled: Boolean) { locked = enabled; backgroundAt = null }

    fun onBackground(enabled: Boolean, askAfter: AskAfter, now: Long) {
        if (!enabled) return
        backgroundAt = now
        if (askAfter.ms == 0L) locked = true
    }

    fun onForeground(enabled: Boolean, askAfter: AskAfter, now: Long) {
        val since = backgroundAt
        backgroundAt = null
        if (!enabled) { locked = false; return }
        if (since != null && now - since >= askAfter.ms) locked = true
    }

    fun onUnlocked() { locked = false }

    fun onDisabled() { locked = false; backgroundAt = null }

    /** Locks now (turning the lock on asks right away the next time the app is opened, not while you're in it). */
    fun lockNow() { locked = true }
}
