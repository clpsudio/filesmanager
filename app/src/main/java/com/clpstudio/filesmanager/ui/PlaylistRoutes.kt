package com.clpstudio.filesmanager.ui

import android.content.Context
import android.net.Uri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.clpstudio.filesmanager.Graph
import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.PlaylistRules
import com.clpstudio.filesmanager.data.PlaylistType
import com.clpstudio.filesmanager.playback.PlayerConnection
import com.clpstudio.filesmanager.ui.screens.PlaylistDetailScreen
import com.clpstudio.filesmanager.ui.screens.PlaylistsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Saved playlists' screens (kept out of AppRoot / Routes so the shared files only gain a couple of lines). */
object PlaylistRoutes {
    fun list(type: PlaylistType) = "playlists/${type.name}"
    fun detail(id: String) = "playlist?id=${Uri.encode(id)}"
}

fun NavGraphBuilder.playlistRoutes(nav: NavHostController, back: () -> Unit, opener: Opener, context: Context) {
    composable("playlists/{type}", arguments = listOf(navArgument("type") { type = NavType.StringType })) { e ->
        val type = runCatching { PlaylistType.valueOf(e.arguments?.getString("type").orEmpty()) }.getOrDefault(PlaylistType.AUDIO)
        PlaylistsScreen(type, onBack = back, onOpen = { id -> nav.navigate(PlaylistRoutes.detail(id)) })
    }
    composable("playlist?id={id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
        PlaylistDetailScreen(e.arguments?.getString("id").orEmpty(), onBack = back, play = { items, start ->
            PlaylistPlayer.play(context, nav, opener, items, start)
        })
    }
}

/**
 * Plays a saved playlist through the same "Play N" paths (item 37: loop settings ignored, one by one, then
 * the first again): songs → music player playlist, videos → the gallery video pager in playlist mode,
 * photos / photos + videos → the slideshow. [start] = the tapped item (0 = from the top).
 */
object PlaylistPlayer {
    fun play(context: Context, nav: NavHostController, opener: Opener, items: List<FileItem>, start: Int) {
        if (items.isEmpty()) return
        if (start <= 0) { opener.playSelected(items); return }
        when (PlayRoute.of(items)) {
            PlayRoute.NONE -> Unit
            PlayRoute.MUSIC -> {
                val audios = items.filter { it.kind == FileKind.AUDIO }
                val index = audios.indexOf(items[start]).coerceAtLeast(0)
                Graph.appScope.launch(Dispatchers.Main) { runCatching { PlayerConnection.playAudio(context, audios, index, playlist = true) } }
                nav.navigate(Routes.MUSIC) { launchSingleTop = true }
            }
            PlayRoute.VIDEO -> {
                val videos = items.filter { it.kind == FileKind.VIDEO }
                ViewerSession.files = videos.map { it.path }
                ViewerSession.playlist = true
                nav.navigate(Routes.view(items[start].path))
            }
            PlayRoute.SLIDESHOW -> {
                ViewerSession.playItems = PlaylistRules.from(items, start)
                nav.navigate(Routes.SLIDESHOW)
            }
        }
    }
}
