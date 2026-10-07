package dev.streamer.app.data.repository

import android.database.SQLException
import dev.streamer.app.data.Connection
import dev.streamer.app.data.LibraryRepository
import dev.streamer.app.data.SyncStatus
import dev.streamer.app.data.SyncTarget
import dev.streamer.app.data.UserMessages
import dev.streamer.app.data.account.AccountRepository
import dev.streamer.app.data.account.SessionState
import dev.streamer.app.data.local.AlbumSongEntity
import dev.streamer.app.data.local.LibraryDao
import dev.streamer.app.data.local.PlayHistoryEntity
import dev.streamer.app.data.local.PlaylistEntryEntity
import dev.streamer.app.data.local.RecentCollectionEntity
import dev.streamer.app.data.local.SyncStateEntity
import dev.streamer.app.data.remote.ApiError
import dev.streamer.app.data.remote.ServerAuth
import dev.streamer.app.data.remote.SubsonicClient
import dev.streamer.app.data.remote.retryTransient
import dev.streamer.app.model.AlbumDetail
import dev.streamer.app.model.ArtistDetail
import dev.streamer.app.model.PlaylistDetail
import dev.streamer.app.model.PlaylistEntry
import dev.streamer.app.model.RecentCollection
import dev.streamer.app.model.SearchResults
import dev.streamer.app.model.Song
import dev.streamer.app.playback.PlaybackSource
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Online-first library backed by the Navidrome (Subsonic) API with a Room
 * cache. Flows render cached rows immediately; while collected, stale data is
 * refreshed in the background and replaced transactionally only after a
 * complete, successful response, so failures never wipe the last good copy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavidromeLibraryRepository(
    private val accounts: AccountRepository,
    private val dao: LibraryDao,
    private val client: SubsonicClient,
    private val messages: UserMessages,
    private val scope: CoroutineScope,
    /** Offline-only mode: cached data only, no server requests. */
    private val offlineOnly: StateFlow<Boolean> = MutableStateFlow(false),
    override val connection: StateFlow<Connection> = MutableStateFlow(Connection.Online),
    /** Told whether requests reach the server, so the UI can say when they don't. */
    private val onServerReachable: (Boolean) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : LibraryRepository {

    private val activeAccountId: Flow<String?> =
        accounts.state.map { (it as? SessionState.Active)?.account?.id }.distinctUntilChanged()

    /** Account-scoped observation; [refresh] runs each time a collector starts for an account. */
    private fun <T> observe(empty: T, refresh: (() -> Unit)? = null, query: (String) -> Flow<T>): Flow<T> =
        activeAccountId.flatMapLatest { acc ->
            if (acc == null) flowOf(empty) else query(acc).onStart { refresh?.invoke() }
        }.flowOn(Dispatchers.Default) // Mapping thousands of rows stays off the main thread.

    override val playlists = observe(emptyList(), { refresh(PLAYLISTS, 5.minutes, ::syncPlaylists) }) { acc ->
        dao.observePlaylists(acc).map { list -> list.map { it.toModel() } }
    }

    override val recentlyAddedAlbums = observe(emptyList(), { refresh(NEWEST, 10.minutes, ::syncNewest) }) { acc ->
        dao.observeNewestAlbums(acc).map { list -> list.map { it.toModel() } }
    }

    override val recentlyPlayed: Flow<List<Song>> = observe(emptyList()) { acc ->
        dao.observeRecentlyPlayed(acc, limit = 20).map { list -> list.map { it.toModel() } }
    }

    override val recentCollections: Flow<List<RecentCollection>> = observe(emptyList()) { acc ->
        combine(dao.observeRecentAlbums(acc, RECENT_COLLECTIONS), dao.observeRecentPlaylists(acc, RECENT_COLLECTIONS)) { albums, playlists ->
            (albums.map { it.playedAtMillis to RecentCollection.Album(it.album.toModel()) } +
                playlists.map { it.playedAtMillis to RecentCollection.Playlist(it.playlist.toModel()) })
                .sortedByDescending { it.first }
                .take(RECENT_COLLECTIONS)
                .map { it.second }
        }
    }

    override fun recordPlayed(song: Song, source: PlaybackSource?) {
        val acc = song.artwork.accountId ?: (accounts.state.value as? SessionState.Active)?.account?.id ?: return
        val now = clock()
        val collection = when (source) {
            is PlaybackSource.Album -> RecentCollectionEntity(acc, RecentCollectionEntity.ALBUM, source.id, now)
            is PlaybackSource.Playlist -> RecentCollectionEntity(acc, RecentCollectionEntity.PLAYLIST, source.id, now)
            else -> null
        }
        scope.launch(Dispatchers.Default) {
            try {
                dao.insertHistory(PlayHistoryEntity(accountId = acc, songId = song.id, playedAtMillis = now))
                collection?.let { dao.upsertRecentCollection(it) }
                dao.pruneHistory(acc, keep = 500)
            } catch (e: SQLException) {
                // Account removed meanwhile.
            }
        }
    }

    override val albums = observe(emptyList(), { refresh(ALBUMS, 30.minutes, ::syncAllAlbums) }) { acc ->
        dao.observeAlbums(acc).map { list -> list.map { it.toModel() } }
    }

    override val artists = observe(emptyList(), { refresh(ARTISTS, 30.minutes, ::syncArtists) }) { acc ->
        dao.observeArtists(acc).map { list -> list.map { it.toModel() } }
    }

    override val songs = observe(emptyList(), { refresh(SONGS, 1.hours, ::syncAllSongs) }) { acc ->
        dao.observeSongs(acc).map { list -> list.map { it.toModel() } }
    }

    override val starredSongIds = observe(emptySet(), { refresh(STARRED, 5.minutes, ::syncStarred) }) { acc ->
        dao.observeStarredSongIds(acc).map { it.toSet() }
    }

    override val starredSongs = observe(emptyList(), { refresh(STARRED, 5.minutes, ::syncStarred) }) { acc ->
        dao.observeStarredSongs(acc).map { list -> list.map { it.toModel() } }
    }

    override fun album(id: String): Flow<AlbumDetail?> {
        val key = "album:$id"
        return observe(null, { refresh(key, 10.minutes) { acc, auth -> syncAlbum(acc, auth, id) } }) { acc ->
            combine(dao.observeAlbum(acc, id), dao.observeAlbumSongs(acc, id)) { album, songs ->
                album?.let { a ->
                    AlbumDetail(
                        summary = a.toModel(),
                        duration = a.durationSec?.seconds,
                        songs = songs.map { it.song.toModel() },
                    )
                }
            }.awaitFirstAttempt(acc, key) { it == null || it.songs.isEmpty() }
                .flatMapLatest { detail -> withArtistArtwork(acc, detail) }
        }
    }

    /** Adds the album artist's picture from the cache, fetching that artist if it isn't cached yet. */
    private fun withArtistArtwork(acc: String, detail: AlbumDetail?): Flow<AlbumDetail?> {
        val artistId = detail?.summary?.artistId ?: return flowOf(detail)
        return dao.observeArtist(acc, artistId)
            .map { artist -> detail.copy(artistArtwork = artist?.toModel()?.artwork) }
            .onStart { refresh("artist:$artistId", 10.minutes) { a, auth -> syncArtist(a, auth, artistId) } }
    }

    override fun artist(id: String): Flow<ArtistDetail?> {
        val key = "artist:$id"
        return observe(null, { refresh(key, 10.minutes) { acc, auth -> syncArtist(acc, auth, id) } }) { acc ->
            combine(dao.observeArtist(acc, id), dao.observeAlbumsByArtist(acc, id)) { artist, albums ->
                artist?.let { ArtistDetail(it.toModel(), albums.map { a -> a.toModel() }) }
            }.awaitFirstAttempt(acc, key) { it == null || it.albums.isEmpty() }
        }
    }

    override fun playlist(id: String): Flow<PlaylistDetail?> {
        val key = "playlist:$id"
        return observe(null, { refresh(key, 2.minutes) { acc, auth -> syncPlaylist(acc, auth, id) } }) { acc ->
            combine(dao.observePlaylist(acc, id), dao.observePlaylistEntries(acc, id)) { playlist, entries ->
                playlist?.takeUnless { it.removedRemotely }?.let { p ->
                    PlaylistDetail(p.toModel(), entries.map { PlaylistEntry(it.position, it.song.toModel()) })
                }
            }.awaitFirstAttempt(acc, key) { it == null || it.entries.isEmpty() }
        }
    }

    override suspend fun search(query: String): SearchResults {
        val q = query.trim()
        if (q.isEmpty()) return SearchResults()
        val current = accounts.currentAuth()?.takeUnless { offlineOnly.value }
        if (current != null) {
            val (account, auth) = current
            try {
                val r = client.search(auth, q, artistCount = 10, albumCount = 20, songCount = 40)
                val acc = account.id
                val songs = r.song.songEntities(acc)
                val albums = r.album.map { it.toEntity(acc) }
                val artists = r.artist.map { it.toEntity(acc) }
                dao.upsertSongs(songs)
                dao.upsertAlbums(albums)
                dao.upsertArtists(artists)
                return SearchResults(
                    songs = songs.map { it.toModel() },
                    albums = albums.map { it.toModel() },
                    artists = artists.map { it.toModel() },
                    // Playlists aren't covered by search3; filter the cached list.
                    playlists = dao.searchPlaylists(acc, likePattern(q)).map { it.toModel() },
                )
            } catch (e: ApiError.Auth) {
                accounts.reportAuthFailure(e)
            } catch (e: ApiError) {
                // Fall back to cached metadata below.
            }
        }
        return searchCache(q)
    }

    private suspend fun searchCache(q: String): SearchResults {
        val acc = (accounts.state.value as? SessionState.Active)?.account?.id ?: return SearchResults(fromCache = true)
        val pattern = likePattern(q)
        return SearchResults(
            songs = dao.searchSongs(acc, pattern, 40).map { it.toModel() },
            albums = dao.searchAlbums(acc, pattern, 20).map { it.toModel() },
            artists = dao.searchArtists(acc, pattern, 10).map { it.toModel() },
            playlists = dao.searchPlaylists(acc, pattern).map { it.toModel() },
            fromCache = true,
        )
    }

    override fun setSongStarred(songId: String, starred: Boolean) {
        if (offlineOnly.value) {
            messages.post("Offline mode is on, so favourites can't be changed right now.")
            return
        }
        val current = accounts.currentAuth()
        if (current == null) {
            messages.post("Not connected to your server, so favourites can't be changed right now.")
            return
        }
        val (account, auth) = current
        scope.launch(Dispatchers.Default) {
            // Optimistic; the next favourites sync replaces the timestamp with the server's.
            dao.setSongStarred(account.id, songId, starred, if (starred) java.time.Instant.ofEpochMilli(clock()).toString() else null)
            try {
                retryTransient { client.setStarred(auth, songId, starred) }
                // Rule-based playlists (e.g. "loved songs") change on the server when favourites do.
                dao.invalidatePlaylistDetails(account.id)
            } catch (e: ApiError) {
                dao.setSongStarred(account.id, songId, !starred, null)
                if (e is ApiError.Auth) accounts.reportAuthFailure(e)
                messages.post("Couldn't update favourite: ${e.message}")
            }
        }
    }

    // --- Refresh machinery ---

    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Keys ("<account>:<key>") whose first refresh attempt this session has finished (any outcome). */
    private val attempted = MutableStateFlow<Set<String>>(emptySet())

    private fun markAttempted(acc: String, key: String) = attempted.update { it + "$acc:$key" }

    /**
     * Suppresses emissions that only mean "not cached yet" until the first
     * refresh attempt for [key] has finished, so screens show loading rather
     * than "not found" or an empty list while the first fetch is running.
     */
    private fun <T> Flow<T>.awaitFirstAttempt(acc: String, key: String, isIncomplete: (T) -> Boolean): Flow<T> =
        combine(attempted.map { "$acc:$key" in it }.distinctUntilChanged()) { value, done -> value to done }
            .transform { (value, done) -> if (done || !isIncomplete(value)) emit(value) }

    /** Latest failure per "<account>:<key>"; cleared by a later success. */
    private val errors = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Starts a background refresh of [key] if it is older than [maxAge] and not already running. */
    private fun refresh(key: String, maxAge: Duration, sync: suspend (acc: String, auth: ServerAuth) -> Unit) {
        // No attempt without a network; cached data is shown instead.
        val current = accounts.currentAuth()?.takeUnless { offlineOnly.value || connection.value == Connection.NoNetwork }
        val accountId = current?.first?.id ?: (accounts.state.value as? SessionState.Active)?.account?.id
        if (current == null) {
            // Signed out or awaiting reauthentication: cached data only.
            accountId?.let { markAttempted(it, key) }
            return
        }
        val (account, auth) = current
        val flightKey = "${account.id}:$key"
        if (!inFlight.add(flightKey)) return
        scope.launch(Dispatchers.Default) {
            try {
                val last = dao.syncedAt(account.id, key)
                if (last != null && clock() - last < maxAge.inWholeMilliseconds) return@launch
                runSync(account.id, auth, key, sync)?.let {
                    // The connection banner already explains an unreachable server.
                    if (connection.value == Connection.Online) messages.post("Couldn't refresh from your server. $it Showing saved data.")
                }
            } finally {
                inFlight.remove(flightKey)
                markAttempted(account.id, key)
            }
        }
    }

    /** Runs one sync, recording success or failure. Returns the user message on failure. */
    private suspend fun runSync(acc: String, auth: ServerAuth, key: String, sync: suspend (String, ServerAuth) -> Unit): String? {
        val errorKey = "$acc:$key"
        return try {
            retryTransient { sync(acc, auth) }
            onServerReachable(true)
            dao.upsertSyncState(SyncStateEntity(acc, key, clock()))
            errors.update { it - errorKey }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            if (e is ApiError.Auth) accounts.reportAuthFailure(e)
            onServerReachable(e !is ApiError.Unreachable && e !is ApiError.Timeout)
            val message = e.message ?: "Couldn't refresh."
            errors.update { it + (errorKey to message) }
            if (e is ApiError.Auth) null else message
        } catch (e: SQLException) {
            null // The account was removed (signed out) while this refresh was running.
        }
    }

    private fun keysFor(target: SyncTarget): List<String> = when (target) {
        SyncTarget.Home -> listOf(PLAYLISTS, NEWEST, STARRED)
        SyncTarget.Playlists -> listOf(PLAYLISTS)
        SyncTarget.Albums -> listOf(ALBUMS)
        SyncTarget.Artists -> listOf(ARTISTS)
        SyncTarget.Songs -> listOf(SONGS)
        SyncTarget.Favourites -> listOf(STARRED)
        is SyncTarget.Album -> listOf("album:${target.id}")
        is SyncTarget.Artist -> listOf("artist:${target.id}")
        is SyncTarget.Playlist -> listOf("playlist:${target.id}")
    }

    private fun syncFor(key: String): suspend (String, ServerAuth) -> Unit = when {
        key == PLAYLISTS -> ::syncPlaylists
        key == NEWEST -> ::syncNewest
        key == ALBUMS -> ::syncAllAlbums
        key == ARTISTS -> ::syncArtists
        key == SONGS -> ::syncAllSongs
        key == STARRED -> ::syncStarred
        key.startsWith("album:") -> { acc, auth -> syncAlbum(acc, auth, key.removePrefix("album:")) }
        key.startsWith("artist:") -> { acc, auth -> syncArtist(acc, auth, key.removePrefix("artist:")) }
        key.startsWith("playlist:") -> { acc, auth -> syncPlaylist(acc, auth, key.removePrefix("playlist:")) }
        else -> error("Unknown sync key $key")
    }

    override fun syncStatus(target: SyncTarget): Flow<SyncStatus> {
        val keys = keysFor(target)
        return observe(SyncStatus()) { acc ->
            combine(dao.observeSyncStates(acc, keys), errors, accounts.state, connection) { states, errs, session, conn ->
                val times = keys.map { k -> states.firstOrNull { it.key == k }?.syncedAtMillis }
                SyncStatus(
                    lastUpdated = if (times.any { it == null }) null else Instant.ofEpochMilli(times.filterNotNull().min()),
                    error = if (conn != Connection.Online) null
                    else (session as? SessionState.Active)?.reauthReason ?: keys.firstNotNullOfOrNull { errs["$acc:$it"] },
                    connection = conn,
                )
            }
        }
    }

    override suspend fun refresh(target: SyncTarget): String? {
        if (offlineOnly.value) return OFFLINE_MESSAGE
        if (connection.value == Connection.NoNetwork) return "There's no network connection."
        val (account, auth) = accounts.currentAuth()
            ?: return (accounts.state.value as? SessionState.Active)?.reauthReason ?: "Not connected to your server."
        return withContext(Dispatchers.Default) {
            keysFor(target).map { key -> async { runSync(account.id, auth, key, syncFor(key)) } }.awaitAll().firstOrNull { it != null }
        }
    }

    /**
     * Refreshes the playlist list. Playlists whose server change time or song
     * count differ from the cached copy are marked stale; those already viewed
     * are refetched in the background so opening them shows current songs (for
     * example playlists rewritten daily by server-side scripts).
     */
    private suspend fun syncPlaylists(acc: String, auth: ServerAuth) {
        val before = dao.playlistMarkers(acc).associateBy { it.id }
        val fresh = client.playlists(auth).map { it.toEntity(acc) }
        dao.replacePlaylists(acc, fresh)
        val toPrefetch = mutableListOf<String>()
        for (p in fresh) {
            val old = before[p.id] ?: continue // New to this device: fetched when first opened.
            if (old.changed == p.changed && old.songCount == p.songCount) continue
            val key = "playlist:${p.id}"
            val wasFetched = dao.syncedAt(acc, key) != null
            dao.deleteSyncState(acc, key)
            if (wasFetched) toPrefetch += p.id
        }
        if (toPrefetch.isNotEmpty()) {
            scope.launch(Dispatchers.Default) {
                val gate = Semaphore(2)
                coroutineScopeAll(toPrefetch) { id ->
                    gate.withPermit {
                        val key = "playlist:$id"
                        if (inFlight.add("$acc:$key")) {
                            try {
                                runSync(acc, auth, key, syncFor(key))
                            } finally {
                                inFlight.remove("$acc:$key")
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun syncPlaylist(acc: String, auth: ServerAuth, id: String) {
        val dto = try {
            client.playlist(auth, id)
        } catch (e: ApiError.NotFound) {
            dao.markPlaylistRemoved(acc, id)
            return
        }
        val songs = dto.entry.songEntities(acc)
        dao.replacePlaylist(
            acc,
            dto.toEntity(acc),
            songs.distinctBy { it.id },
            songs.mapIndexed { i, s -> PlaylistEntryEntity(acc, id, i, s.id) },
        )
    }

    private suspend fun syncNewest(acc: String, auth: ServerAuth) {
        dao.replaceNewest(acc, client.albumList(auth, "newest", size = 30, offset = 0).map { it.toEntity(acc) })
    }

    private suspend fun syncAllAlbums(acc: String, auth: ServerAuth) {
        dao.replaceAllAlbums(acc, pageAll(PAGE) { offset -> client.albumList(auth, "alphabeticalByName", PAGE, offset) }.map { it.toEntity(acc) })
    }

    private suspend fun syncAlbum(acc: String, auth: ServerAuth, id: String) {
        val dto = client.album(auth, id)
        val songs = dto.song.songEntities(acc)
        dao.replaceAlbum(acc, dto.toEntity(acc), songs, songs.mapIndexed { i, s -> AlbumSongEntity(acc, id, i, s.id) })
    }

    private suspend fun syncArtists(acc: String, auth: ServerAuth) {
        dao.replaceArtists(acc, client.artists(auth).map { it.toEntity(acc) })
    }

    private suspend fun syncArtist(acc: String, auth: ServerAuth, id: String) {
        val dto = client.artist(auth, id)
        dao.upsertArtists(listOf(dto.toEntity(acc)))
        dao.upsertAlbums(dto.album.map { it.toEntity(acc) })
    }

    private suspend fun syncStarred(acc: String, auth: ServerAuth) {
        dao.replaceStarred(acc, client.starred(auth).song.songEntities(acc))
    }

    /**
     * Builds the full song list. Navidrome returns every song for an empty
     * search3 query; if a server returns none while albums exist, fall back to
     * fetching albums with bounded concurrency.
     */
    private suspend fun syncAllSongs(acc: String, auth: ServerAuth) {
        val songs = pageAll(PAGE) { offset ->
            client.search(auth, "", artistCount = 0, albumCount = 0, songCount = PAGE, songOffset = offset).song
        }
        if (songs.isNotEmpty()) {
            dao.upsertSongs(songs.songEntities(acc))
            return
        }
        val gate = Semaphore(4)
        coroutineScopeAll(dao.albumIds(acc)) { albumId -> gate.withPermit { syncAlbum(acc, auth, albumId) } }
    }

    private suspend fun coroutineScopeAll(ids: List<String>, block: suspend (String) -> Unit) =
        coroutineScope { ids.map { id -> async { block(id) } }.awaitAll() }

    /** Fetches pages until a short page, with a hard cap on the number of requests. */
    private suspend fun <T> pageAll(pageSize: Int, fetch: suspend (offset: Int) -> List<T>): List<T> {
        val all = ArrayList<T>()
        repeat(MAX_PAGES) { page ->
            val items = fetch(page * pageSize)
            all += items
            if (items.size < pageSize) return all
        }
        return all
    }

    private fun likePattern(q: String) = "%$q%"

    private companion object {
        const val PAGE = 500
        const val OFFLINE_MESSAGE = "Offline mode is on. Turn it off in Settings to refresh."
        const val RECENT_COLLECTIONS = 4
        const val MAX_PAGES = 200
        const val PLAYLISTS = "playlists"
        const val NEWEST = "newest"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        const val SONGS = "songs"
        const val STARRED = "starred"
    }
}
