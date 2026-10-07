package dev.streamer.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.playback.PlayerState
import dev.streamer.app.playback.QueueItem
import dev.streamer.app.ui.components.SongRow
import dev.streamer.app.ui.theme.Dimens
import dev.streamer.app.ui.theme.OverlineStyle

/** Current entry plus upcoming entries, with per-entry move/remove actions. */
@Composable
fun QueueList(
    state: PlayerState,
    player: PlayerController,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = Dimens.l),
    horizontalPadding: androidx.compose.ui.unit.Dp = Dimens.pagePaddingCompact,
) {
    val current = state.current
    val upNext = state.upNext
    LazyColumn(modifier, contentPadding = contentPadding) {
        if (current != null) {
            item(key = "now-header") { QueueHeading("NOW PLAYING", horizontalPadding) }
            item(key = current.occurrenceId) {
                SongRow(current.song, onClick = null, isCurrent = true, horizontalPadding = horizontalPadding)
            }
        }
        item(key = "next-header") {
            val from = state.source?.title
            QueueHeading(if (from != null) "NEXT FROM ${from.uppercase()}" else "NEXT UP", horizontalPadding)
        }
        if (upNext.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing queued after this song.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = horizontalPadding, vertical = Dimens.s),
                )
            }
        }
        itemsIndexed(upNext, key = { _, item -> item.occurrenceId }) { index, item ->
            Row(Modifier.animateItem()) {
                SongRow(
                    item.song,
                    onClick = { player.skipTo(item.occurrenceId) },
                    modifier = Modifier.weight(1f),
                    horizontalPadding = horizontalPadding,
                    // Queue actions replace the song menu here.
                )
                QueueItemMenu(item, isFirst = index == 0, isLast = index == upNext.lastIndex, canReorder = !state.shuffle, player)
            }
        }
    }
}

@Composable
private fun QueueHeading(text: String, horizontalPadding: androidx.compose.ui.unit.Dp) {
    Text(
        text,
        style = OverlineStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = horizontalPadding, end = horizontalPadding, top = Dimens.l, bottom = Dimens.s)
            .semantics { heading() },
    )
}

@Composable
private fun QueueItemMenu(item: QueueItem, isFirst: Boolean, isLast: Boolean, canReorder: Boolean, player: PlayerController) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Queue options for ${item.song.title}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Play now") }, onClick = { open = false; player.skipTo(item.occurrenceId) })
            if (canReorder && !isFirst) {
                DropdownMenuItem(text = { Text("Move to top") }, onClick = { open = false; player.moveUpcoming(item.occurrenceId, Int.MIN_VALUE / 2) })
                DropdownMenuItem(text = { Text("Move up") }, onClick = { open = false; player.moveUpcoming(item.occurrenceId, -1) })
            }
            if (canReorder && !isLast) {
                DropdownMenuItem(text = { Text("Move down") }, onClick = { open = false; player.moveUpcoming(item.occurrenceId, 1) })
            }
            DropdownMenuItem(text = { Text("Remove from queue") }, onClick = { open = false; player.remove(item.occurrenceId) })
        }
    }
}
