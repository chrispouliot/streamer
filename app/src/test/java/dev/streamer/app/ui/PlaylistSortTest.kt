package dev.streamer.app.ui

import dev.streamer.app.model.Artwork
import dev.streamer.app.model.PlaylistEntry
import dev.streamer.app.model.Song
import dev.streamer.app.settings.TrackSort
import dev.streamer.app.settings.TrackSortField
import dev.streamer.app.ui.components.sortedForTracks
import dev.streamer.app.ui.detail.defaultSortFor
import dev.streamer.app.ui.detail.sortedFor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class PlaylistSortTest {
    private fun song(id: String, title: String, artist: String, favourited: String? = null, seconds: Int? = null) =
        Song(id, title, artist, null, null, null, seconds?.seconds, null, Artwork(null, id), favouritedAt = favourited?.let(Instant::parse))

    // Position order is the order songs were added; "b" appears twice.
    private val entries = listOf(
        PlaylistEntry(0, song("a", "Zebra", "Mono")),
        PlaylistEntry(1, song("b", "apple", "Beta")),
        PlaylistEntry(2, song("c", "Mango", "alpha")),
        PlaylistEntry(3, song("b", "apple", "Beta")),
    )

    @Test
    fun dateAddedBothDirectionsKeepDuplicates() {
        assertEquals(listOf(3, 2, 1, 0), entries.sortedFor(TrackSort(TrackSortField.Added, descending = true)).map { it.position })
        assertEquals(listOf(0, 1, 2, 3), entries.shuffled().sortedFor(TrackSort(TrackSortField.Added, descending = false)).map { it.position })
    }

    @Test
    fun titleAndArtistIgnoreCaseInBothDirections() {
        assertEquals(listOf("apple", "apple", "Mango", "Zebra"), entries.sortedFor(TrackSort(TrackSortField.Title, false)).map { it.song.title })
        assertEquals(listOf("Zebra", "Mango", "apple", "apple"), entries.sortedFor(TrackSort(TrackSortField.Title, true)).map { it.song.title })
        assertEquals(listOf("alpha", "Beta", "Beta", "Mono"), entries.sortedFor(TrackSort(TrackSortField.Artist, false)).map { it.song.artist })
        assertEquals(listOf("Mono", "Beta", "Beta", "alpha"), entries.sortedFor(TrackSort(TrackSortField.Artist, true)).map { it.song.artist })
    }

    @Test
    fun favouritedSortsByTimeAndKeepsUnfavouritedLast() {
        val list = listOf(
            PlaylistEntry(0, song("x", "X", "A", "2026-03-01T00:00:00Z")),
            PlaylistEntry(1, song("y", "Y", "A")),
            PlaylistEntry(2, song("z", "Z", "A", "2026-04-01T00:00:00Z")),
        )
        assertEquals(listOf(2, 0, 1), list.sortedFor(TrackSort(TrackSortField.Favourited, true)).map { it.position })
        assertEquals(listOf(0, 2, 1), list.sortedFor(TrackSort(TrackSortField.Favourited, false)).map { it.position })
    }

    @Test
    fun defaultsMatchPreviousBehaviour() {
        assertEquals(TrackSort(TrackSortField.Added, true), defaultSortFor(entries))
        val allFavourites = listOf(PlaylistEntry(0, song("x", "X", "A", "2026-03-01T00:00:00Z")))
        assertEquals(TrackSort(TrackSortField.Favourited, true), defaultSortFor(allFavourites))
        assertEquals(TrackSort(TrackSortField.Added, true), defaultSortFor(emptyList()))
    }

    @Test
    fun savedChoicesRoundTripAndOldOnesAreRead() {
        val sort = TrackSort(TrackSortField.Title, descending = true)
        assertEquals(sort, TrackSort.decode(sort.encode()))
        assertEquals(TrackSort(TrackSortField.Added, true), TrackSort.decode("RecentlyAdded"))
        assertEquals(TrackSort(TrackSortField.Added, false), TrackSort.decode("PlaylistOrder"))
        assertEquals(TrackSort(TrackSortField.Favourited, true), TrackSort.decode("RecentlyFavourited"))
        assertEquals(null, TrackSort.decode("Nonsense"))
    }

    @Test
    fun albumDurationAndTrackOrder() {
        val songs = listOf(song("1", "A", "X", seconds = 200), song("2", "B", "X", seconds = 100), song("3", "C", "X", seconds = 300))
        val indexed = songs.withIndex().toList()
        assertEquals(listOf(1, 0, 2), indexed.sortedForTracks(TrackSort(TrackSortField.Duration, false), { it.index }, { it.value }).map { it.index })
        assertEquals(listOf(2, 1, 0), indexed.sortedForTracks(TrackSort(TrackSortField.Track, true), { it.index }, { it.value }).map { it.index })
    }
}
