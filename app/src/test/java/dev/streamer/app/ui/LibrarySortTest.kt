package dev.streamer.app.ui

import dev.streamer.app.model.AlbumSummary
import dev.streamer.app.model.ArtistSummary
import dev.streamer.app.model.Artwork
import dev.streamer.app.model.PlaylistSummary
import dev.streamer.app.settings.AlbumOrder
import dev.streamer.app.settings.ArtistOrder
import dev.streamer.app.settings.PlaylistListOrder
import dev.streamer.app.ui.components.relativeTime
import dev.streamer.app.ui.library.sortedFor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class LibrarySortTest {
    private fun album(name: String, artist: String, year: Int?, added: String?) =
        AlbumSummary(name, name, artist, null, year, null, Artwork(null, name), added?.let(Instant::parse))

    private val albums = listOf(
        album("beta", "Zed", 2020, "2026-01-01T00:00:00Z"),
        album("Alpha", "amy", null, null),
        album("Gamma", "Amy", 2024, "2026-06-01T00:00:00Z"),
    )

    @Test
    fun albumOrders() {
        assertEquals(listOf("Alpha", "beta", "Gamma"), albums.sortedFor(AlbumOrder.Name).map { it.name })
        assertEquals(listOf("Gamma", "Alpha", "beta"), albums.sortedFor(AlbumOrder.Artist).map { it.name })
        assertEquals(listOf("Gamma", "beta", "Alpha"), albums.sortedFor(AlbumOrder.Year).map { it.name })
        // Unknown added dates go last.
        assertEquals(listOf("Gamma", "beta", "Alpha"), albums.sortedFor(AlbumOrder.RecentlyAdded).map { it.name })
    }

    @Test
    fun artistOrders() {
        val artists = listOf(ArtistSummary("1", "b", 1, Artwork(null, "1")), ArtistSummary("2", "A", 5, Artwork(null, "2")), ArtistSummary("3", "c", null, Artwork(null, "3")))
        assertEquals(listOf("A", "b", "c"), artists.sortedFor(ArtistOrder.Name).map { it.name })
        assertEquals(listOf("A", "b", "c"), artists.sortedFor(ArtistOrder.MostAlbums).map { it.name })
    }

    @Test
    fun playlistOrders() {
        fun p(name: String, changed: String?) = PlaylistSummary(name, name, null, null, null, null, Artwork(null, name), changed?.let(Instant::parse))
        val playlists = listOf(p("b", "2026-01-01T00:00:00Z"), p("a", null), p("c", "2026-09-01T00:00:00Z"))
        assertEquals(listOf("a", "b", "c"), playlists.sortedFor(PlaylistListOrder.Name).map { it.name })
        assertEquals(listOf("c", "b", "a"), playlists.sortedFor(PlaylistListOrder.RecentlyUpdated).map { it.name })
    }

    @Test
    fun relativeTimes() {
        val now = Instant.parse("2026-10-06T12:00:00Z")
        assertEquals("just now", relativeTime(now.minusSeconds(30), now))
        assertEquals("5 min ago", relativeTime(now.minus(Duration.ofMinutes(5)), now))
        assertEquals("3 h ago", relativeTime(now.minus(Duration.ofHours(3)), now))
        assertEquals("yesterday", relativeTime(now.minus(Duration.ofHours(30)), now))
        assertEquals("4 days ago", relativeTime(now.minus(Duration.ofDays(4)), now))
    }
}

class LibraryFilterOrderTest {
    @Test
    fun downloadedComesFirstOnlyWhileOffline() {
        assertEquals(dev.streamer.app.ui.library.LibraryFilter.Playlists, dev.streamer.app.ui.library.filterOrder(offline = false).first())
        val offline = dev.streamer.app.ui.library.filterOrder(offline = true)
        assertEquals(dev.streamer.app.ui.library.LibraryFilter.Downloaded, offline.first())
        assertEquals(dev.streamer.app.ui.library.LibraryFilter.entries.toSet(), offline.toSet())
    }
}
