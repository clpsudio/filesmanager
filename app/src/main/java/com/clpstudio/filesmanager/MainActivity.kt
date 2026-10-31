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

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent?.action == ACTION_NOW_PLAYING) nowPlayingTick.intValue++
        setContent {
            val settings by Graph.settings.collectAsState()
            FilesTheme(settings.theme, settings.liquidGlass) {
                AppRoot(nowPlayingTick.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_NOW_PLAYING) nowPlayingTick.intValue++
    }

    companion object {
        const val ACTION_NOW_PLAYING = "com.clpstudio.filesmanager.NOW_PLAYING"
    }
}
