package com.clpstudio.filesmanager

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.clpstudio.filesmanager.ui.AppRoot
import com.clpstudio.filesmanager.ui.theme.FilesTheme

class MainActivity : ComponentActivity() {
    /** Bumped when the media notification is tapped, so the app shows the player. */
    private val nowPlayingTick = mutableIntStateOf(0)
    /** A folder to open (from "Open folder" after extracting in the file opener). */
    private val browsePath = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        com.clpstudio.filesmanager.ui.PrivacyGuard.install(this)
        enableEdgeToEdge()
        if (intent?.action == ACTION_NOW_PLAYING) nowPlayingTick.intValue++
        if (intent?.action == ACTION_BROWSE) browsePath.value = intent.getStringExtra(EXTRA_PATH)
        setContent {
            val settings by Graph.settings.collectAsState()
            FilesTheme(settings.theme, settings.liquidGlass) {
                AppRoot(nowPlayingTick.intValue, browsePath.value) { browsePath.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_NOW_PLAYING) nowPlayingTick.intValue++
        if (intent.action == ACTION_BROWSE) browsePath.value = intent.getStringExtra(EXTRA_PATH)
    }

    companion object {
        const val ACTION_NOW_PLAYING = "com.clpstudio.filesmanager.NOW_PLAYING"
        const val ACTION_BROWSE = "com.clpstudio.filesmanager.BROWSE"
        const val EXTRA_PATH = "path"
    }
}
