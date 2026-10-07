package dev.streamer.app.data

import dev.streamer.app.model.AlbumDetail
import dev.streamer.app.model.AlbumSummary
import dev.streamer.app.model.ArtistDetail
import dev.streamer.app.model.ArtistSummary
import dev.streamer.app.model.PlaylistDetail
import dev.streamer.app.model.PlaylistSummary
import dev.streamer.app.model.RecentCollection
import dev.streamer.app.model.SearchResults
import dev.streamer.app.model.Song
import dev.streamer.app.playback.PlaybackSource
import kotlinx.coroutines.flow.Flow

/**
 * The music library for the UI. Flows emit cached data immediately and the
 * implementation refreshes stale data from the server in the background while
 * they are collected. Server playlists are read-only.
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
    val starredSongs: Flow<List<Song>>

    /** Emits null when the item is unknown. */
    fun album(id: String): Flow<AlbumDetail?>
    fun artist(id: String): Flow<ArtistDetail?>
    fun playlist(id: String): Flow<PlaylistDetail?>

    suspend fun search(query: String): SearchResults

    /** Albums and playlists most recently played from, newest first. */
    val recentCollections: Flow<List<RecentCollection>>

    /** Records that a song started playing, and the album/playlist it was played from (local history only). */
    fun recordPlayed(song: Song, source: PlaybackSource?)

    fun syncStatus(target: SyncTarget): Flow<SyncStatus>

    /** Refreshes [target] from the server now, whatever its age. Returns a user message on failure, null on success. */
    suspend fun refresh(target: SyncTarget): String?

    /** Stars or unstars a song. Updates [starredSongIds] immediately and reverts if the server rejects it. */
    fun setSongStarred(songId: String, starred: Boolean)
}
