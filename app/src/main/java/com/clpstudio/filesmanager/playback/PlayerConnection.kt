package com.clpstudio.filesmanager.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.clpstudio.filesmanager.data.FileItem
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import java.io.File

/** One connection from the UI to the playback service, shared by all screens. */
object PlayerConnection {
    private var future: ListenableFuture<MediaController>? = null

    private fun future(context: Context): ListenableFuture<MediaController> {
        val f = future
        if (f != null && (!f.isDone || runCatching { f.get().isConnected }.getOrDefault(false))) return f
        val token = SessionToken(context.applicationContext, ComponentName(context.applicationContext, PlaybackService::class.java))
        return MediaController.Builder(context.applicationContext, token).buildAsync().also { future = it }
    }

    suspend fun controller(context: Context): MediaController = future(context).await()

    suspend fun playAudio(context: Context, files: List<FileItem>, index: Int, playlist: Boolean = false) {
        AudioLoop.playlist = playlist
        val items = files.map { PlaybackService.item(Uri.fromFile(File(it.path)), it.name.substringBeforeLast('.'), isVideo = false) }
        // A video may have left the shared player on "repeat one" (the 1.0.10 bug: only the first song played).
        // Start with the audio loop's own mode; the service keeps it right on every song after this.
        val s = runCatching { com.clpstudio.filesmanager.Graph.settings.value }.getOrNull()
        val c = controller(context)
        AudioLoop.plays = 0
        c.repeatMode = AudioLoop.repeatModeFor(com.clpstudio.filesmanager.data.PlaylistPlay.loopMode(playlist, s?.audioLoopMode ?: com.clpstudio.filesmanager.data.LoopMode.OFF), s?.audioLoopTimes ?: 3, 0)
        c.shuffleModeEnabled = false
        // The remembered audio speed (item 52), natural pitch; a video may have left its own speed on the shared player.
        c.playbackParameters = AudioSpeed.params()
        play(context, items, index)
    }

    suspend fun play(context: Context, items: List<MediaItem>, index: Int) {
        val c = controller(context)
        c.setMediaItems(items, index.coerceIn(0, (items.size - 1).coerceAtLeast(0)), 0L)
        c.prepare()
        c.play()
    }
}

/** Snapshot of the player for the UI, refreshed on every player event and every half second. */
data class PlayerUi(
    val controller: Player? = null,
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val artwork: ByteArray? = null,
    val isVideo: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val index: Int = 0,
    val queue: List<MediaMetadata> = emptyList(),
    /** Playback speed (item 52). */
    val speed: Float = 1f,
)

@Composable
fun rememberPlayerUi(connect: Boolean = true): PlayerUi {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<Player?>(null) }
    var ui by remember { mutableStateOf(PlayerUi()) }
    LaunchedEffect(connect) {
        if (connect) controller = runCatching { PlayerConnection.controller(context) }.getOrNull()
    }
    val c = controller
    fun read(p: Player): PlayerUi {
        val md = p.mediaMetadata
        return PlayerUi(
            controller = p,
            hasMedia = p.mediaItemCount > 0,
            isPlaying = p.isPlaying,
            title = (md.title ?: md.displayTitle ?: p.currentMediaItem?.mediaMetadata?.title ?: "").toString(),
            artist = (md.artist ?: md.albumArtist ?: "").toString(),
            artwork = md.artworkData,
            isVideo = p.currentMediaItem?.mediaMetadata?.extras?.getBoolean(PlaybackService.EXTRA_IS_VIDEO) == true ||
                md.extras?.getBoolean(PlaybackService.EXTRA_IS_VIDEO) == true,
            position = p.currentPosition.coerceAtLeast(0),
            duration = p.duration.takeIf { it > 0 } ?: 0,
            shuffle = p.shuffleModeEnabled,
            repeatMode = p.repeatMode,
            index = p.currentMediaItemIndex,
            queue = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaMetadata },
            speed = p.playbackParameters.speed,
        )
    }
    if (c != null) {
        DisposableEffect(c) {
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    ui = read(player)
                }
            }
            c.addListener(listener)
            ui = read(c)
            onDispose { c.removeListener(listener) }
        }
        LaunchedEffect(c, ui.isPlaying) {
            while (ui.isPlaying) {
                ui = ui.copy(position = c.currentPosition.coerceAtLeast(0), duration = c.duration.takeIf { it > 0 } ?: ui.duration)
                delay(500)
            }
        }
    }
    return ui
}
