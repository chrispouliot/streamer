package dev.streamer.app.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.streamer.app.playback.PlaybackSource
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.playback.PlayerState
import dev.streamer.app.ui.components.FavoriteButton
import dev.streamer.app.ui.components.Favorites
import dev.streamer.app.ui.components.PlayPauseButton
import dev.streamer.app.ui.components.RepeatButton
import dev.streamer.app.ui.components.ShuffleButton
import dev.streamer.app.ui.components.formatClock
import dev.streamer.app.ui.icons.AppIcons
import dev.streamer.app.ui.theme.OverlineStyle
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Secondary text on player surfaces: the content colour at reduced alpha. On
 * artwork-tinted backgrounds this keeps more contrast than onSurfaceVariant.
 */
internal const val SECONDARY_ALPHA = 0.8f

fun PlaybackSource.overline(): String = when (this) {
    is PlaybackSource.Album -> "PLAYING FROM ALBUM"
    is PlaybackSource.Playlist -> "PLAYING FROM PLAYLIST"
    is PlaybackSource.Artist -> "PLAYING FROM ARTIST"
    is PlaybackSource.Songs -> "PLAYING FROM"
}

@Composable
fun SourceHeader(source: PlaybackSource?, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (source != null) {
            Text(
                source.overline(),
                style = OverlineStyle,
                color = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                source.title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun TrackTitle(state: PlayerState, favorites: Favorites, modifier: Modifier = Modifier, large: Boolean = true) {
    val song = state.current?.song ?: return
    val reportAnchor = LocalCollapseAnchor.current
    Row(
        modifier.then(if (reportAnchor != null) Modifier.onGloballyPositioned { reportAnchor(it.boundsInRoot()) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                state.error ?: song.artist,
                style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                color = if (state.error != null) MaterialTheme.colorScheme.error else LocalContentColor.current.copy(alpha = SECONDARY_ALPHA),
                maxLines = if (state.error != null) 3 else 1,
                modifier = if (state.error != null) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FavoriteButton(song, favorites)
    }
}

/** Seek slider with elapsed/total labels. Seeks once, when the drag ends. */
@Composable
fun SeekBar(position: Duration, duration: Duration?, onSeek: (Duration) -> Unit, modifier: Modifier = Modifier) {
    val totalMs = duration?.inWholeMilliseconds?.takeIf { it > 0 }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction
        ?: totalMs?.let { (position.inWholeMilliseconds.toFloat() / it).coerceIn(0f, 1f) }
        ?: 0f
    Column(modifier) {
        Slider(
            value = fraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                val f = dragFraction
                if (f != null && totalMs != null) onSeek((totalMs * f).toLong().milliseconds)
                dragFraction = null
            },
            enabled = totalMs != null,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onSurface,
                activeTrackColor = MaterialTheme.colorScheme.onSurface,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
            ),
            modifier = Modifier.semantics {
                contentDescription = "Seek"
                // Times, not just a percentage.
                stateDescription = "${position.formatClock()} of ${duration?.formatClock() ?: "unknown length"}"
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = if (dragFraction != null && totalMs != null) (totalMs * fraction).toLong().milliseconds else position
            val labels = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA)
            Text(shown.formatClock(), style = MaterialTheme.typography.labelMedium, color = labels, modifier = Modifier.clearAndSetSemantics { })
            Text(duration?.formatClock() ?: "–:––", style = MaterialTheme.typography.labelMedium, color = labels, modifier = Modifier.clearAndSetSemantics { })
        }
    }
}

@Composable
fun TransportControls(
    state: PlayerState,
    player: PlayerController,
    modifier: Modifier = Modifier,
    playSize: Dp = 88.dp,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShuffleButton(state.shuffle, onClick = { player.setShuffle(!state.shuffle) })
        IconButton(onClick = player::previous) {
            Icon(AppIcons.SkipPrevious, contentDescription = "Previous", Modifier.size(32.dp))
        }
        PlayPauseButton(state.isPlaying, onClick = player::togglePlayPause, size = playSize, isBuffering = state.isBuffering)
        IconButton(onClick = player::next) {
            Icon(AppIcons.SkipNext, contentDescription = "Next", Modifier.size(32.dp))
        }
        RepeatButton(state.repeat, onClick = player::cycleRepeat)
    }
}
