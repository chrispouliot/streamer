package dev.streamer.app.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.playback.PlayerState
import dev.streamer.app.ui.components.Favorites
import dev.streamer.app.ui.navigation.AppNavigator
import dev.streamer.app.ui.navigation.LocalShellLayout
import dev.streamer.app.ui.theme.Dimens

/** Separate queue screen for windows without room for the side-by-side player. */
@Composable
fun QueueScreen(state: PlayerState, player: PlayerController, favorites: Favorites, navigator: AppNavigator) {
    if (LocalShellLayout.current.playerPaneFits) {
        // The expanded player already shows the queue beside it.
        NowPlayingScreen(state, player, favorites, navigator)
        return
    }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(Dimens.xs), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = navigator::back) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Queue", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (state.hasQueue) {
                TextButton(onClick = { confirmClear = true }) { Text("Clear") }
            }
        }
        QueueList(state, player, Modifier.weight(1f))
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear queue?") },
            text = { Text("Playback stops and the queue is emptied. Playlists on the server are not changed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    player.clearQueue()
                    navigator.closePlayer()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
