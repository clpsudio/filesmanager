package com.clpstudio.filesmanager

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.clpstudio.filesmanager.data.AppSettings
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.data.UnusedApps
import com.clpstudio.filesmanager.ui.screens.LogsContent
import com.clpstudio.filesmanager.ui.screens.SettingsContent
import com.clpstudio.filesmanager.ui.screens.UnusedAppsContent
import com.clpstudio.filesmanager.ui.theme.FilesTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders for items 35, 36, 38, 39 (looked at by hand). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class Round8RenderTest {
    @get:Rule val compose = createComposeRule()
    private val day = UnusedApps.DAY
    private val now = System.currentTimeMillis()

    @Test fun unusedApps() {
        val apps = listOf(
            UnusedApps.classify("com.example.game", "Old Game", now - 800 * day, now - 900 * day, now - 900 * day, now)!!,
            UnusedApps.classify("com.example.travel", "Travel", 0L, now - 420 * day, now - 420 * day, now)!!,
            UnusedApps.classify("com.example.shop", "Shop", now - 100 * day, now - 900 * day, now - 900 * day, now)!!,
            UnusedApps.classify("com.example.bank", "Bank", now - 40 * day, now - 900 * day, now - 900 * day, now)!!,
        ).sortedByDescending { it.bucket.ordinal }
        compose.setContent { FilesTheme(ThemeMode.DARK, liquidGlass = true) { UnusedAppsContent(true, apps, {}, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("/home/user/tab/files/files_r8_unused_apps.png")
    }

    @Test fun logs() {
        val text = "App: Files 1.0.dev\nAndroid 15 (API 35)\n\n=== App log ===\n2026-10-31 17:20:01.120 [app] App start — Files 1.0.dev\n2026-10-31 17:20:05.004 [storage] index query failed — IllegalStateException: x\n2026-10-31 17:21:00.000 [CRASH] on thread main"
        compose.setContent { FilesTheme(ThemeMode.LIGHT, liquidGlass = true) { LogsContent(text, {}, {}, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("/home/user/tab/files/files_r8_logs.png")
    }

    @Test fun settingsPrivacy() {
        compose.setContent {
            FilesTheme(ThemeMode.LIGHT, liquidGlass = true) {
                SettingsContent(AppSettings(showHidden = true, hiddenInSearch = true, blockScreenshots = true), {}, {}, {}, {}, {})
            }
        }
        compose.onRoot().captureRoboImage("/home/user/tab/files/files_r8_settings_top.png")
    }
}
