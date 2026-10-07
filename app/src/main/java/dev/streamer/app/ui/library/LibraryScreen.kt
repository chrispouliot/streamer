package dev.streamer.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.streamer.app.data.LibraryRepository
import dev.streamer.app.model.AlbumSummary
import dev.streamer.app.model.ArtistSummary
import dev.streamer.app.model.Artwork
import dev.streamer.app.model.PlaylistSummary
import dev.streamer.app.model.Song
import dev.streamer.app.playback.PlaybackSource
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.ui.components.EmptyState
import dev.streamer.app.ui.components.MediaCard
import dev.streamer.app.ui.components.MediaRow
import dev.streamer.app.ui.components.ScreenTitle
import dev.streamer.app.ui.components.SongActions
import dev.streamer.app.ui.components.SongRow
import dev.streamer.app.ui.components.appViewModel
import dev.streamer.app.ui.components.dotJoin
import dev.streamer.app.ui.components.songCount
import dev.streamer.app.ui.icons.AppIcons
import dev.streamer.app.ui.navigation.AppNavigator
import dev.streamer.app.ui.navigation.LocalFloatingPlayerHeight
import dev.streamer.app.ui.navigation.LocalShellLayout
import dev.streamer.app.ui.navigation.WidthClass
import dev.streamer.app.ui.theme.Dimens
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class LibraryFilter(val label: String) {
    Playlists("Playlists"),
    Songs("Songs"),
    Albums("Albums"),
    Artists("Artists"),
    Downloaded("Downloaded"),
}

data class LibraryUiState(
    val loaded: Boolean = false,
    val playlists: List<PlaylistSummary> = emptyList(),
    val songs: List<Song> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
)

class LibraryViewModel(library: LibraryRepository) : ViewModel() {
    val state: StateFlow<LibraryUiState> = combine(
        library.playlists,
        library.songs,
        library.albums,
        library.artists,
    ) { playlists, songs, albums, artists -> LibraryUiState(true, playlists, songs, albums, artists) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())
}

@Composable
fun LibraryRoute(navigator: AppNavigator, player: PlayerController) {
    val vm = appViewModel { LibraryViewModel(it.library) }
    val state by vm.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.Playlists) }
    var grid by rememberSaveable { mutableStateOf(false) }
    LibraryScreen(state, filter, { filter = it }, grid, { grid = it }, navigator, player)
}

/** A playlist/album/artist shown as a list row or a grid card. */
private data class Tile(val key: String, val title: String, val subtitle: String?, val artwork: Artwork, val circular: Boolean, val open: () -> Unit)

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    filter: LibraryFilter,
    onFilter: (LibraryFilter) -> Unit,
    grid: Boolean,
    onGrid: (Boolean) -> Unit,
    navigator: AppNavigator,
    player: PlayerController,
) {
    val layout = LocalShellLayout.current
    val pad = layout.pagePadding
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Library", pad) {
            if (filter != LibraryFilter.Songs && filter != LibraryFilter.Downloaded) {
                IconButton(onClick = { onGrid(!grid) }) {
                    Icon(
                        if (grid) AppIcons.ViewList else AppIcons.GridView,
                        contentDescription = if (grid) "Show as list" else "Show as grid",
                    )
                }
            }
            if (layout.widthClass == WidthClass.Compact) {
                IconButton(onClick = navigator::openSearch) { Icon(Icons.Filled.Search, contentDescription = "Search") }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = pad),
            horizontalArrangement = Arrangement.spacedBy(Dimens.s),
        ) {
            items(LibraryFilter.entries) { f ->
                FilterChip(selected = f == filter, onClick = { onFilter(f) }, label = { Text(f.label) })
            }
        }
        val tiles: List<Tile>? = when (filter) {
            LibraryFilter.Playlists -> state.playlists.map {
                Tile(it.id, it.name, dotJoin("Playlist", it.owner, it.songCount?.let(::songCount)), it.artwork, false) { navigator.openPlaylist(it.id) }
            }
            LibraryFilter.Albums -> state.albums.map {
                Tile(it.id, it.name, dotJoin(it.artist, it.year?.toString()), it.artwork, false) { navigator.openAlbum(it.id) }
            }
            LibraryFilter.Artists -> state.artists.map {
                Tile(it.id, it.name, it.albumCount?.let { n -> if (n == 1) "1 album" else "$n albums" }, it.artwork, true) { navigator.openArtist(it.id) }
            }
            else -> null
        }
        when {
            !state.loaded -> Unit
            filter == LibraryFilter.Downloaded -> EmptyState(
                "No downloads",
                "Downloading music for offline playback isn't available in this build yet.",
            )
            filter == LibraryFilter.Songs -> SongList(state.songs, pad, navigator, player)
            tiles.isNullOrEmpty() -> EmptyState("Nothing here yet", "Your server has no ${filter.label.lowercase()} to show.")
            grid -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(start = pad, end = pad, top = Dimens.l, bottom = Dimens.xl + LocalFloatingPlayerHeight.current),
                horizontalArrangement = Arrangement.spacedBy(Dimens.l),
                verticalArrangement = Arrangement.spacedBy(Dimens.s),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(tiles, key = { it.key }) { t -> MediaCard(t.title, t.subtitle, t.artwork, t.open, circular = t.circular) }
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = Dimens.s, bottom = Dimens.xl + LocalFloatingPlayerHeight.current)) {
                items(tiles, key = { it.key }) { t ->
                    MediaRow(t.title, t.subtitle, t.artwork, t.open, circular = t.circular, horizontalPadding = pad)
                }
            }
        }
    }
}

@Composable
private fun SongList(songs: List<Song>, pad: androidx.compose.ui.unit.Dp, navigator: AppNavigator, player: PlayerController) {
    if (songs.isEmpty()) {
        EmptyState("No songs", null)
        return
    }
    val actions = SongActions(
        onPlayNext = player::playNext,
        onAddToQueue = player::addToQueue,
        onOpenAlbum = { s -> s.albumId?.let(navigator::openAlbum) },
        onOpenArtist = { s -> s.artistId?.let(navigator::openArtist) },
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = Dimens.s, bottom = Dimens.xl + LocalFloatingPlayerHeight.current)) {
        itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
            SongRow(song, onClick = { player.play(songs, i, PlaybackSource.Songs("Songs")) }, actions = actions, horizontalPadding = pad)
        }
    }
}
