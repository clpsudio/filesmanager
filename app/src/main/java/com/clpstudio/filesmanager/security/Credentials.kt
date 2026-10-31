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
 * Wrong-attempt LIMIT (item 53, vault and app lock): [allowed] free tries (Settings → Wrong attempts allowed:
 * 3 / 5 / 10, default 5), then a lockout with a live countdown that escalates 30 s → 1 min → 5 min → 15 min →
 * 30 min for each further wrong try; a right one resets everything. Timed on the monotonic clock
 * (SystemClock.elapsedRealtime), so changing the phone's date or time doesn't shorten it; kept on disk, so
 * closing or killing the app doesn't reset it; after a reboot the remaining time is kept (the last saved
 * remaining, re-anchored to the new boot).
 */
data class AttemptState(
    val failures: Int = 0,
    /** Length of the current lockout, counted from [lockStart] (0 = none). */
    val lockMs: Long = 0L,
    /** elapsedRealtime when the lockout started (or was last re-anchored). */
    val lockStart: Long = 0L,
    /** The boot [lockStart] belongs to. */
    val bootId: Int = 0,
)

/** Now, on the monotonic clock, and which boot it is. */
data class LockClock(val elapsed: Long, val bootId: Int) {
    companion object {
        fun now(context: android.content.Context?): LockClock = LockClock(
            android.os.SystemClock.elapsedRealtime(),
            context?.let { runCatching { android.provider.Settings.Global.getInt(it.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0) }.getOrDefault(0) } ?: 0,
        )
    }
}

object AttemptLimiter {
    const val DEFAULT_ALLOWED = 5
    val ALLOWED_CHOICES = listOf(3, 5, 10)
    /** Lockout lengths: first, second, … (the last repeats). */
    val WAITS = listOf(30_000L, 60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L)
    val MAX_WAIT_MS = WAITS.last()

    fun allowed(n: Int?): Int = if (n != null && n in ALLOWED_CHOICES) n else DEFAULT_ALLOWED

    /** The setting, live (default 5). */
    fun allowedNow(): Int = allowed(runCatching { com.clpstudio.filesmanager.Graph.settings.value.wrongAttemptsAllowed }.getOrNull())

    fun waitAfter(failures: Int, allowed: Int = DEFAULT_ALLOWED): Long {
        if (failures < allowed) return 0L
        return WAITS[(failures - allowed).coerceAtMost(WAITS.lastIndex)]
    }

    fun onFailure(s: AttemptState, clock: LockClock, allowed: Int = DEFAULT_ALLOWED): AttemptState {
        val f = s.failures + 1
        val wait = waitAfter(f, allowed)
        return if (wait > 0) AttemptState(f, wait, clock.elapsed, clock.bootId) else AttemptState(f)
    }

    fun onSuccess(): AttemptState = AttemptState()

    /** Milliseconds until another try is allowed (0 = now). After a reboot: the remaining saved last. */
    fun remaining(s: AttemptState, clock: LockClock): Long = when {
        s.lockMs <= 0L -> 0L
        s.bootId == clock.bootId && clock.elapsed >= s.lockStart -> (s.lockMs - (clock.elapsed - s.lockStart)).coerceAtLeast(0L)
        else -> s.lockMs
    }

    /** What to save now so a reboot keeps the time left: the remaining, anchored to this moment and boot. */
    fun checkpoint(s: AttemptState, clock: LockClock): AttemptState {
        if (s.lockMs <= 0L) return s
        val r = remaining(s, clock)
        return if (r <= 0L) s.copy(lockMs = 0L, lockStart = 0L) else AttemptState(s.failures, r, clock.elapsed, clock.bootId)
    }

    /** Tries left before the first lockout (null once lockouts have started). */
    fun triesLeft(s: AttemptState, allowed: Int = DEFAULT_ALLOWED): Int? = (allowed - s.failures).takeIf { it > 0 }

    /** "0:30", "4:59", "30:00". */
    fun clockText(ms: Long): String {
        val sec = (ms.coerceAtLeast(0L) + 999) / 1000
        return "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
    }

    fun lockedMessage(remainingMs: Long): String = "Too many wrong tries. Try again in ${clockText(remainingMs)}"

    fun message(s: AttemptState, clock: LockClock, allowed: Int = DEFAULT_ALLOWED): String? {
        val r = remaining(s, clock)
        if (r > 0) return lockedMessage(r)
        if (s.failures == 0) return null
        val left = triesLeft(s, allowed)
        return if (left != null) "Wrong. $left ${if (left == 1) "try" else "tries"} left" else "Wrong"
    }
}

/** Where a lock keeps its wrong tries (its own SharedPreferences). Reads the old wall-clock key once. */
class AttemptStore(private val prefs: () -> android.content.SharedPreferences?) {
    fun load(clock: LockClock): AttemptState {
        val p = prefs() ?: return AttemptState()
        val failures = p.getInt("failures", 0)
        val legacy = p.getLong("locked_until", 0L)
        if (legacy > 0L) {
            val left = (legacy - System.currentTimeMillis()).coerceIn(0L, AttemptLimiter.MAX_WAIT_MS)
            val s = if (left > 0) AttemptState(failures, left, clock.elapsed, clock.bootId) else AttemptState(failures)
            save(s)
            return s
        }
        return AttemptState(failures, p.getLong("lock_ms", 0L), p.getLong("lock_start", 0L), p.getInt("boot_id", 0))
    }

    fun save(s: AttemptState) {
        prefs()?.edit()?.putInt("failures", s.failures)?.putLong("lock_ms", s.lockMs)?.putLong("lock_start", s.lockStart)
            ?.putInt("boot_id", s.bootId)?.remove("locked_until")?.apply()
    }

    /** Remaining lockout now (re-anchored and saved, so a reboot keeps it). */
    fun remainingNow(clock: LockClock): Long {
        val s = load(clock)
        val c = AttemptLimiter.checkpoint(s, clock)
        if (c != s) save(c)
        return AttemptLimiter.remaining(c, clock)
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
