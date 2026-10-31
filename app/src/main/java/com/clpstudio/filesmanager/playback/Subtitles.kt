package com.clpstudio.filesmanager.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import java.io.File

object Subtitles {
    private val exts = listOf("srt", "vtt", "ass", "ssa", "ttml")

    fun mimeFor(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    fun config(uri: Uri): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mimeFor(uri.lastPathSegment ?: ""))
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()

    /** Subtitle files next to a video with the same base name ("movie.srt", "movie.en.srt"). */
    fun siblings(video: File): List<Uri> {
        val dir = video.parentFile ?: return emptyList()
        val base = video.nameWithoutExtension
        return dir.listFiles { f ->
            f.isFile && f.name.startsWith(base) && f.extension.lowercase() in exts
        }.orEmpty().sortedBy { it.name.length }.map { Uri.fromFile(it) }
    }
}
