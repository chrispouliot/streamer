package dev.streamer.app.demo

import dev.streamer.app.data.LibraryRepository
import dev.streamer.app.model.AlbumDetail
import dev.streamer.app.model.ArtistDetail
import dev.streamer.app.model.PlaylistDetail
import dev.streamer.app.model.SearchResults
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.time.Duration.Companion.milliseconds

/** Fictional library for tests only. */
class DemoLibraryRepository(private val catalog: DemoCatalog = DemoCatalog) : LibraryRepository {
    override val playlists = flowOf(catalog.playlists.map { it.summary })
    override val recentlyAddedAlbums = flowOf(catalog.albums.sortedByDescending { it.summary.year }.map { it.summary })
    override val recentlyPlayed = flowOf(catalog.recentlyPlayed)
    override val albums = flowOf(catalog.albums.map { it.summary }.sortedBy { it.name.lowercase() })
    override val artists = flowOf(catalog.artists.map { it.summary }.sortedBy { it.name.lowercase() })
    override val songs = flowOf(catalog.allSongs.sortedBy { it.title.lowercase() })

    private val starred = MutableStateFlow(catalog.initiallyStarred)
    override val starredSongIds = starred.asStateFlow()
    override val starredSongs = starred.map { ids -> catalog.allSongs.filter { it.id in ids } }

    override val recentCollections = flowOf(emptyList<dev.streamer.app.model.RecentCollection>())
    override fun syncStatus(target: dev.streamer.app.data.SyncTarget) = flowOf(dev.streamer.app.data.SyncStatus())
    override suspend fun refresh(target: dev.streamer.app.data.SyncTarget): String? = null

    override fun recordPlayed(song: dev.streamer.app.model.Song, source: dev.streamer.app.playback.PlaybackSource?) = Unit

    override fun setSongStarred(songId: String, starred: Boolean) {
        this.starred.update { if (starred) it + songId else it - songId }
    }

    override fun album(id: String): Flow<AlbumDetail?> = flowOf(catalog.albums.firstOrNull { it.summary.id == id })
    override fun artist(id: String): Flow<ArtistDetail?> = flowOf(catalog.artists.firstOrNull { it.summary.id == id })
    override fun playlist(id: String): Flow<PlaylistDetail?> = flowOf(catalog.playlists.firstOrNull { it.summary.id == id })

    override suspend fun search(query: String): SearchResults {
        delay(150.milliseconds) // Behave like a network call so debounce/cancellation are exercised.
        val q = query.trim()
        if (q.isEmpty()) return SearchResults()
        fun String.hit() = contains(q, ignoreCase = true)
        return SearchResults(
            songs = catalog.allSongs.filter { it.title.hit() || it.artist.hit() }.take(20),
            albums = catalog.albums.map { it.summary }.filter { it.name.hit() || it.artist.hit() },
            artists = catalog.artists.map { it.summary }.filter { it.name.hit() },
            playlists = catalog.playlists.map { it.summary }.filter { it.name.hit() },
        )
    }
}
