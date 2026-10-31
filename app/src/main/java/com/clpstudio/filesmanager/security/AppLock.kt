package com.clpstudio.filesmanager.security

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.vault.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.WeakHashMap

/**
 * Settings → App lock: a PIN (4–10 digits) or password (8–72 characters), optional fingerprint.
 * Separate from the vault's own PIN. Only a PBKDF2 verifier is stored, never the PIN itself.
 * Asks on every cold start and after the app has been in the background for the "Ask after" time.
 */
object AppLock {
    const val ITERATIONS = 120_000
    private var prefs: SharedPreferences? = null
    private val timer = LockTimer()
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    private val _enabledFlow = MutableStateFlow(false)
    val enabledFlow: StateFlow<Boolean> = _enabledFlow.asStateFlow()

    /** Pure verifier helpers (tested on the JVM). */
    object Verifier {
        fun make(secret: String, iterations: Int = ITERATIONS): Triple<String, String, Int> {
            val salt = Kdf.salt(16)
            val hash = Kdf.derive(secret, salt, iterations)
            return Triple(Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(hash), iterations)
        }
        fun check(secret: String, salt: String, hash: String, iterations: Int): Boolean {
            val expect = runCatching { Base64.getDecoder().decode(hash) }.getOrNull() ?: return false
            val s = runCatching { Base64.getDecoder().decode(salt) }.getOrNull() ?: return false
            return Kdf.equalsConstantTime(expect, Kdf.derive(secret, s, iterations))
        }
    }

    fun init(app: Application) {
        if (prefs != null) return
        prefs = app.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
        _enabledFlow.value = enabled
        timer.onAppStart(enabled)
        publish()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (SystemClock.elapsedRealtime() < externalUntil) return // our own file picker is open
                timer.onBackground(enabled, askAfter, SystemClock.elapsedRealtime(), promptOpen = Biometrics.promptOpen)
                Vault.onAppBackground()
                publish()
            }
            override fun onStart(owner: LifecycleOwner) {
                val until = externalUntil
                if (until != 0L) {
                    externalUntil = 0L
                    // Came back from the picker we opened; took too long = treat it as leaving the app.
                    if (SystemClock.elapsedRealtime() > until) {
                        if (enabled) timer.lockNow()
                        Vault.lock()
                    }
                    publish()
                    return
                }
                timer.onForeground(enabled, askAfter, SystemClock.elapsedRealtime())
                Vault.onAppForeground()
                publish()
            }
        })
        app.registerActivityLifecycleCallbacks(AppLockGuard)
    }

    @Volatile private var externalUntil = 0L

    /**
     * The app is about to open the system file picker for itself ("Add files" in the vault): leaving for it
     * doesn't lock the app or the vault, as long as the user is back within [ms].
     */
    fun allowExternalScreen(ms: Long = 120_000L) {
        externalUntil = SystemClock.elapsedRealtime() + ms
    }

    private fun p(): SharedPreferences = prefs ?: throw IllegalStateException("AppLock not initialised")

    private fun publish() {
        val was = _locked.value
        _locked.value = timer.locked
        AppLockGuard.syncBack()
        if (was != timer.locked) runCatching { com.clpstudio.filesmanager.picker.FilesDocumentsProvider.notifyRootsChanged(Graph.app) }
    }

    val enabled: Boolean get() = prefs?.getBoolean("enabled", false) == true && prefs?.getString("hash", null) != null
    val type: CredentialType get() = prefs?.getString("type", null)?.let { runCatching { CredentialType.valueOf(it) }.getOrNull() } ?: CredentialType.PIN
    var askAfter: AskAfter
        get() = prefs?.getString("ask_after", null)?.let { runCatching { AskAfter.valueOf(it) }.getOrNull() } ?: AskAfter.IMMEDIATELY
        set(v) { p().edit().putString("ask_after", v.name).apply() }
    var biometric: Boolean
        get() = prefs?.getBoolean("biometric", false) == true
        set(v) { p().edit().putBoolean("biometric", v).apply() }

    private val attemptStore = AttemptStore { prefs }
    private fun clock() = LockClock.now(runCatching { Graph.app }.getOrNull())
    var attempts: AttemptState
        get() = attemptStore.load(clock())
        private set(v) { attemptStore.save(v) }

    /** Time left in a wrong-try lockout (0 = can try now). Fingerprint is off during it too. */
    fun remainingMs(): Long = attemptStore.remainingNow(clock())

    /** Locked right now (content of the app should not be shown, the picker shows no roots). */
    val isLocked: Boolean get() = enabled && _locked.value

    /** Turns the lock on, or changes the PIN/password. You're inside the app, so it stays open now. */
    suspend fun set(type: CredentialType, secret: String) = withContext(Dispatchers.Default) {
        require(CredentialRules.error(type, secret) == null)
        val (salt, hash, iters) = Verifier.make(secret)
        p().edit().putBoolean("enabled", true).putString("type", type.name).putString("salt", salt)
            .putString("hash", hash).putInt("iterations", iters).apply()
        attempts = AttemptState()
        _enabledFlow.value = true
    }

    fun disable() {
        p().edit().putBoolean("enabled", false).remove("salt").remove("hash").remove("biometric").apply()
        attempts = AttemptState()
        timer.onDisabled()
        _enabledFlow.value = false
        publish()
    }

    sealed interface Check {
        data object Ok : Check
        data class Wrong(val message: String) : Check
    }

    /** Checks the PIN/password with the wrong-try limit (Settings → Wrong attempts allowed, then escalating lockouts). */
    suspend fun check(secret: String): Check = withContext(Dispatchers.Default) {
        val now = clock()
        val allowed = AttemptLimiter.allowedNow()
        val a = attempts
        if (AttemptLimiter.remaining(a, now) > 0) return@withContext Check.Wrong(AttemptLimiter.message(a, now, allowed)!!)
        val pr = p()
        val ok = Verifier.check(secret, pr.getString("salt", "")!!, pr.getString("hash", "")!!, pr.getInt("iterations", ITERATIONS))
        if (ok) {
            attempts = AttemptLimiter.onSuccess()
            Check.Ok
        } else {
            val n = AttemptLimiter.onFailure(a, now, allowed)
            attempts = n
            Check.Wrong(AttemptLimiter.message(n, now, allowed) ?: "Wrong")
        }
    }

    fun unlocked() {
        attempts = AttemptLimiter.onSuccess()
        timer.onUnlocked(SystemClock.elapsedRealtime())
        publish()
    }

    // --- fingerprint / face (platform BiometricPrompt, Android 10+; see Biometrics for the 1.0.21 causes) ---

    fun biometricStatus(context: Context): Biometrics.Status = Biometrics.status(context, Biometrics.Purpose.APP_LOCK)

    fun biometricAvailable(context: Context): Boolean = Biometrics.usable(biometricStatus(context))

    /** Shows the prompt; [onMessage] gets what to show under the lock (null clears). Returns a cancel handle. */
    fun promptBiometric(activity: Activity, onMessage: (String?) -> Unit = {}, onSuccess: () -> Unit = {}): android.os.CancellationSignal? {
        // No fingerprint during a wrong-try lockout (item 53) — said, not silent.
        val wait = remainingMs()
        if (wait > 0) { onMessage(AttemptLimiter.lockedMessage(wait)); return null }
        return Biometrics.prompt(
            activity, "Unlock Files", "Use ${type.word()}", Biometrics.Purpose.APP_LOCK,
            onSuccess = {
                if (remainingMs() > 0) return@prompt
                unlocked()
                onSuccess()
            },
            onMessage = onMessage,
        )
    }
}

/**
 * Puts the lock screen over EVERY activity of the app (main, viewers, the picker other apps open),
 * as a full-screen layer added on top of the activity's own content. Back while locked leaves the app.
 */
internal object AppLockGuard : Application.ActivityLifecycleCallbacks {
    private val installed = WeakHashMap<Activity, Boolean>()

    override fun onActivityStarted(activity: Activity) {
        if (activity !is ComponentActivity || installed[activity] == true) return
        // The camera's lock-screen review shows only the photo just taken, over the lock screen: no PIN on top.
        if (activity.intent?.action == com.clpstudio.filesmanager.viewer.CameraReview.REVIEW_SECURE) return
        installed[activity] = true
        val view = ComposeView(activity).apply {
            setContent {
                val settings by Graph.settings.collectAsState()
                val locked by AppLock.locked.collectAsState()
                val enabled by AppLock.enabledFlow.collectAsState()
                if (locked && enabled) {
                    FilesTheme(settings.theme, settings.liquidGlass) {
                        com.clpstudio.filesmanager.ui.security.AppLockScreen(activity)
                    }
                }
            }
        }
        activity.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val back = object : OnBackPressedCallback(AppLock.isLocked) {
            override fun handleOnBackPressed() { activity.moveTaskToBack(true) }
        }
        activity.onBackPressedDispatcher.addCallback(activity, back)
        lockWatchers[activity] = back
    }

    /** Back callbacks per activity, switched when the lock state changes. */
    private val lockWatchers = WeakHashMap<Activity, OnBackPressedCallback>()

    fun syncBack() { lockWatchers.values.forEach { it.isEnabled = AppLock.isLocked } }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) { lockWatchers[activity]?.isEnabled = AppLock.isLocked }
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) { installed.remove(activity); lockWatchers.remove(activity) }
}
