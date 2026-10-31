package com.clpstudio.filesmanager

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.security.CredentialType
import com.clpstudio.filesmanager.ui.security.AppLockScreen
import com.clpstudio.filesmanager.ui.security.AppLockSettingsScreen
import com.clpstudio.filesmanager.ui.security.UnlockForm
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.clpstudio.filesmanager.ui.vault.VaultSetup
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Phase 2: vault setup (with the no-recovery warning), vault unlock, app lock screen and settings. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h860dp-port-xxhdpi")
class SecurityScreenshotTest {
    @get:org.junit.Rule val compose = createComposeRule()
    private val out = File("build/screenshots").apply { mkdirs() }

    @Test fun vaultSetupShowsNoRecoveryWarning() {
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { VaultSetup(onBack = {}) } }
        compose.onNodeWithText("No recovery").assertExists()
        compose.onNodeWithText("I understand").performClick()
        compose.onNodeWithText("Create vault").assertExists()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(out, "files_p2_vault_setup.png").path)
    }

    @Test fun vaultUnlockWrongPin() {
        compose.setContent {
            FilesTheme(ThemeMode.DARK, liquidGlass = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(contentAlignment = Alignment.Center) {
                        UnlockForm(
                            type = CredentialType.PIN, title = "Vault is locked", subtitle = "12 files · enter your vault PIN",
                            check = { "Wrong. 3 tries left" }, onUnlocked = {}, remainingMs = { 0L },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(out, "files_p2_vault_unlock.png").path)
    }

    @Test fun appLockScreen() {
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { AppLockScreen(androidx.activity.compose.LocalActivity.current!!) }
        }
        compose.onNodeWithText("Files is locked").assertExists()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(out, "files_p2_applock_pin.png").path)
    }

    @Test fun appLockSettingsOff() {
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = false) { AppLockSettingsScreen(onBack = {}) } }
        compose.onNodeWithText("Turn on app lock").assertExists()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(out, "files_p2_applock_settings.png").path)
    }
}
