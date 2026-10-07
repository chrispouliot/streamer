package dev.streamer.app.ui

import dev.streamer.app.model.Artwork
import dev.streamer.app.model.PlaylistEntry
import dev.streamer.app.model.Song
import dev.streamer.app.settings.PlaylistSort
import dev.streamer.app.ui.detail.sortedFor
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistSortTest {
    private fun song(id: String, title: String, artist: String) =
        Song(id, title, artist, null, null, null, null, null, Artwork(null, id))

    // Position order is the order songs were added; "b" appears twice.
    private val entries = listOf(
        PlaylistEntry(0, song("a", "Zebra", "Mono")),
        PlaylistEntry(1, song("b", "apple", "Beta")),
        PlaylistEntry(2, song("c", "Mango", "alpha")),
        PlaylistEntry(3, song("b", "apple", "Beta")),
    )

    @Test
    fun recentlyAddedIsReversePlaylistOrderKeepingDuplicates() {
        assertEquals(listOf(3, 2, 1, 0), entries.sortedFor(PlaylistSort.RecentlyAdded).map { it.position })
    }

    @Test
    fun playlistOrder() {
        assertEquals(listOf(0, 1, 2, 3), entries.shuffled().sortedFor(PlaylistSort.PlaylistOrder).map { it.position })
    }

    @Test
    fun titleAndArtistIgnoreCase() {
        assertEquals(listOf("apple", "apple", "Mango", "Zebra"), entries.sortedFor(PlaylistSort.Title).map { it.song.title })
        assertEquals(listOf("alpha", "Beta", "Beta", "Mono"), entries.sortedFor(PlaylistSort.Artist).map { it.song.artist })
    }

    @Test
    fun defaultIsRecentlyAdded() {
        assertEquals(PlaylistSort.RecentlyAdded, PlaylistSort.entries.first())
    }
}

class FavouritesPlaylistSortTest {
    private fun entry(position: Int, favourited: String?) = PlaylistEntry(
        position,
        Song("s$position", "T$position", "A", null, null, null, null, null, Artwork(null, "x"), favouritedAt = favourited?.let(java.time.Instant::parse)),
    )

    @Test
    fun smartPlaylistOfFavouritesSortsByFavouriteTimeNotPosition() {
        // A rule-sorted playlist: position order does not reflect when songs were liked.
        val entries = listOf(
            entry(0, "2026-03-01T00:00:00Z"),
            entry(1, "2026-10-05T12:00:00Z"),
            entry(2, "2025-01-01T00:00:00Z"),
        )
        assertEquals(PlaylistSort.RecentlyFavourited, dev.streamer.app.ui.detail.defaultSortFor(entries))
        assertEquals(listOf(1, 0, 2), entries.sortedFor(PlaylistSort.RecentlyFavourited).map { it.position })
    }

    @Test
    fun mixedPlaylistDefaultsToRecentlyAddedAndUnfavouritedSortLast() {
        val entries = listOf(entry(0, "2026-03-01T00:00:00Z"), entry(1, null), entry(2, "2026-04-01T00:00:00Z"))
        assertEquals(PlaylistSort.RecentlyAdded, dev.streamer.app.ui.detail.defaultSortFor(entries))
        assertEquals(listOf(2, 0, 1), entries.sortedFor(PlaylistSort.RecentlyFavourited).map { it.position })
    }

    @Test
    fun emptyPlaylistDefaultsToRecentlyAdded() {
        assertEquals(PlaylistSort.RecentlyAdded, dev.streamer.app.ui.detail.defaultSortFor(emptyList()))
    }
}
