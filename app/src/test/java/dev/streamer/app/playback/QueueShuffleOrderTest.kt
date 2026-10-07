package dev.streamer.app.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.source.ShuffleOrder
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class QueueShuffleOrderTest {
    private fun ShuffleOrder.toList(): List<Int> = buildList {
        var i = firstIndex
        while (i != C.INDEX_UNSET) {
            add(i)
            i = getNextIndex(i)
        }
    }

    @Test
    fun startingWithPutsCurrentFirstAndCoversAll() {
        val order = QueueShuffleOrder.startingWith(10, first = 4, random = Random(1)).toList()
        assertEquals(4, order.first())
        assertEquals((0 until 10).toSet(), order.toSet())
        assertEquals(10, order.size)
    }

    @Test
    fun freshListIsAFullPermutation() {
        val order = QueueShuffleOrder(IntArray(0), Random(2)).cloneAndInsert(0, 50).toList()
        assertEquals((0 until 50).toSet(), order.toSet())
    }

    @Test
    fun playNextInsertionPlaysRightAfterTheCurrentItem() {
        val start = QueueShuffleOrder(intArrayOf(3, 0, 4, 1, 2), Random(3))
        // Current is timeline index 0; "play next" inserts at timeline index 1.
        val after = start.cloneAndInsert(1, 1).toList()
        // Old indices >= 1 shift by one: 3->4, 4->5, 1->2, 2->3. The new item (1) follows 0.
        assertEquals(listOf(4, 0, 1, 5, 2, 3), after)
    }

    @Test
    fun appendedItemsGoLast() {
        val after = QueueShuffleOrder(intArrayOf(2, 0, 1), Random(4)).cloneAndInsert(3, 2).toList()
        assertEquals(listOf(2, 0, 1, 3, 4), after)
    }

    @Test
    fun removalKeepsRelativeOrderAndReindexes() {
        val after = QueueShuffleOrder(intArrayOf(3, 0, 4, 1, 2), Random(5)).cloneAndRemove(1, 3).toList()
        // Removes timeline indices 1 and 2; 3->1, 4->2.
        assertEquals(listOf(1, 0, 2), after)
    }

    @Test
    fun previousAndBoundaries() {
        val o = QueueShuffleOrder(intArrayOf(2, 0, 1), Random(6))
        assertEquals(2, o.firstIndex)
        assertEquals(1, o.lastIndex)
        assertEquals(C.INDEX_UNSET, o.getPreviousIndex(2))
        assertEquals(2, o.getPreviousIndex(0))
        assertEquals(C.INDEX_UNSET, o.getNextIndex(1))
        assertEquals(0, o.cloneAndClear().length)
    }
}
