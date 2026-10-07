package dev.streamer.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Every cached row is scoped to a local account ID: different servers can use
// identical item IDs, and deleting an account cascades to its cached data.

@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey val id: String,
    val baseUrl: String,
    val username: String,
    val allowInsecureHttp: Boolean,
    val serverType: String?,
    val serverVersion: String?,
    val openSubsonic: Boolean,
    val active: Boolean,
)

private const val ACCOUNT_FK = "accountId"

@Entity(
    tableName = "song",
    primaryKeys = ["accountId", "id"],
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], [ACCOUNT_FK], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId", "albumId"), Index("accountId", "starred"), Index("accountId", "title")],
)
data class SongEntity(
    val accountId: String,
    val id: String,
    val title: String,
    val artist: String?,
    val artistId: String?,
    val album: String?,
    val albumId: String?,
    val durationSec: Int?,
    val track: Int?,
    val disc: Int?,
    val year: Int?,
    val coverArt: String?,
    val explicit: Boolean,
    val starred: Boolean,
    val suffix: String?,
    val contentType: String?,
    val bitRate: Int?,
    /** When the song was starred (server timestamp, ISO-8601), if starred. Added in schema v2. */
    @ColumnInfo(defaultValue = "NULL") val starredAt: String? = null,
)

@Entity(
    tableName = "album",
    primaryKeys = ["accountId", "id"],
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], [ACCOUNT_FK], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId", "artistId")],
)
data class AlbumEntity(
    val accountId: String,
    val id: String,
    val name: String,
    val artist: String?,
    val artistId: String?,
    val year: Int?,
    val songCount: Int?,
    val durationSec: Int?,
    val coverArt: String?,
    /** When the album was added to the server library (ISO-8601). Added in schema v5. */
    @ColumnInfo(defaultValue = "NULL") val created: String? = null,
)

/** The server's "recently added" album order. Separate so album upserts never disturb it. */
@Entity(
    tableName = "newest_album",
    primaryKeys = ["accountId", "rank"],
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], [ACCOUNT_FK], onDelete = ForeignKey.CASCADE)],
)
data class NewestAlbumEntity(val accountId: String, val rank: Int, val albumId: String)

/** Album track order as returned by the server. */
@Entity(
    tableName = "album_song",
    primaryKeys = ["accountId", "albumId", "position"],
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], [ACCOUNT_FK], onDelete = ForeignKey.CASCADE)],
)
data class AlbumSongEntity(val accountId: String, val albumId: String, val position: Int, val songId: String)

@Entity(
    tableName = "artist",
    primaryKeys = ["accountId", "id"],
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], [ACCOUNT_FK], onDelete = ForeignKey.CASCADE)],
)
data class ArtistEntity(
    val accountId: String,
    val id: String,
    val name: String,
    val albumCount: Int?,
    val coverArt: String?,
)

@Entity(
    tableName = "playlist",
    primaryKeys = ["accountId", "id"],
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], [ACCOUNT_FK], onDelete = ForeignKey.CASCADE)],
)
data class PlaylistEntity(
    val accountId: String,
    val id: String,
    val name: String,
    val comment: String?,
    val owner: String?,
    val isPublic: Boolean?,
    val songCount: Int?,
    val durationSec: Int?,
    val coverArt: String?,
    val changed: String?,
    /** Absent from the last successful playlist listing; kept for downloads (Phase 5). */
    val removedRemotely: Boolean,
)

/** One occurrence in a playlist, keyed by position so duplicate songs are preserved. */
@Entity(
    tableName = "playlist_entry",
    primaryKeys = ["accountId", "playlistId", "position"],
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], [ACCOUNT_FK], onDelete = ForeignKey.CASCADE)],
)
data class PlaylistEntryEntity(val accountId: String, val playlistId: String, val position: Int, val songId: String)

/** Last successful refresh time per data set (e.g. "playlists", "album:<id>"). */
@Entity(
    tableName = "sync_state",
    primaryKeys = ["accountId", "key"],
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], [ACCOUNT_FK], onDelete = ForeignKey.CASCADE)],
)
data class SyncStateEntity(val accountId: String, val key: String, val syncedAtMillis: Long)

/** Local play history (schema v3); feeds "Recently played". */
@Entity(
    tableName = "play_history",
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], [ACCOUNT_FK], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId", "playedAtMillis")],
)
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val accountId: String,
    val songId: String,
    val playedAtMillis: Long,
)

/** Albums and playlists played from, latest play time per collection (schema v4). */
@Entity(
    tableName = "recent_collection",
    primaryKeys = ["accountId", "kind", "collectionId"],
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], [ACCOUNT_FK], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId", "playedAtMillis")],
)
data class RecentCollectionEntity(val accountId: String, val kind: String, val collectionId: String, val playedAtMillis: Long) {
    companion object {
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"
    }
}

data class RecentAlbum(val playedAtMillis: Long, @Embedded val album: AlbumEntity)
data class RecentPlaylist(val playedAtMillis: Long, @Embedded val playlist: PlaylistEntity)

/**
 * Why a downloaded song is kept (schema v6). [owner] is "song" for an individual
 * download, or "album:<id>" / "playlist:<id>" for a collection. A song's file
 * is deleted only when no references remain.
 */
@Entity(
    tableName = "download_ref",
    primaryKeys = ["accountId", "songId", "owner"],
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], [ACCOUNT_FK], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId", "owner")],
)
data class DownloadRefEntity(val accountId: String, val songId: String, val owner: String)

/** An album or playlist the user downloaded (schema v6). */
@Entity(
    tableName = "downloaded_collection",
    primaryKeys = ["accountId", "kind", "collectionId"],
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], [ACCOUNT_FK], onDelete = ForeignKey.CASCADE)],
)
data class DownloadedCollectionEntity(
    val accountId: String,
    val kind: String,
    val collectionId: String,
    /** Playlists only: follow server changes automatically. */
    val keepUpdated: Boolean,
    val addedAtMillis: Long,
    /** The user stopped it partway (or cancelled one of its songs): keep finished songs, add nothing until resumed. Schema v7. */
    @ColumnInfo(defaultValue = "0") val stopped: Boolean = false,
) {
    val owner: String get() = "$kind:$collectionId"
}

/** What's needed to notice that a server playlist changed since it was cached. */
data class PlaylistMarker(val id: String, val changed: String?, val songCount: Int?)

data class PositionedSong(val position: Int, @Embedded val song: SongEntity)
