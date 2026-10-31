package com.clpstudio.filesmanager.viewer

import com.clpstudio.filesmanager.data.FileItem
import com.clpstudio.filesmanager.data.FileKind
import kotlin.random.Random

/** Slideshow settings chosen in the options sheet. */
data class SlideshowOptions(
    val seconds: Int = 5,
    /** On by default (item 37): a finished slideshow / playlist starts again from the first item. */
    val repeat: Boolean = true,
    val shuffle: Boolean = false,
) {
    init { require(seconds in MIN_SECONDS..MAX_SECONDS) { "seconds out of range: $seconds" } }

    companion object {
        const val MIN_SECONDS = 1
        const val MAX_SECONDS = 30
        val PRESETS = listOf(2, 3, 5, 8, 10, 15)
    }
}

/** Remembers the last options for the whole app session (not saved to disk). */
object SlideshowSession {
    @Volatile var options: SlideshowOptions = SlideshowOptions()
}

/** Ken Burns parameters for one photo: scale goes from [startScale] to [endScale]; the image drifts from -drift to +drift (dp). */
data class KenBurns(val startScale: Float, val endScale: Float, val driftXDp: Float, val driftYDp: Float) {
    val zoomsIn: Boolean get() = endScale > startScale
}

/**
 * Pure slideshow order: which item plays now, what comes next, repeat and shuffle.
 * [items] is filtered to what a slideshow can play (photos, videos, audio).
 * Shuffle gives a fresh permutation every round (every item exactly once per round).
 */
class SlideshowPlan(
    items: List<FileItem>,
    val options: SlideshowOptions = SlideshowOptions(),
    private val random: Random = Random.Default,
    startAt: FileItem? = null,
) {
    val items: List<FileItem> = items.filter { isPlayable(it) }
    val size: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()

    /** Indices into [items] for the current round. */
    var order: List<Int> = newOrder(avoidFirst = -1)
        private set

    /** Position inside [order]. */
    var position: Int = 0
        private set

    /** How many times the order wrapped around (repeat). */
    var round: Int = 0
        private set

    init {
        if (startAt != null) {
            val idx = this.items.indexOfFirst { it.path == startAt.path }
            if (idx >= 0) {
                if (options.shuffle) {
                    // Keep the shuffle but start from the requested item.
                    order = listOf(idx) + order.filter { it != idx }
                } else {
                    position = order.indexOf(idx)
                }
            }
        }
    }

    val current: FileItem? get() = if (isEmpty) null else items[order[position]]

    val hasNext: Boolean get() = !isEmpty && (position < order.size - 1 || options.repeat)
    val hasPrevious: Boolean get() = !isEmpty && (position > 0 || options.repeat)

    /** The item after the current one (for preloading), or null at the end without repeat. */
    fun peekNext(): FileItem? = when {
        isEmpty -> null
        position < order.size - 1 -> items[order[position + 1]]
        options.repeat && !options.shuffle -> items[order[0]]
        else -> null // the next shuffled round isn't drawn yet
    }

    /** Moves forward. Returns false (and stays) at the end when repeat is off. */
    fun next(): Boolean {
        if (isEmpty) return false
        if (position < order.size - 1) {
            position++
            return true
        }
        if (!options.repeat) return false
        val last = order[position]
        order = if (options.shuffle) newOrder(avoidFirst = last) else order
        position = 0
        round++
        return true
    }

    /** Moves back. Returns false (and stays) at the start when repeat is off. */
    fun previous(): Boolean {
        if (isEmpty) return false
        if (position > 0) {
            position--
            return true
        }
        if (!options.repeat) return false
        position = order.size - 1
        return true
    }

    /** Back to the first item of a fresh round. */
    fun restart() {
        order = newOrder(avoidFirst = -1)
        position = 0
        round = 0
    }

    private fun newOrder(avoidFirst: Int): List<Int> {
        val base = items.indices.toMutableList()
        if (!options.shuffle || base.size < 2) return base
        base.shuffle(random)
        // Don't show the same photo twice in a row across rounds.
        if (base[0] == avoidFirst) {
            val swap = 1 + random.nextInt(base.size - 1)
            base[0] = base[swap].also { base[swap] = base[0] }
        }
        return base
    }

    companion object {
        const val ZOOM_MIN = 1.00f
        const val ZOOM_MAX = 1.08f
        const val DRIFT_DP = 6f
        const val CROSSFADE_MS = 600

        fun isPlayable(item: FileItem): Boolean =
            !item.isDir && (item.kind == FileKind.IMAGE || item.kind == FileKind.VIDEO || item.kind == FileKind.AUDIO)

        /** Ken Burns for the [step]-th slide shown: even steps zoom in 1.00→1.08, odd steps zoom out 1.08→1.00. */
        fun kenBurns(step: Int): KenBurns {
            val zoomIn = step % 2 == 0
            // Drift direction rotates through the four diagonals so consecutive photos move differently.
            val (dx, dy) = when (Math.floorMod(step, 4)) {
                0 -> 1f to 1f
                1 -> -1f to 1f
                2 -> -1f to -1f
                else -> 1f to -1f
            }
            return if (zoomIn) KenBurns(ZOOM_MIN, ZOOM_MAX, dx * DRIFT_DP, dy * DRIFT_DP)
            else KenBurns(ZOOM_MAX, ZOOM_MIN, dx * DRIFT_DP, dy * DRIFT_DP)
        }
    }
}
