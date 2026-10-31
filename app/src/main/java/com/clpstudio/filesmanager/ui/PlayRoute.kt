package com.clpstudio.filesmanager.ui

import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind

/** Where "Play N" sends a selection. */
enum class PlayRoute {
    NONE, MUSIC, VIDEO, SLIDESHOW;

    companion object {
        fun of(items: List<FileItem>): PlayRoute {
            val kinds = items.filter { !it.isDir }.map { it.kind }.filter { it == FileKind.IMAGE || it == FileKind.VIDEO || it == FileKind.AUDIO }.toSet()
            return when {
                kinds.isEmpty() -> NONE
                kinds == setOf(FileKind.AUDIO) -> MUSIC
                kinds == setOf(FileKind.VIDEO) -> VIDEO
                else -> SLIDESHOW
            }
        }

        /** The slideshow's photo options ("Time per photo", Play slideshow) only make sense with photos. */
        fun hasPhotos(items: List<FileItem>): Boolean = items.any { !it.isDir && it.kind == FileKind.IMAGE }
    }
}
