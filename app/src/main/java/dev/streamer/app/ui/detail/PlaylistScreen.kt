package dev.streamer.app.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.streamer.app.data.LibraryRepository
import dev.streamer.app.model.PlaylistDetail
import dev.streamer.app.model.PlaylistEntry
import dev.streamer.app.playback.PlaybackSource
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.settings.PlaylistSort
import dev.streamer.app.settings.SettingsRepository
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
import kotlinx.coroutines.launch

class PlaylistViewModel(library: LibraryRepository, private val settings: SettingsRepository, id: String) : ViewModel() {
    val state: StateFlow<DetailState<PlaylistDetail>> = library.playlist(id)
        .map { if (it == null) DetailState.NotFound else DetailState.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState.Loading)

    /** The user's choice for this playlist, or null to use [defaultSortFor]. */
    val chosenSort: StateFlow<PlaylistSort?> = settings.playlistSort(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val playlistId = id

    fun setSort(sort: PlaylistSort) {
        viewModelScope.launch { settings.setPlaylistSort(playlistId, sort) }
    }
}

/**
 * Default when the user hasn't chosen: a playlist made entirely of favourites
 * (such as a rule-based "loved" smart playlist, whose order is set by its rule)
 * lists newest favourites first; others list the newest additions first.
 */
fun defaultSortFor(entries: List<PlaylistEntry>): PlaylistSort =
    if (entries.isNotEmpty() && entries.all { it.song.favouritedAt != null }) PlaylistSort.RecentlyFavourited
    else PlaylistSort.RecentlyAdded

/** Display order only; the server playlist is never reordered. */
fun List<PlaylistEntry>.sortedFor(sort: PlaylistSort): List<PlaylistEntry> = when (sort) {
    PlaylistSort.RecentlyAdded -> sortedByDescending { it.position }
    // Newest favourites first; songs without a favourite time keep newest-added order after them.
    PlaylistSort.RecentlyFavourited -> sortedWith(
        compareByDescending<PlaylistEntry, java.time.Instant?>(nullsFirst()) { it.song.favouritedAt }
            .thenByDescending { it.position },
    )
    PlaylistSort.PlaylistOrder -> sortedBy { it.position }
    PlaylistSort.Title -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.song.title })
    PlaylistSort.Artist -> sortedWith(
        compareBy<PlaylistEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.song.artist }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.song.album ?: "" }
            .thenBy { it.song.trackNumber ?: Int.MAX_VALUE },
    )
}

@Composable
fun PlaylistRoute(id: String, navigator: AppNavigator, player: PlayerController) {
    val vm = appViewModel(key = "playlist-$id") { PlaylistViewModel(it.library, it.settings, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val chosenSort by vm.chosenSort.collectAsStateWithLifecycle()
    val playerState by player.state.collectAsStateWithLifecycle()
    val playlist = (state as? DetailState.Loaded)?.value
    val sort = chosenSort ?: defaultSortFor(playlist?.entries.orEmpty())
    val hasFavourites = remember(playlist) { playlist?.entries.orEmpty().any { it.song.favouritedAt != null } }
    // Sorting 2000 entries is cheap, but only redo it when the data or sort changes.
    val entries = remember(playlist, sort) { playlist?.entries.orEmpty().sortedFor(sort) }
    val songs = remember(entries) { entries.map { it.song } }
    val positions = remember(entries) { entries.map { it.position } }
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
        // Play and shuffle use the order shown.
        onPlay = { if (fromThis) player.togglePlayPause() else player.play(songs, 0, source, sourcePositions = positions) },
        onShuffle = { player.play(songs, 0, source, shuffle = true, sourcePositions = positions) },
        emptyMessage = "This playlist is empty. Add songs to it in Navidrome.",
        topActions = { if (songs.size > 1) SortMenu(sort, hasFavourites, vm::setSort) },
    ) { pad ->
        // Keyed by position: a playlist may contain the same song more than once.
        itemsIndexed(entries, key = { _, e -> e.position }) { i, entry ->
            SongRow(
                entry.song,
                onClick = { player.play(songs, i, source, sourcePositions = positions) },
                leading = SongLeading.Artwork,
                // Compare playlist positions, so duplicates and re-sorting highlight the right row.
                isCurrent = fromThis && playerState.current?.sourceIndex == entry.position,
                actions = actions,
                horizontalPadding = pad + Dimens.s,
            )
        }
    }
}

@Composable
private fun SortMenu(sort: PlaylistSort, hasFavourites: Boolean, onSort: (PlaylistSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.semantics { contentDescription = "Sort: ${sort.label}. Change sort order" },
        ) { Text(sort.label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PlaylistSort.entries.filter { it != PlaylistSort.RecentlyFavourited || hasFavourites }.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { open = false; onSort(option) },
                    trailingIcon = { if (option == sort) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                )
            }
        }
    }
}
