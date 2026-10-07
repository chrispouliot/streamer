package dev.streamer.app.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.streamer.app.data.NetworkPolicy
import dev.streamer.app.data.UserMessages
import dev.streamer.app.data.downloads.DownloadRepository
import dev.streamer.app.data.downloads.DownloadedCollection
import dev.streamer.app.data.downloads.SongDownload
import dev.streamer.app.data.downloads.SongDownloadState
import dev.streamer.app.model.Song
import dev.streamer.app.playback.PlaybackSource
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.ui.components.ArtSize
import dev.streamer.app.ui.components.BackBar
import dev.streamer.app.ui.components.CoverArt
import dev.streamer.app.ui.components.EmptyState
import dev.streamer.app.ui.components.ScreenTitle
import dev.streamer.app.ui.components.SectionHeader
import dev.streamer.app.ui.components.SongActions
import dev.streamer.app.ui.components.SongRow
import dev.streamer.app.ui.components.appViewModel
import dev.streamer.app.ui.navigation.AppNavigator
import dev.streamer.app.ui.navigation.LocalFloatingPlayerHeight
import dev.streamer.app.ui.navigation.LocalShellLayout
import dev.streamer.app.ui.theme.Dimens
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class DownloadsViewModel(private val repository: DownloadRepository, private val messages: UserMessages, policy: NetworkPolicy) : ViewModel() {
    private fun <T> stateOf(flow: kotlinx.coroutines.flow.Flow<T>, initial: T): StateFlow<T> =
        flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val collections: StateFlow<List<DownloadedCollection>> = repository.downloadedCollections
    val songs: StateFlow<List<Song>> = repository.individualSongs
    val active = stateOf(repository.activeDownloads, emptyList())
    val failed = stateOf(repository.failedCount, 0)
    val waiting: StateFlow<Boolean> = repository.waitingForNetwork
    val offline: StateFlow<Boolean> = policy.offlineOnly
    val storage = stateOf(repository.downloads.debounce(1_000).mapLatest { repository.storageUsedBytes() }, 0L)

    fun pauseAll() = repository.pauseAll()
    fun resumeAll() = repository.resumeAll()
    fun retry() = repository.retryFailed()
    fun actionsFor(collection: DownloadedCollection) = collection.actions(repository, messages, viewModelScope)

    /** Cancels one song's download; collections that included it become partly downloaded. */
    fun cancel(song: Song) = viewModelScope.launch { repository.cancelSong(song.id) }
}

@Composable
fun DownloadsRoute(navigator: AppNavigator, player: PlayerController) {
    val vm = appViewModel { DownloadsViewModel(it.downloads, it.messages, it.policy) }
    val layout = LocalShellLayout.current
    val collections by vm.collections.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val waiting by vm.waiting.collectAsStateWithLifecycle()
    val offline by vm.offline.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val downloads = rememberDownloadsUi()
    val pad = layout.pagePadding
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Dimens.xl + LocalFloatingPlayerHeight.current)) {
        if (!layout.usesRail) item { BackBar(navigator::back) }
        item { ScreenTitle("Downloads", pad) }
        item {
            Column(Modifier.padding(horizontal = pad), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                Text("${formatBytes(storage)} used on this device", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    offline -> Notice("Offline mode is on, so downloads are paused.")
                    waiting && active.isNotEmpty() -> Notice("Waiting for Wi-Fi. You can allow mobile data in Settings.")
                }
            }
        }
        if (active.isNotEmpty() || failed > 0) {
            item {
                Row(Modifier.padding(horizontal = pad - Dimens.s)) {
                    if (active.isNotEmpty()) {
                        val paused = active.all { it.second.state == SongDownloadState.Paused }
                        TextButton(onClick = if (paused) vm::resumeAll else vm::pauseAll) { Text(if (paused) "Resume all" else "Pause all") }
                    }
                    if (failed > 0) TextButton(onClick = vm::retry) { Text("Retry $failed failed") }
                }
            }
        }
        if (active.isEmpty() && collections.isEmpty() && songs.isEmpty()) {
            item {
                EmptyState(
                    "No downloads",
                    "Download albums, playlists or songs from their menus to play them without a connection.",
                )
            }
        }
        if (active.isNotEmpty()) {
            item { SectionHeader("Downloading", Modifier.padding(horizontal = pad)) }
            items(active, key = { "active-${it.first.id}" }) { (song, download) -> ActiveDownloadRow(song, download, pad) { vm.cancel(song) } }
        }
        downloadedSections(collections, songs, pad, navigator, player, downloads, actionsFor = vm::actionsFor)
    }
}

/** Downloaded collections and individually downloaded songs; also used by Library's Downloaded filter. */
fun LazyListScope.downloadedSections(
    collections: List<DownloadedCollection>,
    songs: List<Song>,
    pad: androidx.compose.ui.unit.Dp,
    navigator: AppNavigator,
    player: PlayerController,
    downloads: DownloadsUi,
    actionsFor: (DownloadedCollection) -> CollectionDownloadActions,
) {
    if (collections.isNotEmpty()) {
        item { SectionHeader("Albums and playlists", Modifier.padding(horizontal = pad)) }
        items(collections, key = {
            when (it) {
                is DownloadedCollection.Album -> "album-${it.album.id}"
                is DownloadedCollection.Playlist -> "playlist-${it.playlist.id}"
            }
        }) { c -> DownloadedCollectionRow(c, pad, navigator, actionsFor(c)) }
    }
    if (songs.isNotEmpty()) {
        item { SectionHeader("Songs", Modifier.padding(horizontal = pad)) }
        val actions = SongActions(
            onOpenAlbum = { s -> s.albumId?.let(navigator::openAlbum) },
            onOpenArtist = { s -> s.artistId?.let(navigator::openArtist) },
            downloads = downloads,
        )
        itemsIndexed(songs, key = { _, s -> "song-${s.id}" }) { i, song ->
            SongRow(song, onClick = { player.play(songs, i, PlaybackSource.Songs("Downloaded songs")) }, actions = actions, horizontalPadding = pad)
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun ActiveDownloadRow(song: Song, download: SongDownload, pad: androidx.compose.ui.unit.Dp, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = pad, end = Dimens.xs, top = Dimens.s, bottom = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
        CoverArt(song.artwork, Modifier.size(Dimens.rowArt), MaterialTheme.shapes.small, size = ArtSize.Small)
        Spacer(Modifier.width(Dimens.m))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (download.state) {
                    SongDownloadState.Paused -> "Paused"
                    SongDownloadState.Queued -> "Queued"
                    else -> download.percent?.let { "${it.toInt()}%" } ?: "Downloading"
                } + " · ${song.artist}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Dimens.xs))
            val percent = download.percent
            if (download.state == SongDownloadState.Downloading && percent != null) {
                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
            } else if (download.state == SongDownloadState.Downloading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel download of ${song.title}") }
    }
}

@Composable
private fun DownloadedCollectionRow(
    collection: DownloadedCollection,
    pad: androidx.compose.ui.unit.Dp,
    navigator: AppNavigator,
    actions: CollectionDownloadActions,
) {
    val (title, artwork, open) = when (collection) {
        is DownloadedCollection.Album -> Triple(collection.album.name, collection.album.artwork) { navigator.openAlbum(collection.album.id) }
        is DownloadedCollection.Playlist -> Triple(collection.playlist.name, collection.playlist.artwork) { navigator.openPlaylist(collection.playlist.id) }
    }
    val removedRemotely = (collection as? DownloadedCollection.Playlist)?.removedRemotely == true
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !removedRemotely, onClick = open).padding(start = pad, end = Dimens.xs, top = Dimens.s, bottom = Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(artwork, Modifier.size(56.dp), MaterialTheme.shapes.small, size = ArtSize.Small)
        Spacer(Modifier.width(Dimens.m))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (removedRemotely) "No longer on the server · ${collection.status.describe()}" else collection.status.describe(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options for $title") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                CollectionDownloadMenuItems(collection.status, actions) { menu = false }
            }
        }
    }
}
