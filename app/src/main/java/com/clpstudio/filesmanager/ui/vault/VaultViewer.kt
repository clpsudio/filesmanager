package com.clpstudio.filesmanager.ui.vault

import com.clpstudio.filesmanager.security.word
import com.clpstudio.filesmanager.security.label
import android.graphics.Bitmap
import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.security.CredentialType
import com.clpstudio.filesmanager.ui.components.ConfirmDialog
import com.clpstudio.filesmanager.ui.components.GlassTopBar
import com.clpstudio.filesmanager.ui.components.kindColor
import com.clpstudio.filesmanager.ui.components.kindIcon
import com.clpstudio.filesmanager.ui.glass.GlassScaffold
import com.clpstudio.filesmanager.ui.security.Choice
import com.clpstudio.filesmanager.ui.security.CredentialSetupForm
import com.clpstudio.filesmanager.ui.security.SecCard
import com.clpstudio.filesmanager.ui.security.SecHeader
import com.clpstudio.filesmanager.ui.security.VerifyDialog
import com.clpstudio.filesmanager.viewer.ViewerTopBar
import com.clpstudio.filesmanager.viewer.ZoomState
import com.clpstudio.filesmanager.viewer.zoomable
import com.clpstudio.filesmanager.vault.Vault
import com.clpstudio.filesmanager.vault.VaultAutoLock
import com.clpstudio.filesmanager.vault.VaultDataSource
import com.clpstudio.filesmanager.vault.VaultImages
import com.clpstudio.filesmanager.vault.VaultItem
import com.clpstudio.filesmanager.vault.VaultState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opens a vault file without a plaintext copy: photos decoded in memory, video and audio streamed through
 * the decrypting data source, PDF and text through the app's viewers ([onOpenDoc]). Swipe for the next file.
 */
@Composable
fun VaultViewerScreen(startId: String, onBack: () -> Unit, onOpenDoc: (VaultItem) -> Unit) {
    VaultScreenScope()
    val context = LocalContext.current
    val state by Vault.state.collectAsState()
    val items by Vault.items.collectAsState()
    LaunchedEffect(state) { if (state != VaultState.UNLOCKED) onBack() }
    if (state != VaultState.UNLOCKED || items.isEmpty()) return
    val start = remember { items.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(start) { items.size }
    val current = items.getOrNull(pager.currentPage) ?: return
    var confirmDelete by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, key = { items.getOrNull(it)?.id ?: it }, modifier = Modifier.fillMaxSize()) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            when (item.kind) {
                FileKind.IMAGE -> VaultImagePage(item)
                FileKind.VIDEO, FileKind.AUDIO -> VaultMediaPage(item, active = pager.currentPage == page)
                // Item 13: every other type opens in the app's own viewer too.
                else -> VaultDocPage(item, "Open") { onOpenDoc(item) }
            }
        }
        Box(Modifier.align(Alignment.TopCenter)) {
            ViewerTopBar(current.name, "${pager.currentPage + 1} of ${items.size} · in vault", onBack) {
                IconButton(onClick = { Vault.moveOut(context, listOf(current), null) }) { Icon(Icons.Filled.DriveFolderUpload, "Move out") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.DeleteForever, "Delete forever") }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${current.name} forever?",
            text = "It doesn't go to the Bin. This can't be undone.",
            confirm = "Delete forever",
            destructive = true,
            onConfirm = { confirmDelete = false; Vault.deleteForever(context, listOf(current)) },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun VaultImagePage(item: VaultItem) {
    val zoom = remember { ZoomState() }
    val bmp by produceState<Bitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { runCatching { Vault.store.openReader(item).use { VaultImages.decode(it, 4096) } }.getOrNull() }
    }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(bmp) { if (bmp != null) loaded = true }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(
                b.asImageBitmap(), item.name,
                // zoomable() already scales and moves the layer; a second graphicsLayer here zoomed it twice (F11 fix).
                Modifier.fillMaxSize().zoomable(zoom),
                contentScale = ContentScale.Fit,
            )
        } else {
            val tried by produceState(false, item.id) { kotlinx.coroutines.delay(4000); value = true }
            if (tried) Text("Can't show this photo here", color = Color.White) else CircularProgressIndicator()
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VaultMediaPage(item: VaultItem, active: Boolean) {
    val context = LocalContext.current
    if (!active) {
        VaultDocPage(item, null) {}
        return
    }
    val player = remember(item.id) {
        ExoPlayer.Builder(context)
            // Same audio rules as the other players (item 40): our FocusController below.
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
            val source = ProgressiveMediaSource.Factory(VaultDataSource.Factory { Vault.store.openReader(item) })
                .createMediaSource(MediaItem.fromUri("vault://item/${item.id}"))
            setMediaSource(source)
            // Vault audio starts at the remembered audio speed (item 52), natural pitch.
            if (item.kind == com.clpstudio.filesmanager.data.FileKind.AUDIO) playbackParameters = com.clpstudio.filesmanager.playback.AudioSpeed.params()
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val focus = com.clpstudio.filesmanager.playback.FocusController(
            context, player, duckEnabled = { runCatching { com.clpstudio.filesmanager.Graph.settings.value.duckForNotifications }.getOrDefault(true) }, movie = true,
        )
        onDispose { focus.release(); player.stop(); player.release() }
    }
    // Leaving the app (Home, recents, screen off): vault video/audio pauses (no background play from the vault).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { this.player = player; setShowNextButton(false); setShowPreviousButton(false) } },
            modifier = Modifier.fillMaxSize().padding(top = 72.dp),
        )
        if (item.kind == com.clpstudio.filesmanager.data.FileKind.AUDIO) {
            var speed by remember(item.id) { mutableStateOf(com.clpstudio.filesmanager.playback.AudioSpeed.current) }
            com.clpstudio.filesmanager.playback.AudioSpeedChip(
                speed,
                onSpeed = { s -> com.clpstudio.filesmanager.playback.AudioSpeed.set(s); player.playbackParameters = com.clpstudio.filesmanager.playback.AudioSpeed.params(s); speed = s },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp),
            )
        }
    }
}

@Composable
private fun VaultDocPage(item: VaultItem, action: String?, onAction: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(kindIcon(item.kind), null, Modifier.size(72.dp), tint = kindColor(item.kind))
        Spacer(Modifier.height(12.dp))
        Text(item.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(
            Formatter.formatShortFileSize(context, item.size) + " · " + DateFormat.getMediumDateFormat(context).format(java.util.Date(item.modified)),
            color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))
        if (action != null) {
            Button(onClick = onAction) { Text(action) }
        } else if (item.kind != FileKind.VIDEO && item.kind != FileKind.AUDIO) {
            Text(
                "This type can't be opened inside the vault. Move it out to open it with another app.",
                color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Vault settings: change PIN/password, auto-lock, where the key lives, delete the vault. */
@Composable
fun VaultSettingsScreen(onBack: () -> Unit, onSetUp: () -> Unit) {
    VaultScreenScope()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Vault.state.collectAsState()
    val count by Vault.count.collectAsState()
    var autoLock by remember { mutableStateOf(Vault.autoLock) }
    var verifyFor by remember { mutableStateOf<String?>(null) } // change | delete
    var oldSecret by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteSecretOk by remember { mutableStateOf(false) }
    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()
    val type = Vault.store.credentialType ?: CredentialType.PIN
    val check: suspend (String) -> String? = { s ->
        if (Vault.checkSecret(s)) null else "Wrong ${type.word()}"
    }

    GlassScaffold(topBar = { GlassTopBar("Vault settings", onBack = onBack) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state == VaultState.NOT_SET_UP) {
                item {
                    SecCard {
                        Text("The vault isn't set up", style = MaterialTheme.typography.titleMedium)
                        Text("It's off until you create it with its own PIN or password.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onSetUp) { Text("Set up vault") }
                    }
                }
                return@LazyColumn
            }
            item {
                SecCard {
                    Text("Protection", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "$count file${if (count == 1) "" else "s"} · AES-256-GCM · key held by ${Vault.hardwareKind()} + your ${type.word()}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("No recovery: a forgotten PIN/password, uninstalling Files or clearing its data loses the vault for good.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            item { SecHeader("PIN or password") }
            item {
                SecCard {
                    if (oldSecret == null) {
                        Text("Change the vault's ${type.word()}. Files inside don't need re-encrypting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { verifyFor = "change" }) { Text("Change PIN or password") }
                    } else {
                        CredentialSetupForm(
                            confirmLabel = "Save",
                            busy = busy,
                            initialType = type,
                            onSubmit = { t, s ->
                                busy = true
                                scope.launch {
                                    val ok = Vault.changeSecret(oldSecret!!, t, s)
                                    busy = false
                                    oldSecret = null
                                    toast(if (ok) "Saved" else "Couldn't change it")
                                }
                            },
                        )
                    }
                }
            }
            item { SecHeader("Fingerprint") }
            item {
                // Item 5: fingerprint opens the vault (an extra way in; the PIN / password / pattern still works).
                val activity = context as? android.app.Activity ?: (context as? android.content.ContextWrapper)?.baseContext as? android.app.Activity
                val bioStatus = remember { com.clpstudio.filesmanager.vault.VaultBiometric.status(context) }
                val canBio = com.clpstudio.filesmanager.security.Biometrics.usable(bioStatus)
                var bioOn by remember { mutableStateOf(com.clpstudio.filesmanager.vault.VaultBiometric.enabled()) }
                SecCard {
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Unlock with fingerprint", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (canBio) "Open the vault with your fingerprint. Your ${type.word()} still works. Adding a new fingerprint to the phone turns this off."
                                else com.clpstudio.filesmanager.security.Biometrics.statusText(bioStatus, com.clpstudio.filesmanager.security.Biometrics.Purpose.VAULT_KEY),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = bioOn, enabled = canBio && state == VaultState.UNLOCKED && activity != null,
                            onCheckedChange = { on ->
                                if (!on) { com.clpstudio.filesmanager.vault.VaultBiometric.disable(); bioOn = false; toast("Fingerprint unlock off") }
                                else activity?.let { a -> com.clpstudio.filesmanager.vault.VaultBiometric.enable(a) { err -> bioOn = err == null; toast(err ?: "Fingerprint unlock on") } }
                            },
                        )
                    }
                }
            }
            item { SecHeader("Auto-lock") }
            item {
                SecCard {
                    VaultAutoLock.entries.forEach { a ->
                        Choice(a.label, autoLock == a) { autoLock = a; Vault.autoLock = a }
                    }
                    Text("Locking also stops anything playing from the vault.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SecHeader("Wrong attempts allowed") }
            item {
                SecCard {
                    val allowed = com.clpstudio.filesmanager.Graph.settings.collectAsState().value.wrongAttemptsAllowed
                    com.clpstudio.filesmanager.security.AttemptLimiter.ALLOWED_CHOICES.forEach { n ->
                        Choice("$n tries", allowed == n) { com.clpstudio.filesmanager.Graph.appScope.launch { com.clpstudio.filesmanager.Graph.settingsRepo.setWrongAttemptsAllowed(n) } }
                    }
                    Text("After that, the vault and the app lock wait before the next try: 30 s, then 1, 5, 15 and 30 min. A right PIN resets it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SecHeader("Delete vault") }
            item {
                SecCard {
                    Text("Deletes the vault and every file in it, for good. Move out what you want to keep first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { verifyFor = "delete" }) { Text("Delete vault", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    verifyFor?.let { what ->
        VerifyDialog(
            title = if (what == "delete") "Delete the vault?" else "Change PIN or password",
            type = type,
            confirm = "Next",
            check = check,
            onOk = { s -> verifyFor = null; if (what == "change") oldSecret = s else confirmDelete = true },
            onDismiss = { verifyFor = null },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete the vault and $count file${if (count == 1) "" else "s"}?",
            text = "They're deleted for good. They don't go to the Bin and can't be recovered.",
            confirm = "Delete forever",
            destructive = true,
            onConfirm = { confirmDelete = false; scope.launch { Vault.deleteVault(); toast("Vault deleted") } },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** "Move to vault" while the vault is locked (asks for its PIN) or not set up yet (says where to set it up). */
@Composable
fun VaultUnlockDialog(count: Int, onUnlocked: () -> Unit, onDismiss: () -> Unit) {
    val state by Vault.state.collectAsState()
    if (state == VaultState.NOT_SET_UP) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Set up the vault first") },
            text = { Text("Open Home → Vault to create it with its own PIN or password, then move files in.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("OK") } },
        )
        return
    }
    val type = remember { Vault.store.credentialType ?: CredentialType.PIN }
    VerifyDialog(
        title = "Unlock the vault",
        text = "To move $count item${if (count == 1) "" else "s"} in, enter the vault's ${type.word()}.",
        type = type,
        confirm = "Unlock",
        check = { s ->
            when (val r = Vault.unlock(s)) {
                Vault.UnlockResult.Ok -> null
                is Vault.UnlockResult.Wrong -> r.message
                is Vault.UnlockResult.Lost -> r.message
            }
        },
        onOk = { onUnlocked() },
        onDismiss = onDismiss,
    )
}
