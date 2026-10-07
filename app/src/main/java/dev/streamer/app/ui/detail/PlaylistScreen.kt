package dev.streamer.app.ui.detail

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.streamer.app.data.LibraryRepository
import dev.streamer.app.model.PlaylistDetail
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
import dev.streamer.app.ui.theme.Dimens
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class PlaylistViewModel(library: LibraryRepository, id: String) : ViewModel() {
    val state: StateFlow<DetailState<PlaylistDetail>> = library.playlist(id)
        .map { if (it == null) DetailState.NotFound else DetailState.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState.Loading)
}

@Composable
fun PlaylistRoute(id: String, navigator: AppNavigator, player: PlayerController) {
    val vm = appViewModel(key = "playlist-$id") { PlaylistViewModel(it.library, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val playerState by player.state.collectAsStateWithLifecycle()
    val playlist = (state as? DetailState.Loaded)?.value
    val songs = playlist?.entries?.map { it.song }.orEmpty()
    val fromThis = (playerState.source as? PlaybackSource.Playlist)?.id == id
    val source = playlist?.let { PlaybackSource.Playlist(id, it.summary.name) }
    val actions = SongActions(
        onPlayNext = player::playNext,
        onAddToQueue = player::addToQueue,
        onOpenAlbum = { s -> s.albumId?.let(navigator::openAlbum) },
        onOpenArtist = { s -> s.artistId?.let(navigator::openArtist) },
    )
    CollectionScreen(
        state = when (val s = state) {
            is DetailState.Loaded -> {
                val p = s.value.summary
                DetailState.Loaded(
                    CollectionHeader(
                        title = p.name,
                        artwork = p.artwork,
                        byline = p.owner?.let { Byline("By $it") },
                        meta = dotJoin(
                            "Playlist",
                            listOfNotNull(songCount(s.value.entries.size), p.duration?.formatLength()).joinToString(", "),
                        ),
                        description = p.comment,
                    ),
                )
            }
            DetailState.Loading -> DetailState.Loading
            DetailState.NotFound -> DetailState.NotFound
        },
        onBack = navigator::back,
        isPlayingThis = fromThis && playerState.isPlaying,
        hasSongs = songs.isNotEmpty(),
        onPlay = { if (fromThis) player.togglePlayPause() else player.play(songs, 0, source) },
        onShuffle = { player.play(songs, 0, source, shuffle = true) },
        emptyMessage = "This playlist is empty. Add songs to it in Navidrome.",
    ) { pad ->
        // Keyed by position: a playlist may contain the same song more than once.
        itemsIndexed(playlist?.entries.orEmpty(), key = { _, e -> e.position }) { i, entry ->
            SongRow(
                entry.song,
                onClick = { player.play(songs, i, source) },
                leading = SongLeading.Artwork,
                isCurrent = fromThis && playerState.current?.sourceIndex == i,
                actions = actions,
                horizontalPadding = pad + Dimens.s,
            )
        }
    }
}
