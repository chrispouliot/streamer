package dev.streamer.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import dev.streamer.app.model.Song
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.playback.PlayerState
import dev.streamer.app.ui.components.CoverArt
import dev.streamer.app.ui.components.EmptyState
import dev.streamer.app.ui.components.Favorites
import dev.streamer.app.ui.icons.AppIcons
import dev.streamer.app.ui.navigation.AppNavigator
import dev.streamer.app.ui.navigation.LocalShellLayout
import dev.streamer.app.ui.theme.Dimens
import dev.streamer.app.ui.theme.playerTint

/**
 * Full player. Phone-sized windows get a full-screen player with the queue on
 * a separate screen; when the player pane fits, the player sits beside Up Next.
 */
@Composable
fun NowPlayingScreen(state: PlayerState, player: PlayerController, favorites: Favorites, navigator: AppNavigator) {
    val current = state.current
    if (current == null) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            EmptyState("Nothing playing", "Choose a song, album or playlist to start listening.", actionLabel = "Back", onAction = navigator::back)
        }
        return
    }
    if (LocalShellLayout.current.playerPaneFits) {
        ExpandedNowPlaying(state, player, favorites, navigator)
    } else {
        CompactNowPlaying(state, player, favorites, navigator)
    }
}

@Composable
private fun CompactNowPlaying(state: PlayerState, player: PlayerController, favorites: Favorites, navigator: AppNavigator) {
    val song = state.current!!.song
    val tint = playerTint(song.artwork)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tint, MaterialTheme.colorScheme.background)))
            .safeDrawingPadding(),
    ) {
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize().padding(horizontal = Dimens.xl)) {
            PlayerTopBar(state, song, navigator, collapse = true)
            if (landscape) {
                Row(
                    Modifier.weight(1f).fillMaxWidth().padding(bottom = Dimens.l),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.xxl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverArt(song.artwork, Modifier.fillMaxHeight().aspectRatio(1f), MaterialTheme.shapes.large, "Album artwork")
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                        TrackTitle(state, favorites)
                        SeekBar(state.position, state.duration, player::seekTo)
                        TransportControls(state, player, playSize = 64.dp)
                        QueueShortcut(navigator)
                    }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = Dimens.l), contentAlignment = Alignment.Center) {
                    CoverArt(
                        song.artwork,
                        Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true),
                        MaterialTheme.shapes.large,
                        "Album artwork",
                    )
                }
                TrackTitle(state, favorites, Modifier.fillMaxWidth())
                Spacer(Modifier.height(Dimens.l))
                SeekBar(state.position, state.duration, player::seekTo)
                Spacer(Modifier.height(Dimens.s))
                TransportControls(state, player)
                QueueShortcut(navigator, Modifier.padding(vertical = Dimens.s))
            }
        }
    }
}

@Composable
private fun QueueShortcut(navigator: AppNavigator, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        IconButton(onClick = navigator::openQueue) {
            Icon(AppIcons.QueueMusic, contentDescription = "Queue")
        }
    }
}

@Composable
private fun PlayerTopBar(state: PlayerState, song: Song, navigator: AppNavigator, collapse: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = navigator::back) {
            if (collapse) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close player")
            } else {
                Icon(AppIcons.CloseFullscreen, contentDescription = "Exit full player")
            }
        }
        SourceHeader(state.source, Modifier.weight(1f))
        SongOverflow(song, navigator)
    }
}

@Composable
private fun SongOverflow(song: Song, navigator: AppNavigator) {
    if (song.albumId == null && song.artistId == null) {
        Spacer(Modifier.padding(Dimens.xl))
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            song.albumId?.let { id ->
                DropdownMenuItem(text = { Text("Go to album") }, onClick = { open = false; navigator.openAlbum(id) })
            }
            song.artistId?.let { id ->
                DropdownMenuItem(text = { Text("Go to artist") }, onClick = { open = false; navigator.openArtist(id) })
            }
        }
    }
}

@Composable
private fun ExpandedNowPlaying(state: PlayerState, player: PlayerController, favorites: Favorites, navigator: AppNavigator) {
    val song = state.current!!.song
    Row(
        Modifier.fillMaxSize().safeDrawingPadding().padding(Dimens.l),
        horizontalArrangement = Arrangement.spacedBy(Dimens.l),
    ) {
        Surface(
            Modifier.weight(1.4f).fillMaxHeight(),
            shape = MaterialTheme.shapes.extraLarge,
            color = playerTint(song.artwork),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(Modifier.padding(Dimens.l), horizontalAlignment = Alignment.CenterHorizontally) {
                PlayerTopBar(state, song, navigator, collapse = false)
                Column(
                    Modifier.weight(1f).widthIn(max = 560.dp).padding(horizontal = Dimens.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = Dimens.l), contentAlignment = Alignment.Center) {
                        CoverArt(
                            song.artwork,
                            Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true),
                            MaterialTheme.shapes.large,
                            "Album artwork",
                        )
                    }
                    TrackTitle(state, favorites, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(Dimens.l))
                    SeekBar(state.position, state.duration, player::seekTo)
                    TransportControls(state, player, Modifier.padding(vertical = Dimens.m))
                }
            }
        }
        Surface(
            Modifier.weight(1f).fillMaxHeight(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column {
                Text(
                    "Up next",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = Dimens.xl, top = Dimens.xl, end = Dimens.xl),
                )
                QueueList(state, player, Modifier.fillMaxSize(), horizontalPadding = Dimens.xl)
            }
        }
    }
}
