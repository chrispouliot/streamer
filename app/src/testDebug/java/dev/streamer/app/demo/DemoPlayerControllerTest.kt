package dev.streamer.app.demo

import dev.streamer.app.playback.PlaybackSource
import dev.streamer.app.playback.RepeatMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DemoPlayerControllerTest {
    private val album = DemoCatalog.albums.first()
    private val songs = album.songs
    private val source = PlaybackSource.Album(album.summary.id, album.summary.name)

    private fun TestScope.player() = DemoPlayerController(backgroundScope, Random(42))

    @Test
    fun playStartsAtSelectedIndexWithSourcePositions() = runTest {
        val p = player()
        p.play(songs, 3, source)
        val s = p.state.value
        assertEquals(songs[3], s.current?.song)
        assertEquals(3, s.current?.sourceIndex)
        assertTrue(s.isPlaying)
        assertEquals(source, s.source)
    }

    @Test
    fun duplicateSongsGetDistinctOccurrences() = runTest {
        val p = player()
        val dup = DemoCatalog.playlists.first { it.summary.id == "pl-late-night" }.entries.map { it.song }
        p.play(dup, 0)
        val ids = p.state.value.queue.map { it.occurrenceId }
        assertEquals(ids.size, ids.toSet().size)
        val northbound = p.state.value.queue.filter { it.song.title == "Northbound" }
        assertEquals(2, northbound.size)
        // Skipping to the second occurrence selects that one, not the first.
        p.skipTo(northbound[1].occurrenceId)
        assertEquals(5, p.state.value.currentIndex)
    }

    @Test
    fun clockAdvancesToNextTrackAndStopsAtEnd() = runTest {
        val p = player()
        p.play(songs.takeLast(2), 0, source)
        val first = songs.takeLast(2)[0].duration!!
        advanceTimeBy(first + 1.seconds)
        runCurrent()
        assertEquals(1, p.state.value.currentIndex)
        advanceTimeBy(songs.last().duration!! + 1.seconds)
        runCurrent()
        assertFalse(p.state.value.isPlaying)
        assertEquals(1, p.state.value.currentIndex)
    }

    @Test
    fun repeatAllWrapsAndRepeatCycles() = runTest {
        val p = player()
        p.play(songs.take(2), 1)
        p.next()
        assertEquals(1, p.state.value.currentIndex) // Off: stays at the end.
        p.cycleRepeat()
        assertEquals(RepeatMode.All, p.state.value.repeat)
        p.next()
        assertEquals(0, p.state.value.currentIndex)
        p.cycleRepeat()
        p.cycleRepeat()
        assertEquals(RepeatMode.Off, p.state.value.repeat)
    }

    @Test
    fun previousRestartsWhenPastThreshold() = runTest {
        val p = player()
        p.play(songs, 2)
        p.seekTo(10.seconds)
        p.previous()
        assertEquals(2, p.state.value.currentIndex)
        assertEquals(0.seconds, p.state.value.position)
        p.previous()
        assertEquals(1, p.state.value.currentIndex)
    }

    @Test
    fun shuffleKeepsCurrentAndRestoresOrder() = runTest {
        val p = player()
        p.play(songs, 4)
        val current = p.state.value.current
        p.setShuffle(true)
        assertEquals(current, p.state.value.current)
        assertEquals(songs.size, p.state.value.queue.size)
        assertNotEquals(songs.drop(5), p.state.value.upNext.map { it.song })
        p.setShuffle(false)
        assertEquals(songs, p.state.value.queue.map { it.song })
        assertEquals(current, p.state.value.current)
    }

    @Test
    fun queueEditsOnlyAffectUpcomingEntries() = runTest {
        val p = player()
        p.play(songs.take(5), 1)
        val current = p.state.value.current!!
        p.remove(current.occurrenceId)
        assertEquals(current, p.state.value.current) // Current entry is not removed in place.

        val upcoming = p.state.value.upNext
        p.moveUpcoming(upcoming[2].occurrenceId, -10)
        assertEquals(upcoming[2], p.state.value.upNext.first())
        assertEquals(current, p.state.value.current)

        val before = p.state.value.queue.first()
        p.remove(before.occurrenceId)
        assertEquals(0, p.state.value.currentIndex)
        assertEquals(current, p.state.value.current)
    }

    @Test
    fun playNextInsertsAfterCurrent() = runTest {
        val p = player()
        p.play(songs.take(3), 0)
        val extra = songs[7]
        p.playNext(extra)
        assertEquals(extra, p.state.value.upNext.first().song)
        p.addToQueue(extra)
        assertEquals(extra, p.state.value.queue.last().song)
        assertEquals(null, p.state.value.queue.last().sourceIndex)
    }

    @Test
    fun clearQueueStopsPlayback() = runTest {
        val p = player()
        p.play(songs, 0)
        p.clearQueue()
        assertFalse(p.state.value.hasQueue)
        assertFalse(p.state.value.isPlaying)
    }
}
