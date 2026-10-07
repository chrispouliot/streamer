package dev.streamer.app.model

import kotlin.time.Duration

/**
 * Artwork reference. [coverArtId] is the server's cover-art ID when one exists;
 * [seed] is a stable key used to derive fallback artwork and tint colours.
 */
data class Artwork(val coverArtId: String?, val seed: String)

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

data class SearchResults(
    val songs: List<Song> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
) {
    val isEmpty: Boolean
        get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
}
