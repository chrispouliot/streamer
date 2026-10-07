package dev.streamer.app.settings

/** What album and playlist songs can be ordered by (display order only). */
enum class TrackSortField(val label: String, val defaultDescending: Boolean) {
    /** Playlist position: Navidrome appends new songs, so this is when they were added. */
    Added("Date added", defaultDescending = true),
    Favourited("Date favourited", defaultDescending = true),
    Track("Track number", defaultDescending = false),
    Title("Title", defaultDescending = false),
    Artist("Artist", defaultDescending = false),
    Duration("Duration", defaultDescending = false),
    ;

    companion object {
        val forPlaylists = listOf(Added, Favourited, Title, Artist)
        val forAlbums = listOf(Track, Title, Duration)
    }
}

data class TrackSort(val field: TrackSortField, val descending: Boolean = field.defaultDescending) {
    fun encode(): String = "${field.name}:${if (descending) "desc" else "asc"}"

    companion object {
        val AlbumDefault = TrackSort(TrackSortField.Track, descending = false)

        fun decode(stored: String): TrackSort? {
            val parts = stored.split(":")
            if (parts.size == 2) {
                val field = TrackSortField.entries.firstOrNull { it.name == parts[0] } ?: return null
                return TrackSort(field, parts[1] == "desc")
            }
            // Choices saved before sorts had a direction.
            return when (stored) {
                "RecentlyAdded" -> TrackSort(TrackSortField.Added, true)
                "PlaylistOrder" -> TrackSort(TrackSortField.Added, false)
                "RecentlyFavourited" -> TrackSort(TrackSortField.Favourited, true)
                "Title" -> TrackSort(TrackSortField.Title, false)
                "Artist" -> TrackSort(TrackSortField.Artist, false)
                else -> null
            }
        }
    }
}
