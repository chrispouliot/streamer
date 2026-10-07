package dev.streamer.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.streamer.app.data.LibraryRepository
import dev.streamer.app.model.ArtistDetail
import dev.streamer.app.model.Song
import dev.streamer.app.playback.PlaybackSource
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.ui.components.ArtistArt
import dev.streamer.app.ui.components.BackBar
import dev.streamer.app.ui.components.EmptyState
import dev.streamer.app.ui.components.MediaCard
import dev.streamer.app.ui.components.PlayPauseButton
import dev.streamer.app.ui.components.SectionHeader
import dev.streamer.app.ui.components.ShuffleButton
import dev.streamer.app.ui.components.appViewModel
import dev.streamer.app.ui.navigation.AppNavigator
import dev.streamer.app.ui.navigation.LocalFloatingPlayerHeight
import dev.streamer.app.ui.navigation.LocalShellLayout
import dev.streamer.app.ui.navigation.topBar
import dev.streamer.app.ui.theme.Dimens
import dev.streamer.app.ui.theme.playerTint
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ArtistViewModel(private val library: LibraryRepository, private val id: String) : ViewModel() {
    val state: StateFlow<DetailState<ArtistDetail>> = library.artist(id)
        .map { if (it == null) DetailState.NotFound else DetailState.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState.Loading)

    /** Loads every album's tracks, in album order, then hands them to [onReady]. */
    fun withAllSongs(onReady: (List<Song>) -> Unit) {
        val artist = (state.value as? DetailState.Loaded)?.value ?: return
        viewModelScope.launch {
            val songs = artist.albums.flatMap { album -> library.album(album.id).first()?.songs.orEmpty() }
            if (songs.isNotEmpty()) onReady(songs)
        }
    }
}

@Composable
fun ArtistRoute(id: String, navigator: AppNavigator, player: PlayerController) {
    val vm = appViewModel(key = "artist-$id") { ArtistViewModel(it.library, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val playerState by player.state.collectAsStateWithLifecycle()
    val fromThis = (playerState.source as? PlaybackSource.Artist)?.id == id
    when (val s = state) {
        DetailState.Loading -> Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.topBar)) {
            BackBar(navigator::back)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        DetailState.NotFound -> Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.topBar)) {
            BackBar(navigator::back)
            EmptyState("Not available", "This artist could not be found on your server.")
        }
        is DetailState.Loaded -> {
            val source = PlaybackSource.Artist(id, s.value.summary.name)
            ArtistScreen(
                artist = s.value,
                isPlayingThis = fromThis && playerState.isPlaying,
                onBack = navigator::back,
                onPlay = { if (fromThis) player.togglePlayPause() else vm.withAllSongs { player.play(it, 0, source) } },
                onShuffle = { vm.withAllSongs { player.play(it, 0, source, shuffle = true) } },
                onOpenAlbum = navigator::openAlbum,
            )
        }
    }
}

@Composable
fun ArtistScreen(
    artist: ArtistDetail,
    isPlayingThis: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    val pad = LocalShellLayout.current.pagePadding
    val summary = artist.summary
    val gradient = Brush.verticalGradient(0f to playerTint(summary.artwork), 0.5f to MaterialTheme.colorScheme.background)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        // The tint starts behind the status bar; only the content is inset.
        modifier = Modifier.fillMaxSize().background(gradient).windowInsetsPadding(WindowInsets.topBar),
        contentPadding = PaddingValues(start = pad, end = pad, bottom = Dimens.xl + LocalFloatingPlayerHeight.current),
        horizontalArrangement = Arrangement.spacedBy(Dimens.l),
        verticalArrangement = Arrangement.spacedBy(Dimens.s),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                BackBar(onBack, Modifier.padding(start = 0.dp))
                ArtistArt(summary.artwork, Modifier.size(160.dp))
                Spacer(Modifier.height(Dimens.l))
                Text(summary.name, style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
                val count = artist.albums.size
                Text(
                    if (count == 1) "1 album" else "$count albums",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (artist.albums.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        ShuffleButton(enabled = false, onClick = onShuffle)
                        Spacer(Modifier.width(Dimens.s))
                        PlayPauseButton(isPlayingThis, onPlay, size = 64.dp)
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Albums") }
        if (artist.albums.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("No albums", null) }
        }
        items(artist.albums, key = { it.id }) { album ->
            MediaCard(album.name, album.year?.toString(), album.artwork, onClick = { onOpenAlbum(album.id) })
        }
    }
}
