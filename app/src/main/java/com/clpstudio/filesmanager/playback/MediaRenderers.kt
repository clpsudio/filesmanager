package com.clpstudio.filesmanager.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory

/**
 * One renderer setup for every player in the app (music service, the pager's video, the slideshow): the phone's own
 * decoders first, and the bundled FFmpeg decoders (offline, in the APK) for codecs the phone can't play — e.g. AC3 /
 * E-AC3 / DTS / TrueHD / ALAC audio in MKV / MP4 / TS files, Vorbis / Opus / FLAC on phones without them.
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
