package dev.streamer.app.ui.detail

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.streamer.app.data.LibraryRepository
import dev.streamer.app.model.AlbumDetail
import dev.streamer.app.model.Artwork
import dev.streamer.app.playback.PlaybackSource
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.ui.components.SongActions
import dev.streamer.app.ui.components.SongLeading
import dev.streamer.app.ui.components.SongRow
import dev.streamer.app.ui.components.appViewModel
import dev.streamer.app.ui.components.dotJoin
import dev.streamer.app.ui.components.formatLength
import dev.streamer.app.ui.components.songCount
import dev.streamer.app.ui.navigation.AppNavigator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class AlbumViewModel(library: LibraryRepository, id: String) : ViewModel() {
    val state: StateFlow<DetailState<AlbumDetail>> = library.album(id)
        .map { if (it == null) DetailState.NotFound else DetailState.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState.Loading)
}

@Composable
fun AlbumRoute(id: String, navigator: AppNavigator, player: PlayerController) {
    val vm = appViewModel(key = "album-$id") { AlbumViewModel(it.library, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val playerState by player.state.collectAsStateWithLifecycle()
    val album = (state as? DetailState.Loaded)?.value
    val fromThis = (playerState.source as? PlaybackSource.Album)?.id == id
    val source = album?.let { PlaybackSource.Album(id, it.summary.name) }
    val actions = SongActions(
        onPlayNext = player::playNext,
        onAddToQueue = player::addToQueue,
        onOpenArtist = { s -> s.artistId?.let(navigator::openArtist) },
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
                        listOfNotNull(songCount(s.value.songs.size), s.value.duration?.formatLength()).joinToString(", "),
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
            if (fromThis) player.togglePlayPause() else album?.let { player.play(it.songs, 0, source) }
        },
        onShuffle = { album?.let { player.play(it.songs, 0, source, shuffle = true) } },
        emptyMessage = "This album has no songs.",
    ) { pad ->
        val songs = album?.songs.orEmpty()
        itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
            SongRow(
                song,
                onClick = { player.play(songs, i, source) },
                leading = SongLeading.Number,
                number = song.trackNumber ?: (i + 1),
                isCurrent = fromThis && playerState.current?.sourceIndex == i,
                actions = actions,
                horizontalPadding = pad,
            )
        }
    }
}
