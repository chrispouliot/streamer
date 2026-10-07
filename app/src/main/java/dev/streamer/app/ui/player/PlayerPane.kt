package dev.streamer.app.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.playback.PlayerState
import dev.streamer.app.ui.components.ArtSize
import dev.streamer.app.ui.components.CoverArt
import dev.streamer.app.ui.components.Favorites
import dev.streamer.app.ui.components.SongLeading
import dev.streamer.app.ui.components.SongRow
import dev.streamer.app.ui.icons.AppIcons
import dev.streamer.app.ui.theme.Dimens

/** Persistent right-hand player for wide windows. Replaces the mini-player there. */
@Composable
fun PlayerPane(
    state: PlayerState,
    player: PlayerController,
    favorites: Favorites,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    /** Applied to the visible panel itself (inside the outer margin). */
    panelModifier: Modifier = Modifier,
) {
    if (state.current == null) return
    PlayerPanePanel(
        state,
        player,
        favorites,
        onExpand,
        modifier
            .width(Dimens.playerPaneWidth)
            .fillMaxHeight()
            .safeDrawingPadding()
            .padding(top = Dimens.l, end = Dimens.l, bottom = Dimens.l)
            .then(panelModifier),
    )
}

/** The pane panel without its outer margin; also drawn by the player sheet as it collapses. */
@Composable
fun PlayerPanePanel(
    state: PlayerState,
    player: PlayerController,
    favorites: Favorites,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val song = state.current?.song ?: return
    Surface(
        modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(Dimens.xl)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Now playing", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(onClick = onExpand) { Icon(AppIcons.OpenInFull, contentDescription = "Open full player") }
            }
            Spacer(Modifier.height(Dimens.s))
            CoverArt(song.artwork, Modifier.fillMaxWidth().aspectRatio(1f), MaterialTheme.shapes.large, "Album artwork", ArtSize.Large)
            Spacer(Modifier.height(Dimens.l))
            TrackTitle(state, favorites, large = false)
            Spacer(Modifier.height(Dimens.s))
            SeekBar(state.position, state.duration, player::seekTo)
            TransportControls(state, player, playSize = 72.dp)
            val next = state.upNext.take(3)
            if (next.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.l))
                Text("Next up", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                next.forEach { item ->
                    SongRow(
                        item.song,
                        onClick = { player.skipTo(item.occurrenceId) },
                        leading = SongLeading.Artwork,
                        horizontalPadding = 0.dp,
                    )
                }
            }
        }
    }
}
