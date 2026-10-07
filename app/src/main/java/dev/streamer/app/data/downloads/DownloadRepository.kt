package dev.streamer.app.data.downloads

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import dev.streamer.app.data.LibraryRepository
import dev.streamer.app.data.NetworkPolicy
import dev.streamer.app.data.SyncTarget
import dev.streamer.app.data.account.AccountRepository
import dev.streamer.app.data.account.SessionState
import dev.streamer.app.data.images.ArtworkStore
import dev.streamer.app.data.local.DownloadRefEntity
import dev.streamer.app.data.local.DownloadedCollectionEntity
import dev.streamer.app.data.local.LibraryDao
import dev.streamer.app.data.remote.ServerAuth
import dev.streamer.app.data.repository.toModel
import dev.streamer.app.model.AlbumSummary
import dev.streamer.app.model.PlaylistSummary
import dev.streamer.app.model.Song
import dev.streamer.app.playback.MediaResolver
import dev.streamer.app.playback.MediaUris
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** A downloaded album or playlist, for the Downloads screen. */
sealed interface DownloadedCollection {
    val status: CollectionDownloadStatus

    data class Album(val album: AlbumSummary, override val status: CollectionDownloadStatus) : DownloadedCollection
    data class Playlist(val playlist: PlaylistSummary, val removedRemotely: Boolean, override val status: CollectionDownloadStatus) : DownloadedCollection
}

/**
 * Owns downloads: the permanent Media3 download store (never evicted, outside
 * temporary cache directories), which collection keeps each song, saved cover
 * art, and keeping downloaded playlists in step with the server.
 * Media3 download calls are made on the main thread.
 */
@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class, FlowPreview::class)
class DownloadRepository(
    private val context: Context,
    private val dao: LibraryDao,
    private val accounts: AccountRepository,
    private val library: LibraryRepository,
    resolver: MediaResolver,
    http: OkHttpClient,
    private val artwork: ArtworkStore,
    private val policy: NetworkPolicy,
    private val scope: CoroutineScope,
    /** The DownloadService class, to run downloads in the foreground. */
    private val serviceClass: Class<out DownloadService>,
    private val onPending: () -> Unit,
) {
    private val databaseProvider = StandaloneDatabaseProvider(context)

    val cache: Cache = SimpleCache(File(context.filesDir, "downloads"), NoOpCacheEvictor(), databaseProvider)

    val manager: DownloadManager = DownloadManager(
        context,
        databaseProvider,
        cache,
        ResolvingDataSource.Factory(OkHttpDataSource.Factory(http), resolver::original),
        Executors.newFixedThreadPool(3),
    ).apply { maxParallelDownloads = 3 }

    private val _downloads = MutableStateFlow<Map<String, SongDownload>>(emptyMap())

    /** Every known download by key ([MediaUris.originalKey]). */
    val downloads: StateFlow<Map<String, SongDownload>> = _downloads.asStateFlow()

    private val _waitingForNetwork = MutableStateFlow(false)
    val waitingForNetwork: StateFlow<Boolean> = _waitingForNetwork.asStateFlow()

    /** Serialises reference changes so concurrent updates can't release a song another keeps. */
    private val refsLock = Mutex()

    private val activeAccountId: Flow<String?> =
        accounts.state.map { (it as? SessionState.Active)?.account?.id }.distinctUntilChanged()

    init {
        manager.addListener(
            object : DownloadManager.Listener {
                override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                    _downloads.update { it + (download.request.id to download.toSongDownload()) }
                    if (download.state == Download.STATE_COMPLETED) onDownloadCompleted()
                }

                override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                    _downloads.update { it - download.request.id }
                }

                override fun onWaitingForRequirementsChanged(downloadManager: DownloadManager, waitingForRequirements: Boolean) {
                    _waitingForNetwork.value = waitingForRequirements
                }
            },
        )
        scope.launch { loadIndex() }
        scope.launch {
            policy.wifiOnlyDownloads.collect { wifiOnly ->
                manager.requirements = Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
            }
        }
        scope.launch {
            policy.offlineOnly.collect { offline -> if (offline) manager.pauseDownloads() else manager.resumeDownloads() }
        }
        // Keep-updated playlists follow their cached contents whenever those
        // change (refreshes, change detection); also finishes interrupted updates.
        scope.launch {
            activeAccountId.flatMapLatest { acc ->
                if (acc == null) return@flatMapLatest flowOf(null)
                dao.observeDownloadedCollections(acc).flatMapLatest { collections ->
                    val followed = collections.filter { it.keepUpdated }
                    if (followed.isEmpty()) flowOf(acc) else combine(followed.map { dao.observePlaylistSongIds(acc, it.collectionId) }) { acc }
                }
            }.debounce(2_000).collect { acc -> if (acc != null) reconcileAll() }
        }
    }

    private suspend fun loadIndex() {
        val loaded = withContext(Dispatchers.IO) {
            buildMap {
                manager.downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) put(cursor.download.request.id, cursor.download.toSongDownload())
                }
            }
        }
        _downloads.update { loaded + it } // Live updates win over the snapshot.
    }

    private fun Download.toSongDownload() = SongDownload(
        state = when (state) {
            Download.STATE_QUEUED, Download.STATE_RESTARTING -> SongDownloadState.Queued
            Download.STATE_DOWNLOADING -> SongDownloadState.Downloading
            Download.STATE_STOPPED -> SongDownloadState.Paused
            Download.STATE_COMPLETED -> SongDownloadState.Completed
            Download.STATE_FAILED -> SongDownloadState.Failed
            else -> SongDownloadState.Removing
        },
        percent = percentDownloaded.takeIf { it >= 0f }, // C.PERCENTAGE_UNSET (-1) when unknown.
        bytes = bytesDownloaded,
    )

    // --- Queries ---

    /** True when playback should go through the download store for this song reference. */
    fun hasDownload(uri: Uri): Boolean {
        val (acc, id) = MediaUris.parse(uri, MediaUris.SONG_SCHEME) ?: return false
        return downloads.value.containsKey(MediaUris.originalKey(acc, id))
    }

    fun isDownloaded(accountId: String, songId: String): Boolean =
        downloads.value[MediaUris.originalKey(accountId, songId)]?.state == SongDownloadState.Completed

    private fun songStates(acc: String, all: Map<String, SongDownload>): Map<String, SongDownload> {
        val prefix = "orig:$acc:"
        return all.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
    }

    // The lists below stay loaded while the app runs (small, in memory), so screens
    // start with real data instead of filling in after their first frame.

    /** IDs of the active account's completely downloaded songs. */
    val downloadedSongIds: StateFlow<Set<String>> = activeAccountId.flatMapLatest { acc ->
        if (acc == null) flowOf(emptySet()) else downloads.map { all -> songStates(acc, all).filterValues { it.state == SongDownloadState.Completed }.keys }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun collectionStatus(kind: String, id: String): Flow<CollectionDownloadStatus?> = activeAccountId.flatMapLatest { acc ->
        if (acc == null) return@flatMapLatest flowOf(null)
        val entries = if (kind == ALBUM) dao.observeAlbumSongIds(acc, id) else dao.observePlaylistSongIds(acc, id)
        combine(dao.observeDownloadedCollection(acc, kind, id), entries, dao.observeDownloadRefs(acc), downloads, waitingForNetwork) {
                collection, songIds, refs, all, waiting ->
            collection ?: return@combine null
            val kept = refs.filter { it.owner == collection.owner }.map { it.songId }.toSet()
            DownloadPlanner.status(songIds, kept, songStates(acc, all), waiting, collection.keepUpdated, collection.stopped)
        }
    }

    /** Downloaded albums and playlists with their status, newest first. */
    val downloadedCollections: StateFlow<List<DownloadedCollection>> = activeAccountId.flatMapLatest { acc ->
        if (acc == null) return@flatMapLatest flowOf(emptyList())
        combine(dao.observeDownloadedCollections(acc), dao.observeDownloadRefs(acc), downloads, waitingForNetwork) { c, r, d, w -> Snapshot(c, r, d, w) }
            .mapLatest { s ->
                val albums = dao.albums(acc, s.collections.filter { it.kind == ALBUM }.map { it.collectionId }).associateBy { it.id }
                val playlists = dao.playlists(acc, s.collections.filter { it.kind == PLAYLIST }.map { it.collectionId }).associateBy { it.id }
                s.collections.mapNotNull { c ->
                    val entries = if (c.kind == ALBUM) dao.albumSongIds(acc, c.collectionId) else dao.playlistSongIds(acc, c.collectionId)
                    val kept = s.refs.filter { it.owner == c.owner }.map { it.songId }.toSet()
                    val status = DownloadPlanner.status(entries, kept, songStates(acc, s.downloads), s.waiting, c.keepUpdated, c.stopped)
                    when (c.kind) {
                        ALBUM -> albums[c.collectionId]?.let { DownloadedCollection.Album(it.toModel(), status) }
                        else -> playlists[c.collectionId]?.let { DownloadedCollection.Playlist(it.toModel(), it.removedRemotely, status) }
                    }
                }
            }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private data class Snapshot(
        val collections: List<DownloadedCollectionEntity>,
        val refs: List<DownloadRefEntity>,
        val downloads: Map<String, SongDownload>,
        val waiting: Boolean,
    )

    /** Downloads still in progress (queued, downloading or paused), with their songs. */
    val activeDownloads: Flow<List<Pair<Song, SongDownload>>> = activeAccountId.flatMapLatest { acc ->
        if (acc == null) return@flatMapLatest flowOf(emptyList())
        downloads.map { all -> songStates(acc, all).filterValues { it.state in PENDING } }
            .distinctUntilChanged()
            .mapLatest { pending ->
                val songs = dao.songs(acc, pending.keys.toList()).associateBy { it.id }
                pending.mapNotNull { (id, d) -> songs[id]?.let { it.toModel() to d } }.sortedBy { it.first.title.lowercase() }
            }
    }

    /** Number of failed downloads for the active account. */
    val failedCount: Flow<Int> = activeAccountId.flatMapLatest { acc ->
        if (acc == null) flowOf(0) else downloads.map { all -> songStates(acc, all).values.count { it.state == SongDownloadState.Failed } }
    }.distinctUntilChanged()

    /** Songs downloaded on their own (not only as part of a collection). */
    val individualSongs: StateFlow<List<Song>> = activeAccountId.flatMapLatest { acc ->
        if (acc == null) flowOf(emptyList()) else dao.observeIndividuallyDownloadedSongs(acc).map { list -> list.map { it.toModel() } }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Every song with a download, completed or not, for offline browsing. */
    val allDownloadedSongs: Flow<List<Song>> = activeAccountId.flatMapLatest { acc ->
        if (acc == null) flowOf(emptyList()) else dao.observeDownloadedSongs(acc).map { list -> list.map { it.toModel() } }
    }

    suspend fun storageUsedBytes(): Long = withContext(Dispatchers.IO) { cache.cacheSpace + artwork.sizeBytes() }

    // --- Actions (each returns a user message on failure, null on success) ---

    private fun ready(): Pair<String, ServerAuth>? {
        if (policy.offlineOnly.value) return null
        val (account, auth) = accounts.currentAuth() ?: return null
        return account.id to auth
    }

    private fun unavailableMessage() =
        if (policy.offlineOnly.value) "Offline mode is on. Turn it off in Settings to download." else "Not connected to your server."

    suspend fun downloadSong(song: Song): String? {
        val (acc, auth) = ready() ?: return unavailableMessage()
        refsLock.withLock {
            dao.upsertDownloadRefs(listOf(DownloadRefEntity(acc, song.id, OWNER_SONG)))
            enqueue(acc, listOf(song.id))
        }
        saveArtwork(acc, auth, listOf(song.id), null)
        return null
    }

    suspend fun removeSong(songId: String) {
        val acc = activeId() ?: return
        refsLock.withLock {
            dao.deleteDownloadRefs(acc, OWNER_SONG, listOf(songId))
            release(acc, listOf(songId))
        }
    }

    /** Downloads an album or playlist; for playlists, [keepUpdated] follows server changes. */
    suspend fun downloadCollection(kind: String, id: String, keepUpdated: Boolean = false): String? {
        val (acc, auth) = ready() ?: return unavailableMessage()
        var entries = entriesOf(acc, kind, id)
        if (entries.isEmpty()) {
            library.refresh(targetOf(kind, id))?.let { return it }
            entries = entriesOf(acc, kind, id)
        }
        if (entries.isEmpty()) return "There's nothing to download here."
        val existing = dao.downloadedCollection(acc, kind, id)
        dao.upsertDownloadedCollection(
            DownloadedCollectionEntity(
                acc,
                kind,
                id,
                keepUpdated = (existing?.keepUpdated ?: false) || (keepUpdated && kind == PLAYLIST),
                addedAtMillis = existing?.addedAtMillis ?: System.currentTimeMillis(),
                stopped = false, // Downloading again resumes a stopped collection.
            ),
        )
        reconcile(acc, auth, "$kind:$id", entries, addNew = true)
        return null
    }

    /** Brings a downloaded collection in line with its current songs (refreshing them first). */
    suspend fun updateCollection(kind: String, id: String): String? {
        val (acc, auth) = ready() ?: return unavailableMessage()
        dao.setCollectionStopped(acc, kind, id, false)
        library.refresh(targetOf(kind, id))?.let { return it }
        reconcile(acc, auth, "$kind:$id", entriesOf(acc, kind, id), addNew = true)
        return null
    }

    suspend fun setKeepUpdated(playlistId: String, enabled: Boolean): String? {
        val acc = activeId() ?: return unavailableMessage()
        val existing = dao.downloadedCollection(acc, PLAYLIST, playlistId) ?: return null
        dao.upsertDownloadedCollection(existing.copy(keepUpdated = enabled))
        return if (enabled) updateCollection(PLAYLIST, playlistId) else null
    }

    suspend fun removeCollection(kind: String, id: String) {
        val acc = activeId() ?: return
        refsLock.withLock {
            val kept = dao.downloadRefSongIds(acc, "$kind:$id")
            dao.deleteDownloadRefsFor(acc, "$kind:$id")
            dao.deleteDownloadedCollection(acc, kind, id)
            release(acc, kept)
        }
        pruneArtwork(acc)
    }

    /**
     * Stops a collection's download partway: cancels songs that haven't
     * finished and keeps the finished ones for offline play.
     */
    suspend fun stopCollection(kind: String, id: String) {
        val acc = activeId() ?: return
        refsLock.withLock {
            val owner = "$kind:$id"
            val completed = songStates(acc, downloads.value).filterValues { it.state == SongDownloadState.Completed }.keys
            val unfinished = dao.downloadRefSongIds(acc, owner).filter { it !in completed }
            dao.setCollectionStopped(acc, kind, id, true)
            if (unfinished.isNotEmpty()) {
                dao.deleteDownloadRefs(acc, owner, unfinished)
                release(acc, unfinished)
            }
        }
    }

    /** Resumes a stopped collection, downloading its remaining songs. */
    suspend fun resumeCollection(kind: String, id: String): String? = downloadCollection(kind, id)

    /**
     * Cancels one song's download. Albums and playlists that included it become
     * partly downloaded (stopped), so it isn't downloaded again automatically.
     */
    suspend fun cancelSong(songId: String) {
        val acc = activeId() ?: return
        refsLock.withLock {
            for (owner in dao.downloadOwners(acc, songId)) {
                if (owner == OWNER_SONG) continue
                val (kind, id) = owner.split(":", limit = 2)
                dao.setCollectionStopped(acc, kind, id, true)
            }
            dao.deleteAllDownloadRefsForSong(acc, songId)
            release(acc, listOf(songId))
        }
    }

    /**
     * Follows server changes for Keep-updated playlists, and finishes pending
     * updates (releasing songs that left a collection once replacements are in).
     * Called after downloads complete, after playlist refreshes, and by the
     * background worker.
     */
    suspend fun reconcileAll() {
        val acc = activeId() ?: return
        val auth = ready()?.second
        for (c in dao.downloadedCollections(acc)) {
            val entries = entriesOf(acc, c.kind, c.collectionId)
            if (entries.isEmpty()) continue // Not cached (or emptied): keep what's downloaded.
            reconcile(acc, auth, c.owner, entries, addNew = c.keepUpdated && !c.stopped && auth != null)
        }
    }

    /** Refreshes Keep-updated playlists from the server, then reconciles. For the background worker. */
    suspend fun refreshKeepUpdated() {
        val acc = activeId() ?: return
        if (ready() == null) return
        for (c in dao.downloadedCollections(acc).filter { it.keepUpdated }) library.refresh(targetOf(c.kind, c.collectionId))
        reconcileAll()
    }

    fun pauseAll() = DownloadService.sendPauseDownloads(context, serviceClass, false)

    fun resumeAll() {
        if (!policy.offlineOnly.value) DownloadService.sendResumeDownloads(context, serviceClass, false)
    }

    /** Retries failed downloads of the active account. */
    fun retryFailed() {
        val acc = activeIdNow() ?: return
        songStates(acc, downloads.value).filterValues { it.state == SongDownloadState.Failed }.keys.forEach { send(acc, it) }
    }

    /** Deletes every download and saved cover of the active account. */
    suspend fun removeAll() {
        val acc = activeId() ?: return
        refsLock.withLock {
            val keys = downloads.value.keys.filter { it.startsWith("orig:$acc:") }
            keys.forEach { sendRemove(it) }
            dao.clearDownloadRefs(acc)
            dao.clearDownloadedCollections(acc)
        }
        artwork.deleteAll()
    }

    /** Runs downloads directly, without the foreground service (for the background worker). */
    suspend fun resumeInBackground() = withContext(Dispatchers.Main) {
        if (!policy.offlineOnly.value) manager.resumeDownloads()
    }

    /** True while the active account has downloads that aren't finished or failed. */
    fun hasPending(): Boolean {
        val acc = activeIdNow() ?: return false
        return songStates(acc, downloads.value).values.any { it.state in PENDING }
    }

    // --- Internals ---

    private var reconcileJob: kotlinx.coroutines.Job? = null

    /** Batches completions (a large playlist completes many songs in a row). */
    private fun onDownloadCompleted() {
        reconcileJob?.cancel()
        reconcileJob = scope.launch {
            kotlinx.coroutines.delay(2_000)
            reconcileAll()
        }
    }

    /** Applies [DownloadPlanner] to one collection. Must not run concurrently with other reference changes. */
    private suspend fun reconcile(acc: String, auth: ServerAuth?, owner: String, entries: List<String>, addNew: Boolean) {
        val added = refsLock.withLock {
            val kept = dao.downloadRefSongIds(acc, owner).toSet()
            val completed = songStates(acc, downloads.value).filterValues { it.state == SongDownloadState.Completed }.keys
            val plan = DownloadPlanner.plan(entries, kept, completed)
            val toAdd = if (addNew) plan.toAdd else emptyList()
            val toRelease = if (addNew) plan.toRelease else DownloadPlanner.releaseOnly(entries, kept, completed)
            if (toAdd.isNotEmpty()) dao.upsertDownloadRefs(toAdd.map { DownloadRefEntity(acc, it, owner) })
            // Start anything kept but not downloading (never retries failures automatically).
            val keep = (kept + toAdd).filter { it in entries }
            enqueue(acc, keep)
            if (toRelease.isNotEmpty()) {
                dao.deleteDownloadRefs(acc, owner, toRelease)
                release(acc, toRelease)
            }
            toAdd
        }
        if (auth != null) saveArtwork(acc, auth, added, owner)
        if (added.isNotEmpty() || entries.isNotEmpty()) pruneArtwork(acc)
    }

    /** Starts downloads for songs that have none yet. */
    private suspend fun enqueue(acc: String, songIds: List<String>) {
        val existing = downloads.value
        val missing = songIds.distinct().map { MediaUris.originalKey(acc, it) }.filter { it !in existing }
        if (missing.isEmpty()) return
        withContext(Dispatchers.Main) {
            // One service start is enough; it follows the manager for the rest.
            send(acc, missing.first())
            missing.drop(1).forEach { key -> manager.addDownload(request(acc, key)) }
        }
        onPending()
    }

    private fun request(acc: String, key: String): DownloadRequest {
        val songId = key.removePrefix("orig:$acc:")
        return DownloadRequest.Builder(key, MediaUris.song(acc, songId)).setCustomCacheKey(key).build()
    }

    private fun send(acc: String, key: String) {
        val request = request(acc, key)
        try {
            DownloadService.sendAddDownload(context, serviceClass, request, false)
        } catch (e: IllegalStateException) {
            manager.addDownload(request) // App in background: the worker drives it.
        }
    }

    private suspend fun sendRemove(key: String) = withContext(Dispatchers.Main) {
        try {
            DownloadService.sendRemoveDownload(context, serviceClass, key, false)
        } catch (e: IllegalStateException) {
            manager.removeDownload(key)
        }
    }

    /** Deletes the files of [songIds] that no collection or individual download keeps. */
    private suspend fun release(acc: String, songIds: List<String>) {
        if (songIds.isEmpty()) return
        val stillKept = dao.referencedSongIds(acc).toSet()
        songIds.distinct().filter { it !in stillKept }.forEach { sendRemove(MediaUris.originalKey(acc, it)) }
    }

    private fun saveArtwork(acc: String, auth: ServerAuth, songIds: List<String>, owner: String?) {
        scope.launch(Dispatchers.IO) {
            val covers = dao.songs(acc, songIds).mapNotNull { it.coverArt }.toMutableSet()
            owner?.let {
                val (kind, id) = it.split(":", limit = 2)
                (if (kind == ALBUM) dao.albumCoverArt(acc, id) else dao.playlistCoverArt(acc, id))?.let(covers::add)
            }
            covers.forEach { artwork.ensure(auth, acc, it) }
        }
    }

    private suspend fun pruneArtwork(acc: String) {
        artwork.keepOnly(acc, dao.downloadedCoverArtIds(acc).toSet())
    }

    private suspend fun entriesOf(acc: String, kind: String, id: String) =
        if (kind == ALBUM) dao.albumSongIds(acc, id) else dao.playlistSongIds(acc, id)

    private fun targetOf(kind: String, id: String) = if (kind == ALBUM) SyncTarget.Album(id) else SyncTarget.Playlist(id)

    private fun activeIdNow() = (accounts.state.value as? SessionState.Active)?.account?.id
    private fun activeId() = activeIdNow()

    companion object {
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"
        const val OWNER_SONG = "song"
        private val PENDING = setOf(SongDownloadState.Queued, SongDownloadState.Downloading, SongDownloadState.Paused)
    }
}
