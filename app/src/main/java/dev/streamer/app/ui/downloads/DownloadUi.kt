package dev.streamer.app.ui.downloads

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.streamer.app.LocalAppContainer
import dev.streamer.app.data.UserMessages
import dev.streamer.app.data.downloads.CollectionDownloadStatus
import dev.streamer.app.data.downloads.DownloadRepository
import dev.streamer.app.model.Song
import dev.streamer.app.ui.icons.AppIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Snapshot of completely downloaded songs plus per-song download actions, for lists and menus. */
class DownloadsUi(
    private val ids: Set<String>,
    private val repository: DownloadRepository?,
    private val scope: CoroutineScope?,
    private val messages: UserMessages?,
    private val beforeDownload: () -> Unit,
) {
    fun isDownloaded(song: Song): Boolean = song.id in ids

    fun download(song: Song) {
        beforeDownload()
        scope?.launch { repository?.downloadSong(song)?.let { messages?.post(it) } }
    }

    fun remove(song: Song) {
        scope?.launch { repository?.removeSong(song.id) }
    }

    companion object {
        /** For previews and tests. */
        val None = DownloadsUi(emptySet(), null, null, null) {}
    }
}

@Composable
fun rememberDownloadsUi(): DownloadsUi {
    val container = LocalAppContainer.current
    val ids by container.downloads.downloadedSongIds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val askPermission = rememberNotificationPermissionRequest()
    return remember(ids, container, scope) { DownloadsUi(ids, container.downloads, scope, container.messages, askPermission) }
}

/**
 * Asks once (per screen instance) for notification permission so download
 * progress can be shown. Downloads work without it.
 */
@Composable
fun rememberNotificationPermissionRequest(): () -> Unit {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** One-line description of a collection's download state, or null when not downloaded. */
fun CollectionDownloadStatus.describe(): String = when {
    stopped && inProgress == 0 -> "Partly downloaded · $completed of $total"
    inProgress > 0 && waitingForNetwork -> "Waiting for Wi-Fi · $completed of $total downloaded"
    inProgress > 0 -> "Downloading · $completed of $total"
    outOfDate && keepUpdated -> "Updating downloads…"
    outOfDate -> "Downloads out of date · $completed of $total downloaded"
    failed > 0 -> "$failed failed · $completed of $total downloaded"
    isComplete -> if (keepUpdated) "Downloaded · Keeps up to date" else "Downloaded"
    else -> "$completed of $total downloaded"
}

/** Actions available from a collection's download button. */
class CollectionDownloadActions(
    val isPlaylist: Boolean,
    val download: () -> Unit,
    /** Stop partway, keeping finished songs. */
    val stop: () -> Unit,
    val resume: () -> Unit,
    val remove: () -> Unit,
    val update: () -> Unit,
    val retry: () -> Unit,
    val setKeepUpdated: (Boolean) -> Unit,
)

/**
 * Download control for album/playlist headers: downloads on first tap, then
 * offers update, retry, Keep updated (playlists) and removal.
 */
@Composable
fun CollectionDownloadButton(status: CollectionDownloadStatus?, actions: CollectionDownloadActions, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val askPermission = rememberNotificationPermissionRequest()
    Box(modifier) {
        IconButton(
            onClick = {
                if (status == null) {
                    askPermission()
                    actions.download()
                } else {
                    open = true
                }
            },
        ) {
            when {
                status == null -> Icon(AppIcons.Download, contentDescription = "Download")
                status.inProgress > 0 -> CircularProgressIndicator(
                    progress = { if (status.total == 0) 0f else status.completed.toFloat() / status.total },
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                )
                status.failed > 0 || (status.outOfDate && !status.keepUpdated) ->
                    Icon(Icons.Filled.Warning, contentDescription = "Download options: ${status.describe()}", tint = MaterialTheme.colorScheme.error)
                else -> Icon(AppIcons.DownloadDone, contentDescription = "Download options: ${status.describe()}")
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (status != null) {
                CollectionDownloadMenuItems(status, actions) { open = false }
            }
        }
    }
}

/** Menu entries for a downloaded collection, depending on its state. */
@Composable
fun CollectionDownloadMenuItems(status: CollectionDownloadStatus, actions: CollectionDownloadActions, dismiss: () -> Unit) {
    val downloading = status.inProgress > 0
    when {
        downloading -> {
            DropdownMenuItem(
                text = { TwoLine("Stop downloading", "Keeps the songs already downloaded") },
                onClick = { dismiss(); actions.stop() },
            )
            DropdownMenuItem(
                text = { TwoLine("Cancel and remove", "Deletes the songs downloaded here") },
                onClick = { dismiss(); actions.remove() },
            )
        }
        status.stopped -> {
            DropdownMenuItem(text = { Text("Resume download") }, onClick = { dismiss(); actions.resume() })
            DropdownMenuItem(text = { Text("Remove download") }, onClick = { dismiss(); actions.remove() })
        }
        else -> {
            if (status.outOfDate && !status.keepUpdated) {
                DropdownMenuItem(text = { Text("Update downloads") }, onClick = { dismiss(); actions.update() })
            }
            if (status.failed > 0) {
                DropdownMenuItem(text = { Text("Retry failed downloads") }, onClick = { dismiss(); actions.retry() })
            }
            DropdownMenuItem(text = { Text("Remove download") }, onClick = { dismiss(); actions.remove() })
        }
    }
    if (actions.isPlaylist && !status.stopped) {
        DropdownMenuItem(
            text = { Text("Keep updated") },
            onClick = { dismiss(); actions.setKeepUpdated(!status.keepUpdated) },
            trailingIcon = { if (status.keepUpdated) Icon(Icons.Filled.Check, contentDescription = "On") },
        )
    }
}

@Composable
private fun TwoLine(title: String, detail: String) {
    Column {
        Text(title)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "1.2 GB", "350 MB". */
fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "%.0f MB".format(mb)
}
