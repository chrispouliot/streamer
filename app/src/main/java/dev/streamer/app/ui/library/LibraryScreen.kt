package dev.streamer.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.streamer.app.data.LibraryRepository
import dev.streamer.app.data.SyncStatus
import dev.streamer.app.data.SyncTarget
import dev.streamer.app.model.AlbumSummary
import dev.streamer.app.model.ArtistSummary
import dev.streamer.app.model.Artwork
import dev.streamer.app.model.PlaylistSummary
import dev.streamer.app.model.Song
import dev.streamer.app.playback.PlaybackSource
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.settings.AlbumOrder
import dev.streamer.app.settings.ArtistOrder
import dev.streamer.app.settings.PlaylistListOrder
import dev.streamer.app.settings.SettingsRepository
import dev.streamer.app.ui.components.EmptyState
import dev.streamer.app.ui.components.MediaCard
import dev.streamer.app.ui.components.MediaRow
import dev.streamer.app.ui.components.Refreshable
import dev.streamer.app.ui.components.ScreenTitle
import dev.streamer.app.ui.components.SongActions
import dev.streamer.app.ui.components.SongRow
import dev.streamer.app.ui.components.SyncController
import dev.streamer.app.ui.components.SyncStatusText
import dev.streamer.app.ui.components.appViewModel
import dev.streamer.app.ui.components.dotJoin
import dev.streamer.app.ui.components.songCount
import dev.streamer.app.ui.icons.AppIcons
import dev.streamer.app.ui.navigation.AppNavigator
import dev.streamer.app.ui.navigation.LocalFloatingPlayerHeight
import dev.streamer.app.ui.navigation.LocalShellLayout
import dev.streamer.app.ui.navigation.WidthClass
import dev.streamer.app.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryFilter(val label: String) {
    Playlists("Playlists"),
    Favourites("Favourites"),
    Songs("Songs"),
    Albums("Albums"),
    Artists("Artists"),
    Downloaded("Downloaded"),
}

private val GRID_FILTERS = setOf(LibraryFilter.Playlists, LibraryFilter.Albums, LibraryFilter.Artists)

data class LibraryUiState(
    val loaded: Boolean = false,
    val playlists: List<PlaylistSummary> = emptyList(),
    val songs: List<Song> = emptyList(),
    val favourites: List<Song> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
)

class LibraryViewModel(library: LibraryRepository, private val settings: SettingsRepository) : ViewModel() {
    val albumOrder = settings.albumOrder.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumOrder.Name)
    val artistOrder = settings.artistOrder.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArtistOrder.Name)
    val playlistOrder = settings.playlistListOrder.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistListOrder.Name)

    private val lists = combine(library.playlists, library.songs, library.albums, library.artists, library.starredSongs) {
            playlists, songs, albums, artists, favourites ->
        LibraryUiState(true, playlists, songs, favourites, albums, artists)
    }

    val state: StateFlow<LibraryUiState> = combine(lists, albumOrder, artistOrder, playlistOrder) { s, ao, ar, po ->
        s.copy(albums = s.albums.sortedFor(ao), artists = s.artists.sortedFor(ar), playlists = s.playlists.sortedFor(po))
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /** One refresh controller per filter; each only observes while its filter is shown. */
    val syncs: Map<LibraryFilter, SyncController> = mapOf(
        LibraryFilter.Playlists to SyncController(library, SyncTarget.Playlists, viewModelScope),
        LibraryFilter.Favourites to SyncController(library, SyncTarget.Favourites, viewModelScope),
        LibraryFilter.Songs to SyncController(library, SyncTarget.Songs, viewModelScope),
        LibraryFilter.Albums to SyncController(library, SyncTarget.Albums, viewModelScope),
        LibraryFilter.Artists to SyncController(library, SyncTarget.Artists, viewModelScope),
    )

    fun setAlbumOrder(order: AlbumOrder) = viewModelScope.launch { settings.setAlbumOrder(order) }
    fun setArtistOrder(order: ArtistOrder) = viewModelScope.launch { settings.setArtistOrder(order) }
    fun setPlaylistOrder(order: PlaylistListOrder) = viewModelScope.launch { settings.setPlaylistListOrder(order) }
}

private val byName = String.CASE_INSENSITIVE_ORDER

fun List<AlbumSummary>.sortedFor(order: AlbumOrder): List<AlbumSummary> = when (order) {
    AlbumOrder.Name -> sortedWith(compareBy(byName) { it.name })
    AlbumOrder.Artist -> sortedWith(compareBy<AlbumSummary, String>(byName) { it.artist }.thenBy { it.year ?: Int.MAX_VALUE }.thenBy(byName) { it.name })
    AlbumOrder.Year -> sortedWith(compareByDescending<AlbumSummary> { it.year ?: Int.MIN_VALUE }.thenBy(byName) { it.name })
    AlbumOrder.RecentlyAdded -> sortedWith(compareByDescending<AlbumSummary, java.time.Instant?>(nullsFirst()) { it.addedAt }.thenBy(byName) { it.name })
}

fun List<ArtistSummary>.sortedFor(order: ArtistOrder): List<ArtistSummary> = when (order) {
    ArtistOrder.Name -> sortedWith(compareBy(byName) { it.name })
    ArtistOrder.MostAlbums -> sortedWith(compareByDescending<ArtistSummary> { it.albumCount ?: 0 }.thenBy(byName) { it.name })
}

fun List<PlaylistSummary>.sortedFor(order: PlaylistListOrder): List<PlaylistSummary> = when (order) {
    PlaylistListOrder.Name -> sortedWith(compareBy(byName) { it.name })
    PlaylistListOrder.RecentlyUpdated -> sortedWith(compareByDescending<PlaylistSummary, java.time.Instant?>(nullsFirst()) { it.changedAt }.thenBy(byName) { it.name })
}

@Composable
fun LibraryRoute(navigator: AppNavigator, player: PlayerController) {
    val vm = appViewModel { LibraryViewModel(it.library, it.settings) }
    val state by vm.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.Playlists) }
    var grid by rememberSaveable { mutableStateOf(false) }
    // A "Show all" from Home picks the view (and album order) to show.
    val request = navigator.libraryRequest
    LaunchedEffect(request) {
        if (request != null) {
            filter = request.filter
            request.albumOrder?.let(vm::setAlbumOrder)
            navigator.libraryRequest = null
        }
    }
    val sync = vm.syncs[filter]
    val status by (sync?.status ?: remember { MutableStateFlow(SyncStatus()) }).collectAsStateWithLifecycle()
    val refreshing by (sync?.refreshing ?: remember { MutableStateFlow(false) }).collectAsStateWithLifecycle()
    val albumOrder by vm.albumOrder.collectAsStateWithLifecycle()
    val artistOrder by vm.artistOrder.collectAsStateWithLifecycle()
    val playlistOrder by vm.playlistOrder.collectAsStateWithLifecycle()
    val sortMenu: (@Composable () -> Unit)? = when (filter) {
        LibraryFilter.Albums -> { { SortMenu(AlbumOrder.entries, albumOrder, { it.label }, { vm.setAlbumOrder(it) }) } }
        LibraryFilter.Artists -> { { SortMenu(ArtistOrder.entries, artistOrder, { it.label }, { vm.setArtistOrder(it) }) } }
        LibraryFilter.Playlists -> { { SortMenu(PlaylistListOrder.entries, playlistOrder, { it.label }, { vm.setPlaylistOrder(it) }) } }
        else -> null
    }
    LibraryScreen(
        state = state,
        filter = filter,
        onFilter = { filter = it },
        grid = grid,
        onGrid = { grid = it },
        status = status,
        refreshing = refreshing,
        onRefresh = { sync?.refresh() },
        sortMenu = sortMenu,
        navigator = navigator,
        player = player,
    )
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
    status: SyncStatus,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    sortMenu: (@Composable () -> Unit)?,
    navigator: AppNavigator,
    player: PlayerController,
) {
    val layout = LocalShellLayout.current
    val pad = layout.pagePadding
    val bottom = Dimens.xl + LocalFloatingPlayerHeight.current
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Library", pad) {
            sortMenu?.invoke()
            if (filter in GRID_FILTERS) {
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
        SyncStatusText(status, Modifier.padding(horizontal = pad, vertical = Dimens.xs))
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
        // Every state is scrollable so pull-to-refresh works, including empty ones.
        Refreshable(refreshing, onRefresh, Modifier.fillMaxSize()) {
            when {
                !state.loaded -> Unit
                filter == LibraryFilter.Downloaded -> ScrollableEmpty(
                    "No downloads",
                    "Downloading music for offline playback isn't available in this build yet.",
                )
                filter == LibraryFilter.Songs -> SongList(state.songs, "Songs", "No songs", pad, navigator, player)
                filter == LibraryFilter.Favourites ->
                    SongList(state.favourites, "Favourite songs", "Tap the heart on a song to add it here.", pad, navigator, player)
                tiles.isNullOrEmpty() -> ScrollableEmpty("Nothing here yet", "Your server has no ${filter.label.lowercase()} to show.")
                grid -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = pad, end = pad, top = Dimens.s, bottom = bottom),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.l),
                    verticalArrangement = Arrangement.spacedBy(Dimens.s),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(tiles, key = { it.key }) { t -> MediaCard(t.title, t.subtitle, t.artwork, t.open, circular = t.circular) }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = Dimens.xs, bottom = bottom)) {
                    items(tiles, key = { it.key }) { t ->
                        MediaRow(t.title, t.subtitle, t.artwork, t.open, circular = t.circular, horizontalPadding = pad)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScrollableEmpty(title: String, message: String) {
    LazyColumn(Modifier.fillMaxSize()) { item { EmptyState(title, message) } }
}

@Composable
private fun <T> SortMenu(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.semantics { contentDescription = "Sort: ${label(selected)}. Change sort order" },
        ) { Text(label(selected)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = { open = false; onSelect(option) },
                    trailingIcon = { if (option == selected) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                )
            }
        }
    }
}

@Composable
private fun SongList(
    songs: List<Song>,
    sourceTitle: String,
    emptyMessage: String,
    pad: androidx.compose.ui.unit.Dp,
    navigator: AppNavigator,
    player: PlayerController,
) {
    if (songs.isEmpty()) {
        ScrollableEmpty("Nothing here yet", emptyMessage)
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
            SongRow(song, onClick = { player.play(songs, i, PlaybackSource.Songs(sourceTitle)) }, actions = actions, horizontalPadding = pad)
        }
    }
}
