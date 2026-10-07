package dev.streamer.app.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * Shuffle order that behaves like a music queue:
 * - a new list is fully shuffled;
 * - turning shuffle on keeps the current song first ([startingWith]);
 * - songs inserted after an item play right after it in shuffled order too, so
 *   "Play next" stays next; appended songs go to the end.
 * Media3's default order places insertions at random positions instead.
 */
@UnstableApi // ShuffleOrder is an unstable Media3 API.
class QueueShuffleOrder(private val order: IntArray, private val random: Random = Random.Default) : ShuffleOrder {
    private val positionOf = IntArray(order.size).also { pos -> order.forEachIndexed { p, index -> pos[index] = p } }

    override fun getLength(): Int = order.size

    override fun getNextIndex(index: Int): Int {
        val p = positionOf[index] + 1
        return if (p < order.size) order[p] else C.INDEX_UNSET
    }

    override fun getPreviousIndex(index: Int): Int {
        val p = positionOf[index] - 1
        return if (p >= 0) order[p] else C.INDEX_UNSET
    }

    override fun getLastIndex(): Int = if (order.isEmpty()) C.INDEX_UNSET else order.last()

    override fun getFirstIndex(): Int = if (order.isEmpty()) C.INDEX_UNSET else order.first()

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        if (order.isEmpty()) return QueueShuffleOrder(shuffled(insertionCount, random), random)
        val shifted = order.map { if (it >= insertionIndex) it + insertionCount else it }
        val inserted = (insertionIndex until insertionIndex + insertionCount).toList()
        val at = when {
            insertionIndex >= order.size -> shifted.size // Appended: end of the shuffled order.
            insertionIndex == 0 -> 0
            else -> positionOf[insertionIndex - 1] + 1 // Right after the item it was inserted after.
        }
        return QueueShuffleOrder((shifted.subList(0, at) + inserted + shifted.subList(at, shifted.size)).toIntArray(), random)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val count = indexToExclusive - indexFrom
        val kept = order.filter { it < indexFrom || it >= indexToExclusive }.map { if (it >= indexToExclusive) it - count else it }
        return QueueShuffleOrder(kept.toIntArray(), random)
    }

    override fun cloneAndClear(): ShuffleOrder = QueueShuffleOrder(IntArray(0), random)

    companion object {
        /** Shuffled order of [length] items with [first] playing first. */
        fun startingWith(length: Int, first: Int, random: Random = Random.Default): QueueShuffleOrder {
            if (length == 0) return QueueShuffleOrder(IntArray(0), random)
            val rest = (0 until length).filter { it != first }.shuffled(random)
            val order = if (first in 0 until length) intArrayOf(first) + rest.toIntArray() else shuffled(length, random)
            return QueueShuffleOrder(order, random)
        }

        private fun shuffled(length: Int, random: Random) = (0 until length).shuffled(random).toIntArray()
    }
}
