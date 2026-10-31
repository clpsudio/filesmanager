package com.clpstudio.filesmanager.ui.security

import com.clpstudio.filesmanager.security.Biometrics
import androidx.compose.runtime.key
import com.clpstudio.filesmanager.security.word
import com.clpstudio.filesmanager.security.label
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Timer
import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.security.AppLock
import com.clpstudio.filesmanager.security.AttemptLimiter
import com.clpstudio.filesmanager.security.CredentialRules
import com.clpstudio.filesmanager.security.CredentialType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The one PIN / password field used everywhere: plain, masked, the phone's number keyboard for a PIN,
 * a show/hide eye. A PIN field takes digits only and stops at 10.
 */
@Composable
fun SecretField(
    value: String,
    onChange: (String) -> Unit,
    type: CredentialType,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onIme: () -> Unit = {},
    enabled: Boolean = true,
) {
    if (type == CredentialType.PATTERN) {
        // Item 4: a 3×3 pattern pad instead of a text field; the value arrives when the finger lifts.
        Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            PatternPad(onDone = { onChange(CredentialRules.filterInput(type, it)); onIme() }, error = error != null, enabled = enabled)
            if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            else if (value.isNotEmpty()) Text("Pattern drawn (${value.length} dots)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(CredentialRules.filterInput(type, it)) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (type == CredentialType.PIN) KeyboardType.NumberPassword else KeyboardType.Password,
            imeAction = imeAction,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onDone = { onIme() }, onNext = { onIme() }, onGo = { onIme() }),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (show) "Hide" else "Show")
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/** Choose PIN or password, type it twice. [onSubmit] gets the type and the value once both match the rules. */
@Composable
fun CredentialSetupForm(
    confirmLabel: String,
    busy: Boolean,
    onSubmit: (CredentialType, String) -> Unit,
    initialType: CredentialType = CredentialType.PIN,
    serverError: String? = null,
) {
    var type by remember { mutableStateOf(initialType) }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val secondFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(type) { runCatching { firstFocus.requestFocus() } }
    fun submit() {
        val e = CredentialRules.confirmError(type, first, second)
        error = e
        if (e == null) onSubmit(type, first)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val choices = listOf(CredentialType.PIN to "PIN", CredentialType.PASSWORD to "Password", CredentialType.PATTERN to "Pattern")
            choices.forEachIndexed { i, (t, label) ->
                SegmentedButton(selected = type == t, onClick = { if (type != t) { type = t; first = ""; second = ""; error = null } }, shape = SegmentedButtonDefaults.itemShape(i, choices.size)) { Text(label) }
            }
        }
        Text(
            when (type) {
                CredentialType.PIN -> "${CredentialRules.PIN_MIN}–${CredentialRules.PIN_MAX} digits"
                CredentialType.PASSWORD -> "${CredentialRules.PASSWORD_MIN}–${CredentialRules.PASSWORD_MAX} characters"
                CredentialType.PATTERN -> "Connect at least ${CredentialRules.PATTERN_MIN} dots, then draw it again"
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (type == CredentialType.PATTERN) {
            // One pad: draw, then draw again to confirm (submits by itself when both match).
            val firstOk = first.isNotEmpty() && CredentialRules.error(type, first) == null
            key(firstOk) {
                SecretField(
                    if (firstOk) second else first,
                    { v ->
                        if (!firstOk) {
                            first = v
                            error = CredentialRules.error(type, v)
                            if (error != null) first = ""
                        } else {
                            second = v
                            submit()
                            if (error != null) { first = ""; second = "" }
                        }
                    },
                    type, if (!firstOk) "Draw a new pattern" else "Draw it again to confirm",
                    error = error ?: serverError, enabled = !busy,
                )
            }
            if (firstOk) TextButton(onClick = { first = ""; second = ""; error = null }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Start over") }
            if (busy) CircularProgressIndicator(Modifier.size(18.dp).align(Alignment.CenterHorizontally), strokeWidth = 2.dp)
            return@Column
        }
        SecretField(first, { first = it; error = null }, type, "New " + type.word(),
            Modifier.focusRequester(firstFocus), imeAction = ImeAction.Next, onIme = { runCatching { secondFocus.requestFocus() } }, enabled = !busy)
        SecretField(second, { second = it; error = null }, type, "Repeat " + type.word(),
            Modifier.focusRequester(secondFocus), error = error ?: serverError, onIme = { submit() }, enabled = !busy)
        Button(onClick = { submit() }, enabled = !busy && first.isNotEmpty() && second.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(confirmLabel)
        }
    }
}

/** Live countdown for a wrong-try lockout: "Too many wrong tries. Try again in 0:30" (null when a try is allowed). */
@Composable
fun rememberWaitMessage(remainingMs: () -> Long, key: Any?): String? {
    var left by remember { mutableLongStateOf(remainingMs()) }
    LaunchedEffect(key) {
        while (true) {
            left = remainingMs()
            if (left <= 0L) break
            delay(250)
        }
    }
    return if (left > 0L) AttemptLimiter.lockedMessage(left) else null
}

/**
 * A PIN/password prompt (lock screen and vault unlock). [check] returns null when right, else the message.
 */
@Composable
fun UnlockForm(
    type: CredentialType,
    title: String,
    subtitle: String?,
    check: suspend (String) -> String?,
    onUnlocked: () -> Unit,
    /** Time left in a wrong-try lockout (0 = can try now). */
    remainingMs: () -> Long,
    /** Below the Unlock button; gets whether a lockout is running (fingerprint is off then). */
    extra: @Composable (lockedOut: Boolean) -> Unit = {},
    /** Open the keyboard by itself; off when a fingerprint prompt opens first (the keyboard cancelled it on many phones). */
    autoFocus: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tries by remember { mutableIntStateOf(0) }
    val waitMsg = rememberWaitMessage(remainingMs, tries)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) { delay(200); runCatching { focus.requestFocus() } } }
    fun submit() {
        if (busy || value.isEmpty() || waitMsg != null) return
        busy = true
        scope.launch {
            val r = check(value)
            busy = false
            if (r == null) {
                value = ""
                onUnlocked()
            } else {
                error = r
                value = ""
                tries++
            }
        }
    }
    Column(
        Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Lock, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        SecretField(
            value, { value = it; error = null; if (type == CredentialType.PATTERN && it.isNotEmpty()) submit() }, type,
            type.label(),
            Modifier.focusRequester(focus),
            error = if (waitMsg != null) null else error,
            onIme = { submit() },
            enabled = !busy && waitMsg == null,
        )
        // Lockout: a clear live countdown (the disabled field would grey it out).
        if (waitMsg != null) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Timer, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(10.dp))
                Column {
                    Text("Too many wrong tries", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(waitMsg.substringAfter("tries. "), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("lockoutCountdown"))
                }
            }
        }
        if (type != CredentialType.PATTERN) {
            Button(onClick = { submit() }, enabled = !busy && value.isNotEmpty() && waitMsg == null, modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Unlock")
            }
        } else if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        extra(waitMsg != null)
    }
}

/** The app lock screen, drawn over any activity of the app while it's locked. */
@Composable
fun AppLockScreen(activity: Activity) {
    val context = LocalContext.current
    val bio = remember { AppLock.biometric && AppLock.biometricAvailable(context) }
    var bioMsg by remember { mutableStateOf<String?>(null) }
    var signal by remember { mutableStateOf<android.os.CancellationSignal?>(null) }
    fun ask() { signal = AppLock.promptBiometric(activity, onMessage = { bioMsg = it }) }
    LaunchedEffect(Unit) {
        if (bio && AppLock.remainingMs() <= 0L) {
            // Wait for the window to have focus first: asked earlier, many phones cancel the prompt at once.
            Biometrics.awaitFocus(activity)
            ask()
        }
    }
    // Typed the PIN while the prompt was up, or the lock closed: put the prompt away.
    DisposableEffect(Unit) { onDispose { runCatching { signal?.cancel() } } }
    Surface(
        Modifier.fillMaxSize()
            // swallow every touch so nothing underneath can be used
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(40.dp))
            AppLockForm(bio, bioMsg, onFingerprint = { ask() })
        }
    }
}

/** The lock form itself (also rendered in tests): PIN / password / pattern, plus "Use fingerprint" and its message. */
@Composable
fun AppLockForm(bio: Boolean, bioMsg: String?, onFingerprint: () -> Unit, type: CredentialType = AppLock.type, remainingMs: () -> Long = { AppLock.remainingMs() }) {
    UnlockForm(
        type = type,
        title = "Files is locked",
        subtitle = if (type == CredentialType.PATTERN) "Draw your pattern" else "Enter your " + type.word(),
        check = { s -> when (val r = AppLock.check(s)) { AppLock.Check.Ok -> null; is AppLock.Check.Wrong -> r.message } },
        onUnlocked = { AppLock.unlocked() },
        remainingMs = remainingMs,
        autoFocus = !bio,
        extra = { lockedOut ->
            // No fingerprint during a lockout.
            if (bio && !lockedOut) {
                OutlinedButton(onClick = onFingerprint, modifier = Modifier.fillMaxWidth().testTag("useFingerprint")) {
                    Icon(Icons.Filled.Fingerprint, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Use fingerprint")
                }
            }
            if (bio && bioMsg != null) Text(bioMsg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        },
    )
}

/** FLAG_SECURE while a vault screen is showing (no screenshots, blank in Recents); counted so screens can nest. */
object SecureWindow {
    private val holds = java.util.WeakHashMap<Activity, Int>()
    fun isHeld(activity: Activity) = (holds[activity] ?: 0) > 0
    fun acquire(activity: Activity) {
        holds[activity] = (holds[activity] ?: 0) + 1
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    fun release(activity: Activity) {
        val n = ((holds[activity] ?: 1) - 1).coerceAtLeast(0)
        holds[activity] = n
        // Item 39: "Block screenshots & screen recording" keeps it on after a vault screen closes.
        if (n == 0 && !com.clpstudio.filesmanager.ui.PrivacyGuard.blockAll) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

@Composable
fun SecureScreen() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        SecureWindow.acquire(activity)
        onDispose { SecureWindow.release(activity) }
    }
}

fun android.content.Context.findActivity(): Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
