package dev.streamer.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM account WHERE active = 1 LIMIT 1")
    suspend fun active(): AccountEntity?

    @Query("SELECT * FROM account WHERE baseUrl = :baseUrl AND username = :username LIMIT 1")
    suspend fun find(baseUrl: String, username: String): AccountEntity?

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("UPDATE account SET active = 0")
    suspend fun deactivateAll()

    @Transaction
    suspend fun activate(account: AccountEntity) {
        deactivateAll()
        upsert(account.copy(active = true))
    }

    /** Cascades to all of the account's cached data. */
    @Query("DELETE FROM account WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface LibraryDao {
    // --- Observation ---

    @Query("SELECT * FROM playlist WHERE accountId = :acc AND removedRemotely = 0 ORDER BY name COLLATE NOCASE")
    fun observePlaylists(acc: String): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist WHERE accountId = :acc AND id = :id")
    fun observePlaylist(acc: String, id: String): Flow<PlaylistEntity?>

    @Query(
        """SELECT e.position AS position, s.* FROM playlist_entry e
           JOIN song s ON s.accountId = e.accountId AND s.id = e.songId
           WHERE e.accountId = :acc AND e.playlistId = :playlistId ORDER BY e.position""",
    )
    fun observePlaylistEntries(acc: String, playlistId: String): Flow<List<PositionedSong>>

    @Query(
        """SELECT a.* FROM newest_album n JOIN album a ON a.accountId = n.accountId AND a.id = n.albumId
           WHERE n.accountId = :acc ORDER BY n.rank""",
    )
    fun observeNewestAlbums(acc: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM album WHERE accountId = :acc ORDER BY name COLLATE NOCASE")
    fun observeAlbums(acc: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM album WHERE accountId = :acc AND id = :id")
    fun observeAlbum(acc: String, id: String): Flow<AlbumEntity?>

    @Query(
        """SELECT a.position AS position, s.* FROM album_song a
           JOIN song s ON s.accountId = a.accountId AND s.id = a.songId
           WHERE a.accountId = :acc AND a.albumId = :albumId ORDER BY a.position""",
    )
    fun observeAlbumSongs(acc: String, albumId: String): Flow<List<PositionedSong>>

    @Query("SELECT * FROM album WHERE accountId = :acc AND artistId = :artistId ORDER BY year DESC, name COLLATE NOCASE")
    fun observeAlbumsByArtist(acc: String, artistId: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM artist WHERE accountId = :acc ORDER BY name COLLATE NOCASE")
    fun observeArtists(acc: String): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artist WHERE accountId = :acc AND id = :id")
    fun observeArtist(acc: String, id: String): Flow<ArtistEntity?>

    @Query("SELECT * FROM song WHERE accountId = :acc ORDER BY title COLLATE NOCASE")
    fun observeSongs(acc: String): Flow<List<SongEntity>>

    /** Most recently starred first; songs without a timestamp last. */
    @Query("SELECT * FROM song WHERE accountId = :acc AND starred = 1 ORDER BY starredAt IS NULL, starredAt DESC, title COLLATE NOCASE")
    fun observeStarredSongs(acc: String): Flow<List<SongEntity>>

    @Query("SELECT id FROM song WHERE accountId = :acc AND starred = 1")
    fun observeStarredSongIds(acc: String): Flow<List<String>>

    /** Distinct songs, most recently played first. */
    @Query(
        """SELECT s.* FROM song s
           JOIN (SELECT songId, MAX(playedAtMillis) AS lastPlayed FROM play_history WHERE accountId = :acc GROUP BY songId) h
             ON h.songId = s.id
           WHERE s.accountId = :acc ORDER BY h.lastPlayed DESC LIMIT :limit""",
    )
    fun observeRecentlyPlayed(acc: String, limit: Int): Flow<List<SongEntity>>

    @androidx.room.Insert
    suspend fun insertHistory(entry: PlayHistoryEntity)

    @Upsert
    suspend fun upsertRecentCollection(entry: RecentCollectionEntity)

    @Query(
        """SELECT r.playedAtMillis AS playedAtMillis, a.* FROM recent_collection r
           JOIN album a ON a.accountId = r.accountId AND a.id = r.collectionId
           WHERE r.accountId = :acc AND r.kind = 'album' ORDER BY r.playedAtMillis DESC LIMIT :limit""",
    )
    fun observeRecentAlbums(acc: String, limit: Int): Flow<List<RecentAlbum>>

    @Query(
        """SELECT r.playedAtMillis AS playedAtMillis, p.* FROM recent_collection r
           JOIN playlist p ON p.accountId = r.accountId AND p.id = r.collectionId
           WHERE r.accountId = :acc AND r.kind = 'playlist' AND p.removedRemotely = 0
           ORDER BY r.playedAtMillis DESC LIMIT :limit""",
    )
    fun observeRecentPlaylists(acc: String, limit: Int): Flow<List<RecentPlaylist>>

    @Query(
        """DELETE FROM play_history WHERE accountId = :acc AND rowId NOT IN
           (SELECT rowId FROM play_history WHERE accountId = :acc ORDER BY playedAtMillis DESC LIMIT :keep)""",
    )
    suspend fun pruneHistory(acc: String, keep: Int)

    // --- Local search over cached metadata (used offline) ---

    @Query("SELECT * FROM song WHERE accountId = :acc AND (title LIKE :pattern OR artist LIKE :pattern) ORDER BY title COLLATE NOCASE LIMIT :limit")
    suspend fun searchSongs(acc: String, pattern: String, limit: Int): List<SongEntity>

    @Query("SELECT * FROM album WHERE accountId = :acc AND (name LIKE :pattern OR artist LIKE :pattern) ORDER BY name COLLATE NOCASE LIMIT :limit")
    suspend fun searchAlbums(acc: String, pattern: String, limit: Int): List<AlbumEntity>

    @Query("SELECT * FROM artist WHERE accountId = :acc AND name LIKE :pattern ORDER BY name COLLATE NOCASE LIMIT :limit")
    suspend fun searchArtists(acc: String, pattern: String, limit: Int): List<ArtistEntity>

    @Query("SELECT * FROM playlist WHERE accountId = :acc AND removedRemotely = 0 AND name LIKE :pattern ORDER BY name COLLATE NOCASE")
    suspend fun searchPlaylists(acc: String, pattern: String): List<PlaylistEntity>

    // --- Sync bookkeeping ---

    @Query("SELECT syncedAtMillis FROM sync_state WHERE accountId = :acc AND `key` = :key")
    suspend fun syncedAt(acc: String, key: String): Long?

    @Upsert
    suspend fun upsertSyncState(state: SyncStateEntity)

    /** Makes every cached playlist detail stale, so the next visit refetches it. */
    @Query("DELETE FROM sync_state WHERE accountId = :acc AND `key` LIKE 'playlist:%'")
    suspend fun invalidatePlaylistDetails(acc: String)

    @Query("SELECT id FROM album WHERE accountId = :acc")
    suspend fun albumIds(acc: String): List<String>

    @Query("SELECT COUNT(*) FROM song WHERE accountId = :acc")
    suspend fun songCount(acc: String): Int

    // --- Writes ---

    @Upsert suspend fun upsertSongs(songs: List<SongEntity>)
    @Upsert suspend fun upsertAlbums(albums: List<AlbumEntity>)
    @Upsert suspend fun upsertArtists(artists: List<ArtistEntity>)
    @Upsert suspend fun upsertPlaylists(playlists: List<PlaylistEntity>)
    @Upsert suspend fun insertPlaylistEntries(entries: List<PlaylistEntryEntity>)
    @Upsert suspend fun insertAlbumSongs(entries: List<AlbumSongEntity>)

    @Query("UPDATE song SET starred = :starred, starredAt = :starredAt WHERE accountId = :acc AND id = :songId")
    suspend fun setSongStarred(acc: String, songId: String, starred: Boolean, starredAt: String?)

    @Query("UPDATE song SET starred = 0, starredAt = NULL WHERE accountId = :acc")
    suspend fun clearStarredSongs(acc: String)

    @Query("DELETE FROM newest_album WHERE accountId = :acc")
    suspend fun clearNewest(acc: String)

    @Upsert suspend fun insertNewest(entries: List<NewestAlbumEntity>)

    @Query("DELETE FROM playlist_entry WHERE accountId = :acc AND playlistId = :playlistId")
    suspend fun deletePlaylistEntries(acc: String, playlistId: String)

    @Query("DELETE FROM album_song WHERE accountId = :acc AND albumId = :albumId")
    suspend fun deleteAlbumSongs(acc: String, albumId: String)

    @Query("UPDATE playlist SET removedRemotely = 1 WHERE accountId = :acc AND id NOT IN (:keepIds)")
    suspend fun markPlaylistsRemovedExcept(acc: String, keepIds: List<String>)

    @Query("UPDATE playlist SET removedRemotely = 1 WHERE accountId = :acc")
    suspend fun markAllPlaylistsRemoved(acc: String)

    @Query("UPDATE playlist SET removedRemotely = 1 WHERE accountId = :acc AND id = :id")
    suspend fun markPlaylistRemoved(acc: String, id: String)

    @Query("DELETE FROM album WHERE accountId = :acc AND id NOT IN (:keepIds)")
    suspend fun deleteAlbumsExcept(acc: String, keepIds: List<String>)

    @Query("DELETE FROM artist WHERE accountId = :acc AND id NOT IN (:keepIds)")
    suspend fun deleteArtistsExcept(acc: String, keepIds: List<String>)

    // --- Transactional snapshot replacement: called only with a complete, successful response ---

    @Transaction
    suspend fun replacePlaylists(acc: String, playlists: List<PlaylistEntity>) {
        upsertPlaylists(playlists)
        if (playlists.isEmpty()) markAllPlaylistsRemoved(acc) else markPlaylistsRemovedExcept(acc, playlists.map { it.id })
    }

    @Transaction
    suspend fun replacePlaylist(acc: String, playlist: PlaylistEntity, songs: List<SongEntity>, entries: List<PlaylistEntryEntity>) {
        upsertPlaylists(listOf(playlist))
        upsertSongs(songs)
        deletePlaylistEntries(acc, playlist.id)
        insertPlaylistEntries(entries)
    }

    @Transaction
    suspend fun replaceAlbum(acc: String, album: AlbumEntity, songs: List<SongEntity>, order: List<AlbumSongEntity>) {
        upsertAlbums(listOf(album))
        upsertSongs(songs)
        deleteAlbumSongs(acc, album.id)
        insertAlbumSongs(order)
    }

    @Transaction
    suspend fun replaceNewest(acc: String, albums: List<AlbumEntity>) {
        upsertAlbums(albums)
        clearNewest(acc)
        insertNewest(albums.mapIndexed { i, a -> NewestAlbumEntity(acc, i, a.id) })
    }

    @Transaction
    suspend fun replaceAllAlbums(acc: String, albums: List<AlbumEntity>) {
        upsertAlbums(albums)
        deleteAlbumsExcept(acc, albums.map { it.id })
    }

    @Transaction
    suspend fun replaceArtists(acc: String, artists: List<ArtistEntity>) {
        upsertArtists(artists)
        deleteArtistsExcept(acc, artists.map { it.id })
    }

    @Transaction
    suspend fun replaceStarred(acc: String, songs: List<SongEntity>) {
        clearStarredSongs(acc)
        upsertSongs(songs)
    }
}
