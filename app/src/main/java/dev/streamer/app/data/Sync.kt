package dev.streamer.app.data

import java.time.Instant

/** A screen's worth of library data that can be refreshed from the server. */
sealed interface SyncTarget {
    /** Playlists, recently added albums and favourites. */
    data object Home : SyncTarget
    data object Playlists : SyncTarget
    data object Albums : SyncTarget
    data object Artists : SyncTarget
    data object Songs : SyncTarget
    data object Favourites : SyncTarget
    data class Album(val id: String) : SyncTarget
    data class Artist(val id: String) : SyncTarget
    data class Playlist(val id: String) : SyncTarget
}

/**
 * Freshness of cached data. [lastUpdated] is the oldest successful refresh
 * among the target's parts (null if one has never been fetched); [error] is
 * set when the latest attempt failed or the account needs to reconnect;
 * [connection] says why nothing can be refreshed right now, if so.
 */
data class SyncStatus(val lastUpdated: Instant? = null, val error: String? = null, val connection: Connection = Connection.Online)
