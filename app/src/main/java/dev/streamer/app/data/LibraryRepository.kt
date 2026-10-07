package dev.streamer.app.data

import dev.streamer.app.model.AlbumDetail
import dev.streamer.app.model.AlbumSummary
import dev.streamer.app.model.ArtistDetail
import dev.streamer.app.model.ArtistSummary
import dev.streamer.app.model.PlaylistDetail
import dev.streamer.app.model.PlaylistSummary
import dev.streamer.app.model.SearchResults
import dev.streamer.app.model.Song
import kotlinx.coroutines.flow.Flow

/**
 * Read-only view of the music library for the UI. Phase 2 backs this with the
 * Navidrome API and the Room cache; refresh/stale state will be added then.
 */
interface LibraryRepository {
    val playlists: Flow<List<PlaylistSummary>>
    val recentlyAddedAlbums: Flow<List<AlbumSummary>>
    val recentlyPlayed: Flow<List<Song>>
    val albums: Flow<List<AlbumSummary>>
    val artists: Flow<List<ArtistSummary>>
    val songs: Flow<List<Song>>

    /** IDs of songs marked as favourite (starred) on the server. */
    val starredSongIds: Flow<Set<String>>

    /** Emits null when the item is unknown. */
    fun album(id: String): Flow<AlbumDetail?>
    fun artist(id: String): Flow<ArtistDetail?>
    fun playlist(id: String): Flow<PlaylistDetail?>

    suspend fun search(query: String): SearchResults

    /** Stars or unstars a song. Updates [starredSongIds] immediately and reverts if the server rejects it. */
    fun setSongStarred(songId: String, starred: Boolean)
}
