package com.clpstudio.filesmanager.playback

import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import com.clpstudio.filesmanager.data.LoopMode
import com.clpstudio.filesmanager.data.LoopPolicy

/** The audio playlist (item 25): what is queued when a song is opened, and what plays after a song ends. Pure. */
object AudioQueue {
    /**
     * Every audio file of the list the song was opened from (folder, Audio, Recent, search, Favorites), in the
     * list's order, starting at the tapped one. The tapped song alone only when the list doesn't have it.
     */
    fun build(tapped: FileItem, siblings: List<FileItem>): Pair<List<FileItem>, Int> {
        val audios = siblings.filter { !it.isDir && it.kind == FileKind.AUDIO }.distinctBy { it.path }
        val index = audios.indexOfFirst { it.path == tapped.path }
        return if (index >= 0) audios to index else listOf(tapped) to 0
    }

    /**
     * The track after track [index] of [size] finished its [playsDone]-th play: itself while the loop wants another
     * play, otherwise the next one, and after the last the first again (item 24).
     */
    fun nextAfterEnd(index: Int, size: Int, mode: LoopMode, times: Int, playsDone: Int): Int {
        if (size <= 0) return 0
        return if (LoopPolicy.afterPlay(mode, times, playsDone, hasNext = true) == LoopPolicy.After.REPLAY) index else (index + 1) % size
    }
}
