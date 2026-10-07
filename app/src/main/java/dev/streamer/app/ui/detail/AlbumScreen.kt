package dev.streamer.app.ui.detail

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.streamer.app.data.LibraryRepository
import dev.streamer.app.data.SyncTarget
import dev.streamer.app.data.UserMessages
import dev.streamer.app.data.downloads.DownloadRepository
import dev.streamer.app.model.AlbumDetail
import dev.streamer.app.model.Artwork
import dev.streamer.app.playback.PlaybackSource
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.settings.SettingsRepository
import dev.streamer.app.settings.TrackSort
import dev.streamer.app.settings.TrackSortField
import dev.streamer.app.ui.components.SongActions
import dev.streamer.app.ui.components.SongLeading
import dev.streamer.app.ui.components.SongRow
import dev.streamer.app.ui.components.SyncController
import dev.streamer.app.ui.components.TrackSortControl
import dev.streamer.app.ui.components.appViewModel
import dev.streamer.app.ui.components.dotJoin
import dev.streamer.app.ui.components.formatLength
import dev.streamer.app.ui.components.songCount
import dev.streamer.app.ui.components.sortedForTracks
import dev.streamer.app.ui.downloads.CollectionDownloads
import dev.streamer.app.ui.downloads.rememberDownloadsUi
import dev.streamer.app.ui.navigation.AppNavigator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlbumViewModel(
    library: LibraryRepository,
    private val settings: SettingsRepository,
    downloads: DownloadRepository,
    messages: UserMessages,
    id: String,
) : ViewModel() {
    val sort: StateFlow<TrackSort> = settings.albumTrackSort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackSort.AlbumDefault)

    fun setSort(sort: TrackSort) {
        viewModelScope.launch { settings.setAlbumTrackSort(sort) }
    }

    val download = CollectionDownloads(downloads, messages, DownloadRepository.ALBUM, id, this)
    val sync = SyncController(library, SyncTarget.Album(id), viewModelScope)

    val state: StateFlow<DetailState<AlbumDetail>> = library.album(id)
        .map { if (it == null) DetailState.NotFound else DetailState.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState.Loading)
}

@Composable
fun AlbumRoute(id: String, navigator: AppNavigator, player: PlayerController) {
    val vm = appViewModel(key = "album-$id") { AlbumViewModel(it.library, it.settings, it.downloads, it.messages, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val playerState by player.state.collectAsStateWithLifecycle()
    val syncStatus by vm.sync.status.collectAsStateWithLifecycle()
    val downloadStatus by vm.download.status.collectAsStateWithLifecycle()
    val refreshing by vm.sync.refreshing.collectAsStateWithLifecycle()
    val album = (state as? DetailState.Loaded)?.value
    val sort by vm.sort.collectAsStateWithLifecycle()
    // Display order; each song keeps its album position for highlighting and the queue.
    val ordered = remember(album, sort) {
        album?.songs.orEmpty().withIndex().toList().sortedForTracks(sort, { it.index }, { it.value })
    }
    val songs = remember(ordered) { ordered.map { it.value } }
    val positions = remember(ordered) { ordered.map { it.index } }
    val fromThis = (playerState.source as? PlaybackSource.Album)?.id == id
    val source = album?.let { PlaybackSource.Album(id, it.summary.name) }
    val downloads = rememberDownloadsUi()
    val actions = SongActions(
        onPlayNext = player::playNext,
        onAddToQueue = player::addToQueue,
        onOpenArtist = { s -> s.artistId?.let(navigator::openArtist) },
        downloads = downloads,
    )
    CollectionScreen(
        state = when (val s = state) {
            is DetailState.Loaded -> DetailState.Loaded(
                CollectionHeader(
                    title = s.value.summary.name,
                    artwork = s.value.summary.artwork,
                    byline = Byline(
                        s.value.summary.artist,
                        artwork = s.value.artistArtwork ?: Artwork(null, s.value.summary.artistId ?: s.value.summary.artist),
                        onClick = s.value.summary.artistId?.let { aid -> { navigator.openArtist(aid) } },
                    ),
                    meta = dotJoin(
                        "Album",
                        s.value.summary.year?.toString(),
                        listOfNotNull(
                            (s.value.songs.size.takeIf { it > 0 } ?: s.value.summary.songCount)?.let(::songCount),
                            s.value.duration?.formatLength(),
                        ).joinToString(", "),
                    ),
                ),
            )
            DetailState.Loading -> DetailState.Loading
            DetailState.NotFound -> DetailState.NotFound
        },
        onBack = navigator::back,
        isPlayingThis = fromThis && playerState.isPlaying,
        hasSongs = !album?.songs.isNullOrEmpty(),
        onPlay = {
            if (fromThis) player.togglePlayPause() else player.play(songs, 0, source, sourcePositions = positions)
        },
        onShuffle = { player.play(songs, 0, source, shuffle = true, sourcePositions = positions) },
        emptyMessage = "This album has no songs.",
        sync = syncStatus,
        downloadStatus = downloadStatus,
        downloadActions = vm.download.actions,
        refreshing = refreshing,
        onRefresh = vm.sync::refresh,
        sortControl = if (songs.size > 1) {
            { modifier -> TrackSortControl(sort, TrackSortField.forAlbums, vm::setSort, modifier) }
        } else {
            null
        },
    ) { pad ->
        itemsIndexed(ordered, key = { _, s -> s.value.id }) { i, item ->
            val song = item.value
            SongRow(
                song,
                onClick = { player.play(songs, i, source, sourcePositions = positions) },
                leading = SongLeading.Number,
                number = song.trackNumber ?: (item.index + 1),
                isCurrent = fromThis && playerState.current?.sourceIndex == item.index,
                actions = actions,
                horizontalPadding = pad,
            )
        }
    }
}
