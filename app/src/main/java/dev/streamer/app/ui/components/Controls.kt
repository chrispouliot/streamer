package dev.streamer.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.streamer.app.playback.RepeatMode
import dev.streamer.app.ui.icons.AppIcons

/** Large rounded-square play/pause button used by player surfaces and detail headers. */
@Composable
fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    isBuffering: Boolean = false,
) {
    val label = if (isPlaying) "Pause" else "Play"
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(size)
            .semantics { contentDescription = label },
        shape = MaterialTheme.shapes.extraLarge.let { if (size < 64.dp) MaterialTheme.shapes.large else it },
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isBuffering) {
                CircularProgressIndicator(Modifier.size(size * 0.4f), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp)
            } else {
                Icon(
                    if (isPlaying) AppIcons.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(size * 0.45f),
                )
            }
        }
    }
}

@Composable
fun ShuffleButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ToggleIconButton(
        active = enabled,
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = "Shuffle"
            stateDescription = if (enabled) "On" else "Off"
        },
    ) { Icon(AppIcons.Shuffle, contentDescription = null) }
}

@Composable
fun RepeatButton(mode: RepeatMode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ToggleIconButton(
        active = mode != RepeatMode.Off,
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = "Repeat"
            stateDescription = when (mode) {
                RepeatMode.Off -> "Off"
                RepeatMode.All -> "Repeat all"
                RepeatMode.One -> "Repeat one"
            }
        },
    ) { Icon(if (mode == RepeatMode.One) AppIcons.RepeatOne else AppIcons.Repeat, contentDescription = null) }
}

/** Icon button whose "on" state is shown with a filled tonal background, not colour alone. */
@Composable
private fun ToggleIconButton(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        colors = if (active) {
            IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), containerColor = Color.Transparent)
        },
    ) { content() }
}
