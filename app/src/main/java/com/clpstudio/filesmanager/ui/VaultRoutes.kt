package com.clpstudio.filesmanager.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.ui.security.AppLockSettingsScreen
import com.clpstudio.filesmanager.ui.vault.VaultScreen
import com.clpstudio.filesmanager.ui.vault.VaultScreenScope
import com.clpstudio.filesmanager.ui.vault.VaultSettingsScreen
import com.clpstudio.filesmanager.ui.vault.VaultViewerScreen
import com.clpstudio.filesmanager.vault.Vault
import com.clpstudio.filesmanager.vault.VaultItem
import com.clpstudio.filesmanager.vault.VaultProvider
import com.clpstudio.filesmanager.vault.VaultState
import kotlinx.coroutines.launch

/** Vault and app-lock screens (kept out of AppRoot so the shared file only gains two lines). */
fun NavGraphBuilder.vaultRoutes(nav: NavHostController, back: () -> Unit) {
    val openDoc: (VaultItem) -> Unit = { item -> openVaultItem(nav, item) }
    composable(Routes.VAULT) {
        VaultScreen(
            onBack = back,
            onOpen = { item ->
                // Photos, videos and music swipe in the vault's own viewer; every other type opens its viewer directly.
                if (item.kind == FileKind.IMAGE || item.kind == FileKind.VIDEO || item.kind == FileKind.AUDIO) nav.navigate(Routes.vaultView(item.id)) else openDoc(item)
            },
            onSettings = { nav.navigate(Routes.VAULT_SETTINGS) },
        )
    }
    composable("vault_view?id={id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
        VaultViewerScreen(e.arguments?.getString("id").orEmpty(), back, openDoc)
    }
    composable(Routes.VAULT_SETTINGS) {
        VaultSettingsScreen(back, onSetUp = { nav.navigate(Routes.VAULT) { popUpTo(Routes.VAULT_SETTINGS) { inclusive = true } } })
    }
    composable(Routes.APP_LOCK) { AppLockSettingsScreen(back) }
}

/** A PDF / text opened from the vault: FLAG_SECURE, counts as "in the vault", closes when the vault locks. */
@Composable
fun VaultDocGuard(uri: Uri, back: () -> Unit) {
    if (!VaultProvider.isVault(uri)) return
    VaultScreenScope()
    val state by Vault.state.collectAsState()
    LaunchedEffect(state) { if (state != VaultState.UNLOCKED) back() }
}


/** Item 13: which viewer a vault file opens in (pure, unit tested). */
object VaultOpen {
    enum class Target { PDF, TEXT, OFFICE, EPUB, ARCHIVE, APK, FONT, PAGER, HEX }

    fun target(kind: FileKind, name: String): Target {
        val ext = com.clpstudio.filesmanager.data.FileTypes.extension(name)
        return when {
            kind == FileKind.PDF -> Target.PDF
            kind == FileKind.TEXT -> Target.TEXT
            ext == "epub" -> Target.EPUB
            kind == FileKind.DOCUMENT && com.clpstudio.filesmanager.viewer.hasOfficeViewer(name) -> Target.OFFICE
            kind == FileKind.APK -> Target.APK
            kind == FileKind.ARCHIVE && com.clpstudio.filesmanager.data.Archives.canBrowse(name) -> Target.ARCHIVE
            kind == FileKind.FONT -> Target.FONT
            // Photoshop / Illustrator / CorelDRAW / AutoCAD and other pictures the pager draws.
            com.clpstudio.filesmanager.data.DesignPreview.isDesign(name) || kind == FileKind.IMAGE -> Target.PAGER
            // Anything else: its bytes in the hex viewer (read straight from the encrypted file).
            else -> Target.HEX
        }
    }

    /** Needs a decrypted temporary file (the others read the encrypted file directly). */
    fun needsTemp(t: Target): Boolean = t != Target.PDF && t != Target.TEXT && t != Target.HEX
}

/** Opens [item] in the matching in-app viewer (decrypting to a wiped private file only when the viewer needs one). */
fun openVaultItem(nav: NavHostController, item: VaultItem) {
    val t = VaultOpen.target(item.kind, item.name)
    if (!VaultOpen.needsTemp(t)) {
        val uri = VaultProvider.uri(item)
        nav.navigate(when (t) { VaultOpen.Target.PDF -> Routes.pdf(uri, item.name); VaultOpen.Target.TEXT -> Routes.text(uri, item.name); else -> Routes.hex(uri, item.name) })
        return
    }
    com.clpstudio.filesmanager.Graph.appScope.launch(kotlinx.coroutines.Dispatchers.Main) {
        val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.clpstudio.filesmanager.vault.VaultTemp.export(item) }.getOrNull() }
        if (f == null) {
            android.widget.Toast.makeText(com.clpstudio.filesmanager.Graph.app, "Couldn't open ${item.name}", android.widget.Toast.LENGTH_SHORT).show()
            return@launch
        }
        when (t) {
            VaultOpen.Target.EPUB -> nav.navigate(Routes.epub(f.path))
            VaultOpen.Target.ARCHIVE -> nav.navigate(Routes.zip(f.path))
            VaultOpen.Target.APK -> nav.navigate(Routes.apk(f.path))
            VaultOpen.Target.FONT -> nav.navigate(Routes.font(Uri.fromFile(f), f.name))
            else -> {
                ViewerSession.files = listOf(f.path)
                nav.navigate(Routes.view(f.path))
            }
        }
    }
}

/**
 * A viewer showing a decrypted vault file: FLAG_SECURE, closes when the vault locks, and the file is wiped when
 * this screen leaves the back stack (not when another screen is opened on top of it).
 */
@Composable
fun VaultTempGuard(path: String, entry: androidx.navigation.NavBackStackEntry, nav: NavHostController, back: () -> Unit) {
    if (!com.clpstudio.filesmanager.vault.VaultTemp.isTemp(path)) return
    VaultScreenScope()
    val state by Vault.state.collectAsState()
    LaunchedEffect(state) { if (state != VaultState.UNLOCKED) back() }
    val activity = androidx.activity.compose.LocalActivity.current
    androidx.compose.runtime.DisposableEffect(entry) {
        // The entry is destroyed when popped (or when the app closes) — not when a screen opens on top of it.
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_DESTROY && activity?.isChangingConfigurations != true) {
                com.clpstudio.filesmanager.vault.VaultTemp.wipe(path)
            }
        }
        entry.lifecycle.addObserver(obs)
        onDispose { entry.lifecycle.removeObserver(obs) }
    }
}
