package dev.streamer.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.streamer.app.data.LibraryRepository
import dev.streamer.app.data.SyncStatus
import dev.streamer.app.data.SyncTarget
import dev.streamer.app.model.AlbumSummary
import dev.streamer.app.model.Artwork
import dev.streamer.app.model.PlaylistSummary
import dev.streamer.app.model.RecentCollection
import dev.streamer.app.model.Song
import dev.streamer.app.playback.PlaybackSource
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.settings.AlbumOrder
import dev.streamer.app.ui.components.EmptyState
import dev.streamer.app.ui.components.MediaCard
import dev.streamer.app.ui.components.Refreshable
import dev.streamer.app.ui.components.SectionHeader
import dev.streamer.app.ui.components.ShortcutTile
import dev.streamer.app.ui.components.SongActions
import dev.streamer.app.ui.components.SongRow
import dev.streamer.app.ui.components.SyncController
import dev.streamer.app.ui.components.SyncStatusText
import dev.streamer.app.ui.components.appViewModel
import dev.streamer.app.ui.components.dotJoin
import dev.streamer.app.ui.components.songCount
import dev.streamer.app.ui.downloads.rememberDownloadsUi
import dev.streamer.app.ui.library.LibraryFilter
import dev.streamer.app.ui.library.LibraryRequest
import dev.streamer.app.ui.navigation.AppNavigator
import dev.streamer.app.ui.navigation.LocalFloatingPlayerHeight
import dev.streamer.app.ui.navigation.LocalShellLayout
import dev.streamer.app.ui.navigation.WidthClass
import dev.streamer.app.ui.theme.Dimens
import java.time.LocalTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val loaded: Boolean = false,
    val playlists: List<PlaylistSummary> = emptyList(),
    val recentAlbums: List<AlbumSummary> = emptyList(),
    val recentlyPlayed: List<Song> = emptyList(),
    val favourites: List<Song> = emptyList(),
    val recentCollections: List<RecentCollection> = emptyList(),
) {
    val isEmpty: Boolean
        get() = playlists.isEmpty() && recentAlbums.isEmpty() && recentlyPlayed.isEmpty() && favourites.isEmpty() &&
            recentCollections.isEmpty()
}

class HomeViewModel(library: LibraryRepository) : ViewModel() {
    val sync = SyncController(library, SyncTarget.Home, viewModelScope)

    val state: StateFlow<HomeUiState> = combine(
        library.playlists,
        library.recentlyAddedAlbums,
        library.recentlyPlayed,
        library.starredSongs,
        library.recentCollections,
    ) { playlists, albums, recent, favourites, collections ->
        HomeUiState(true, playlists, albums, recent, favourites, collections)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
}

/** A shortcut target: a playlist or an album. */
private data class Shortcut(val title: String, val artwork: Artwork, val open: () -> Unit)

@Composable
fun HomeRoute(navigator: AppNavigator, player: PlayerController) {
    val vm = appViewModel { HomeViewModel(it.library) }
    val state by vm.state.collectAsStateWithLifecycle()
    val status by vm.sync.status.collectAsStateWithLifecycle()
    val refreshing by vm.sync.refreshing.collectAsStateWithLifecycle()
    HomeScreen(state, status, refreshing, vm.sync::refresh, navigator, player)
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    status: SyncStatus,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    navigator: AppNavigator,
    player: PlayerController,
) {
    val layout = LocalShellLayout.current
    val pad = layout.pagePadding
    val wide = layout.widthClass != WidthClass.Compact
    // The albums and playlists played from most recently (up to 4).
    val shortcuts = state.recentCollections.map { c ->
        when (c) {
            is RecentCollection.Album -> Shortcut(c.album.name, c.album.artwork) { navigator.openAlbum(c.album.id) }
            is RecentCollection.Playlist -> Shortcut(c.playlist.name, c.playlist.artwork) { navigator.openPlaylist(c.playlist.id) }
        }
    }
    val downloads = rememberDownloadsUi()
    val songActions = SongActions(
        onPlayNext = player::playNext,
        onAddToQueue = player::addToQueue,
        onOpenAlbum = { s -> s.albumId?.let(navigator::openAlbum) },
        onOpenArtist = { s -> s.artistId?.let(navigator::openArtist) },
        downloads = downloads,
    )

    Refreshable(refreshing, onRefresh, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Dimens.xl + LocalFloatingPlayerHeight.current)) {
            item(key = "header") {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = pad, end = pad - Dimens.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Weighted so large text wraps instead of pushing the buttons off screen.
                    Text(
                        greeting(),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    if (wide) {
                        SearchLauncher(navigator::openSearch, Modifier.widthIn(max = 380.dp).weight(1f, fill = false))
                    } else {
                        IconButton(onClick = navigator::openSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                }
            }
            item(key = "status") { SyncStatusText(status, Modifier.padding(horizontal = pad)) }
            if (state.loaded && state.isEmpty) {
                item(key = "empty") {
                    EmptyState(
                        "Nothing here yet",
                        "Playlists and recently added albums from your server appear here once they've loaded.",
                    )
                }
            }
            if (shortcuts.isNotEmpty()) {
                item(key = "shortcuts") {
                    BoxWithConstraints(Modifier.padding(horizontal = pad, vertical = Dimens.s)) {
                    // From the actual width, so tiles keep room for their titles.
                    val columns = when {
                        maxWidth >= 900.dp -> 4
                        maxWidth >= 560.dp -> 3
                        else -> 2
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                        shortcuts.chunked(columns).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                                row.forEach { ShortcutTile(it.title, it.artwork, it.open, Modifier.weight(1f)) }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    }
                }
            }
            if (state.playlists.isNotEmpty()) {
                item(key = "playlists-header") { SectionHeader(
                        "Playlists",
                        Modifier.padding(start = pad, end = pad, top = Dimens.l),
                        actionLabel = "Show all",
                        actionDescription = "Show all playlists",
                        onAction = { navigator.openLibrary(LibraryRequest(LibraryFilter.Playlists)) },
                    ) }
                item(key = "playlists") {
                    Shelf(state.playlists, key = { it.id }) { p ->
                        MediaCard(
                            p.name,
                            p.songCount?.let(::songCount),
                            p.artwork,
                            onClick = { navigator.openPlaylist(p.id) },
                            modifier = Modifier.width(if (wide) 200.dp else Dimens.cardWidth),
                        )
                    }
                }
            }
            if (state.recentAlbums.isNotEmpty()) {
                item(key = "albums-header") {
                    SectionHeader(
                        "Recently added albums",
                        Modifier.padding(start = pad, end = pad, top = Dimens.l),
                        actionLabel = "Show all",
                        actionDescription = "Show all recently added albums",
                        onAction = { navigator.openLibrary(LibraryRequest(LibraryFilter.Albums, AlbumOrder.RecentlyAdded)) },
                    )
                }
                item(key = "albums") {
                    Shelf(state.recentAlbums, key = { it.id }) { a ->
                        MediaCard(
                            a.name,
                            dotJoin(a.artist, a.year?.toString()),
                            a.artwork,
                            onClick = { navigator.openAlbum(a.id) },
                            modifier = Modifier.width(if (wide) 200.dp else Dimens.cardWidth),
                        )
                    }
                }
            }
            if (state.recentlyPlayed.isNotEmpty()) {
                item(key = "recent-header") {
                    SectionHeader("Recently played", Modifier.padding(start = pad, end = pad, top = Dimens.l))
                }
                itemsIndexed(state.recentlyPlayed, key = { i, s -> "recent-$i-${s.id}" }) { index, song ->
                    SongRow(
                        song,
                        onClick = { player.play(state.recentlyPlayed, index, PlaybackSource.Songs("Recently played")) },
                        actions = songActions,
                        horizontalPadding = pad,
                    )
                }
            }
            if (state.favourites.isNotEmpty()) {
                item(key = "favourites-header") {
                    SectionHeader(
                        "Favourite songs",
                        Modifier.padding(start = pad, end = pad, top = Dimens.l),
                        actionLabel = "Show all",
                        actionDescription = "Show all favourite songs",
                        onAction = { navigator.openLibrary(LibraryRequest(LibraryFilter.Favourites)) },
                    )
                }
                itemsIndexed(state.favourites.take(10), key = { i, s -> "fav-$i-${s.id}" }) { index, song ->
                    SongRow(
                        song,
                        onClick = { player.play(state.favourites, index, PlaybackSource.Songs("Favourite songs")) },
                        actions = songActions,
                        horizontalPadding = pad,
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> Shelf(items: List<T>, key: (T) -> Any, content: @Composable (T) -> Unit) {
    val pad = LocalShellLayout.current.pagePadding
    LazyRow(
        contentPadding = PaddingValues(horizontal = pad),
        horizontalArrangement = Arrangement.spacedBy(Dimens.l),
    ) {
        items(items, key = key) { content(it) }
    }
}

@Composable
private fun SearchLauncher(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(horizontal = Dimens.l), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, contentDescription = null)
            Spacer(Modifier.width(Dimens.m))
            Text("Search your library", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}
