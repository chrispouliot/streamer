package dev.streamer.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.playback.PlayerState
import dev.streamer.app.ui.components.CoverArt
import dev.streamer.app.ui.components.FavoriteButton
import dev.streamer.app.ui.components.Favorites
import dev.streamer.app.ui.icons.AppIcons
import dev.streamer.app.ui.theme.Dimens
import dev.streamer.app.ui.theme.playerTint

@Composable
fun MiniPlayer(
    state: PlayerState,
    player: PlayerController,
    favorites: Favorites,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** Applied to the visible card itself (inside the outer margin). */
    cardModifier: Modifier = Modifier,
) {
    if (state.current == null) return
    MiniPlayerCard(
        state,
        player,
        favorites,
        onOpen,
        modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.s, vertical = Dimens.xs)
            .then(cardModifier),
    )
}

/** The mini-player card without its outer margin; also drawn by the player sheet as it collapses. */
@Composable
fun MiniPlayerCard(
    state: PlayerState,
    player: PlayerController,
    favorites: Favorites,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = state.current ?: return
    val song = item.song
    Surface(
        onClick = onOpen,
        modifier = modifier
            .semantics { contentDescription = "Now playing: ${song.title} by ${song.artist}. Open player" },
        shape = MaterialTheme.shapes.medium,
        color = playerTint(song.artwork),
        contentColor = MaterialTheme.colorScheme.onSurface,
        // Flat, as in the mockups; a shadow also changed visibly when the player morphs into this card.
    ) {
        Column {
            Row(
                Modifier.padding(start = Dimens.s, end = Dimens.xs, top = Dimens.s, bottom = Dimens.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverArt(song.artwork, Modifier.size(Dimens.miniPlayerArt), MaterialTheme.shapes.small)
                Spacer(Modifier.width(Dimens.m))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        state.error ?: song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FavoriteButton(song, favorites)
                Box(Modifier.size(Dimens.minTouchTarget), contentAlignment = Alignment.Center) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = player::togglePlayPause) {
                            Icon(
                                if (state.isPlaying) AppIcons.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (state.isPlaying) "Pause" else "Play",
                            )
                        }
                    }
                }
            }
            val total = state.duration?.inWholeMilliseconds?.takeIf { it > 0 }
            LinearProgressIndicator(
                progress = { total?.let { (state.position.inWholeMilliseconds.toFloat() / it).coerceIn(0f, 1f) } ?: 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.onSurface,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}
