package com.clpstudio.filesmanager.ui.security

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
            listOf(CredentialType.PIN to "PIN", CredentialType.PASSWORD to "Password").forEachIndexed { i, (t, label) ->
                SegmentedButton(selected = type == t, onClick = { if (type != t) { type = t; first = ""; second = ""; error = null } }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
            }
        }
        Text(
            if (type == CredentialType.PIN) "${CredentialRules.PIN_MIN}–${CredentialRules.PIN_MAX} digits" else "${CredentialRules.PASSWORD_MIN}–${CredentialRules.PASSWORD_MAX} characters",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SecretField(first, { first = it; error = null }, type, if (type == CredentialType.PIN) "New PIN" else "New password",
            Modifier.focusRequester(firstFocus), imeAction = ImeAction.Next, onIme = { runCatching { secondFocus.requestFocus() } }, enabled = !busy)
        SecretField(second, { second = it; error = null }, type, if (type == CredentialType.PIN) "Repeat PIN" else "Repeat password",
            Modifier.focusRequester(secondFocus), error = error ?: serverError, onIme = { submit() }, enabled = !busy)
        Button(onClick = { submit() }, enabled = !busy && first.isNotEmpty() && second.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(confirmLabel)
        }
    }
}

/** Live countdown text for the wrong-try wait (null when another try is allowed now). */
@Composable
fun rememberWaitMessage(lockedUntil: Long, failures: Int): String? {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lockedUntil) {
        while (lockedUntil > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(500)
        }
        now = System.currentTimeMillis()
    }
    val s = com.clpstudio.filesmanager.security.AttemptState(failures, lockedUntil)
    return if (AttemptLimiter.remaining(s, now) > 0) AttemptLimiter.message(s, now) else null
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
    waitUntil: () -> Pair<Long, Int>,
    extra: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var waitState by remember { mutableStateOf(waitUntil()) }
    val waitMsg = rememberWaitMessage(waitState.first, waitState.second)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(200); runCatching { focus.requestFocus() } }
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
                waitState = waitUntil()
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
            value, { value = it; error = null }, type,
            if (type == CredentialType.PIN) "PIN" else "Password",
            Modifier.focusRequester(focus),
            error = waitMsg ?: error,
            onIme = { submit() },
            enabled = !busy && waitMsg == null,
        )
        Button(onClick = { submit() }, enabled = !busy && value.isNotEmpty() && waitMsg == null, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Unlock")
        }
        extra()
    }
}

/** The app lock screen, drawn over any activity of the app while it's locked. */
@Composable
fun AppLockScreen(activity: Activity) {
    val context = LocalContext.current
    val bio = remember { AppLock.biometric && AppLock.biometricAvailable(context) }
    LaunchedEffect(Unit) { if (bio) AppLock.promptBiometric(activity) {} }
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
            UnlockForm(
                type = AppLock.type,
                title = "Files is locked",
                subtitle = if (AppLock.type == CredentialType.PIN) "Enter your PIN" else "Enter your password",
                check = { s -> when (val r = AppLock.check(s)) { AppLock.Check.Ok -> null; is AppLock.Check.Wrong -> r.message } },
                onUnlocked = { AppLock.unlocked() },
                waitUntil = { AppLock.attempts.let { it.lockedUntil to it.failures } },
                extra = {
                    if (bio) {
                        OutlinedButton(onClick = { AppLock.promptBiometric(activity) {} }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Fingerprint, null)
                            Spacer(Modifier.size(8.dp))
                            Text("Use fingerprint")
                        }
                    }
                },
            )
        }
    }
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
        if (n == 0) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
