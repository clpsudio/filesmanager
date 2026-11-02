package com.clpstudio.filesmanager.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory

/**
 * One renderer setup for every player in the app (music service, the pager's video, the slideshow): the phone's own
 * decoders first, and the bundled FFmpeg decoders (offline, in the APK) only for FLAC, ALAC, G.711 (A-law / µ-law),
 * MP3 and AC-3 when the phone can't play them.
 * AAC, E-AC-3, DTS and TrueHD are NOT bundled (codec patents): they are played by the phone's own MediaCodec decoders
 * when the phone has them. The FFmpeg renderer does not claim them (FfmpegLibrary.supportsFormat is false because the
 * native library has no such decoder), so with MODE = ON ExoPlayer keeps using MediaCodec for them; a phone without a
 * decoder plays such a video without its audio track.
 * Video always uses the phone's decoders: the bundled FFmpeg build has no video decoding (its "experimental" video
 * renderer is an empty stub), and there is no WMV / ASF container reader in the player (F11 item 12 finding).
 */
@UnstableApi
object MediaRenderers {
    /** ON = extension renderers are used only when no platform decoder supports the format. */
    const val MODE = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON

    fun factory(context: Context): DefaultRenderersFactory = DefaultRenderersFactory(context.applicationContext)
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(MODE)
}
