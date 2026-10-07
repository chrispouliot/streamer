package dev.streamer.app.model

import java.time.Instant
import kotlin.time.Duration

/**
 * Artwork reference. [coverArtId] is the server's cover-art ID when one exists
 * and [accountId] the account it belongs to; [seed] is a stable key used to
 * derive fallback artwork and tint colours.
 */
data class Artwork(val coverArtId: String?, val seed: String, val accountId: String? = null)

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val album: String?,
    val albumId: String?,
    val duration: Duration?,
    val trackNumber: Int?,
    val artwork: Artwork,
    val explicit: Boolean = false,
    /** When the user favourited (starred) the song, if known. */
    val favouritedAt: Instant? = null,
)

data class AlbumSummary(
    val id: String,
    val name: String,
    val artist: String,
    val artistId: String?,
    val year: Int?,
    val songCount: Int?,
    val artwork: Artwork,
)

data class AlbumDetail(
    val summary: AlbumSummary,
    val duration: Duration?,
    val songs: List<Song>,
    /** The album artist's picture, when known. */
    val artistArtwork: Artwork? = null,
)

data class ArtistSummary(
    val id: String,
    val name: String,
    val albumCount: Int?,
    val artwork: Artwork,
)

data class ArtistDetail(
    val summary: ArtistSummary,
    val albums: List<AlbumSummary>,
)

data class PlaylistSummary(
    val id: String,
    val name: String,
    val owner: String?,
    val comment: String?,
    val songCount: Int?,
    val duration: Duration?,
    val artwork: Artwork,
)

/** One position in a playlist. The same song may occur more than once. */
data class PlaylistEntry(val position: Int, val song: Song)

data class PlaylistDetail(
    val summary: PlaylistSummary,
    val entries: List<PlaylistEntry>,
)

/** An album or playlist the user recently played from. */
sealed interface RecentCollection {
    data class Album(val album: AlbumSummary) : RecentCollection
    data class Playlist(val playlist: PlaylistSummary) : RecentCollection
}

data class SearchResults(
    val songs: List<Song> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    /** True when the server couldn't be reached and only cached metadata was searched. */
    val fromCache: Boolean = false,
) {
    val isEmpty: Boolean
        get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
}
