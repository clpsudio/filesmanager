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

/** Vault and app-lock screens (kept out of AppRoot so the shared file only gains two lines). */
fun NavGraphBuilder.vaultRoutes(nav: NavHostController, back: () -> Unit) {
    val openDoc: (VaultItem) -> Unit = { item ->
        val uri = VaultProvider.uri(item)
        nav.navigate(if (item.kind == FileKind.PDF) Routes.pdf(uri, item.name) else Routes.text(uri, item.name))
    }
    composable(Routes.VAULT) {
        VaultScreen(
            onBack = back,
            onOpen = { item ->
                if (item.kind == FileKind.PDF || item.kind == FileKind.TEXT) openDoc(item) else nav.navigate(Routes.vaultView(item.id))
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
