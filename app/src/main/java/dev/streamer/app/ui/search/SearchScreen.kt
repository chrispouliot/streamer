package dev.streamer.app.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.streamer.app.LocalAppContainer
import dev.streamer.app.data.LibraryRepository
import dev.streamer.app.model.SearchResults
import dev.streamer.app.playback.PlaybackSource
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.ui.components.EmptyState
import dev.streamer.app.ui.components.MediaRow
import dev.streamer.app.ui.components.ScreenTitle
import dev.streamer.app.ui.components.SectionHeader
import dev.streamer.app.ui.components.SongActions
import dev.streamer.app.ui.components.SongRow
import dev.streamer.app.ui.components.dotJoin
import dev.streamer.app.ui.components.songCount
import dev.streamer.app.ui.navigation.AppNavigator
import dev.streamer.app.ui.navigation.LocalFloatingPlayerHeight
import dev.streamer.app.ui.navigation.LocalShellLayout
import dev.streamer.app.ui.theme.Dimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface SearchStatus {
    data object Idle : SearchStatus
    data object Loading : SearchStatus
    data class Done(val results: SearchResults) : SearchStatus
    data class Failed(val message: String) : SearchStatus
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val library: LibraryRepository, private val handle: SavedStateHandle) : ViewModel() {
    val query: StateFlow<String> = handle.getStateFlow(QUERY, "")

    /** Debounced; a newer query cancels the previous search. */
    val status: StateFlow<SearchStatus> = query
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce { if (it.isEmpty()) 0L else 300L }
        .flatMapLatest { q ->
            flow {
                if (q.isEmpty()) {
                    emit(SearchStatus.Idle)
                    return@flow
                }
                emit(SearchStatus.Loading)
                val result = try {
                    SearchStatus.Done(library.search(q))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SearchStatus.Failed("Search failed. Check your connection and try again.")
                }
                emit(result)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchStatus.Idle)

    fun setQuery(value: String) {
        handle[QUERY] = value
    }

    private companion object {
        const val QUERY = "query"
    }
}

@Composable
fun SearchRoute(navigator: AppNavigator, player: PlayerController) {
    val container = LocalAppContainer.current
    val vm = viewModel { SearchViewModel(container.library, createSavedStateHandle()) }
    val query by vm.query.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    SearchScreen(query, status, vm::setQuery, navigator, player)
}

@Composable
fun SearchScreen(
    query: String,
    status: SearchStatus,
    onQueryChange: (String) -> Unit,
    navigator: AppNavigator,
    player: PlayerController,
) {
    val pad = LocalShellLayout.current.pagePadding
    val focus = LocalFocusManager.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Dimens.xl + LocalFloatingPlayerHeight.current)) {
        item(key = "title") { ScreenTitle("Search", pad) }
        item(key = "field") {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = pad).widthIn(max = 720.dp),
                placeholder = { Text("Songs, albums, artists, playlists") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }
        when (status) {
            SearchStatus.Idle -> item(key = "idle") {
                EmptyState("Search your library", "Find songs, albums, artists and playlists on your server.")
            }
            SearchStatus.Loading -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(horizontal = pad, vertical = Dimens.l)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            is SearchStatus.Failed -> item(key = "failed") { EmptyState("Search unavailable", status.message) }
            is SearchStatus.Done -> results(status.results, query, pad, navigator, player)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.results(
    results: SearchResults,
    query: String,
    pad: androidx.compose.ui.unit.Dp,
    navigator: AppNavigator,
    player: PlayerController,
) {
    if (results.fromCache) {
        item(key = "cached") {
            Text(
                "Your server couldn't be reached, so only saved music was searched.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = pad, vertical = Dimens.s),
            )
        }
    }
    if (results.isEmpty) {
        item(key = "none") { EmptyState("No results", "Nothing matches “${query.trim()}”.") }
        return
    }
    val actions = SongActions(
        onPlayNext = player::playNext,
        onAddToQueue = player::addToQueue,
        onOpenAlbum = { s -> s.albumId?.let(navigator::openAlbum) },
        onOpenArtist = { s -> s.artistId?.let(navigator::openArtist) },
    )
    if (results.songs.isNotEmpty()) {
        item(key = "songs-h") { SectionHeader("Songs", Modifier.padding(horizontal = pad, vertical = Dimens.xs)) }
        itemsIndexed(results.songs, key = { _, s -> "song-${s.id}" }) { i, song ->
            SongRow(
                song,
                onClick = { player.play(results.songs, i, PlaybackSource.Songs("Search results")) },
                actions = actions,
                horizontalPadding = pad,
            )
        }
    }
    if (results.artists.isNotEmpty()) {
        item(key = "artists-h") { SectionHeader("Artists", Modifier.padding(horizontal = pad, vertical = Dimens.xs)) }
        items(results.artists, key = { "artist-${it.id}" }) { a ->
            MediaRow(a.name, "Artist", a.artwork, { navigator.openArtist(a.id) }, circular = true, horizontalPadding = pad)
        }
    }
    if (results.albums.isNotEmpty()) {
        item(key = "albums-h") { SectionHeader("Albums", Modifier.padding(horizontal = pad, vertical = Dimens.xs)) }
        items(results.albums, key = { "album-${it.id}" }) { a ->
            MediaRow(a.name, dotJoin("Album", a.artist, a.year?.toString()), a.artwork, { navigator.openAlbum(a.id) }, horizontalPadding = pad)
        }
    }
    if (results.playlists.isNotEmpty()) {
        item(key = "playlists-h") { SectionHeader("Playlists", Modifier.padding(horizontal = pad, vertical = Dimens.xs)) }
        items(results.playlists, key = { "playlist-${it.id}" }) { p ->
            MediaRow(p.name, dotJoin("Playlist", p.songCount?.let(::songCount)), p.artwork, { navigator.openPlaylist(p.id) }, horizontalPadding = pad)
        }
    }
}
