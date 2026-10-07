package dev.streamer.app

import android.app.Application
import dev.streamer.app.data.LibraryRepository
import dev.streamer.app.model.AlbumDetail
import dev.streamer.app.model.AlbumSummary
import dev.streamer.app.model.ArtistDetail
import dev.streamer.app.model.ArtistSummary
import dev.streamer.app.model.PlaylistDetail
import dev.streamer.app.model.PlaylistSummary
import dev.streamer.app.model.SearchResults
import dev.streamer.app.model.Song
import dev.streamer.app.playback.PlaybackSource
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.playback.PlayerState
import dev.streamer.app.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration

// Until the Navidrome client (Phase 2) and Media3 player (Phase 3) exist,
// release builds show truthful empty states rather than demo data.
fun createAppContainer(app: Application): AppContainer = AppContainer(
    library = EmptyLibraryRepository,
    player = IdlePlayerController,
    settings = SettingsRepository(app),
)

private object EmptyLibraryRepository : LibraryRepository {
    override val playlists = flowOf(emptyList<PlaylistSummary>())
    override val recentlyAddedAlbums = flowOf(emptyList<AlbumSummary>())
    override val recentlyPlayed = flowOf(emptyList<Song>())
    override val albums = flowOf(emptyList<AlbumSummary>())
    override val artists = flowOf(emptyList<ArtistSummary>())
    override val songs = flowOf(emptyList<Song>())
    override val starredSongIds = flowOf(emptySet<String>())
    override fun album(id: String): Flow<AlbumDetail?> = flowOf(null)
    override fun artist(id: String): Flow<ArtistDetail?> = flowOf(null)
    override fun playlist(id: String): Flow<PlaylistDetail?> = flowOf(null)
    override suspend fun search(query: String) = SearchResults()
    override fun setSongStarred(songId: String, starred: Boolean) = Unit
}

private object IdlePlayerController : PlayerController {
    override val state: StateFlow<PlayerState> = MutableStateFlow(PlayerState())
    override fun play(songs: List<Song>, startIndex: Int, source: PlaybackSource?, shuffle: Boolean) = Unit
    override fun togglePlayPause() = Unit
    override fun next() = Unit
    override fun previous() = Unit
    override fun seekTo(position: Duration) = Unit
    override fun setShuffle(enabled: Boolean) = Unit
    override fun cycleRepeat() = Unit
    override fun skipTo(occurrenceId: Long) = Unit
    override fun playNext(song: Song) = Unit
    override fun addToQueue(song: Song) = Unit
    override fun moveUpcoming(occurrenceId: Long, offset: Int) = Unit
    override fun remove(occurrenceId: Long) = Unit
    override fun clearQueue() = Unit
}
