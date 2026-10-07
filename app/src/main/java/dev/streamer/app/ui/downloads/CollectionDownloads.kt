package dev.streamer.app.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.streamer.app.data.UserMessages
import dev.streamer.app.data.downloads.CollectionDownloadStatus
import dev.streamer.app.data.downloads.DownloadRepository
import dev.streamer.app.data.downloads.DownloadedCollection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Download state and actions for one album or playlist, owned by its screen's ViewModel. */
class CollectionDownloads(
    private val repository: DownloadRepository,
    private val messages: UserMessages,
    private val kind: String,
    private val id: String,
    private val owner: ViewModel,
) {
    val status: StateFlow<CollectionDownloadStatus?> =
        repository.collectionStatus(kind, id).stateIn(owner.viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val actions = collectionDownloadActions(repository, messages, owner.viewModelScope, kind, id)
}

/** Download actions for one album or playlist, run in [scope]; failures are shown as messages. */
fun collectionDownloadActions(
    repository: DownloadRepository,
    messages: UserMessages,
    scope: CoroutineScope,
    kind: String,
    id: String,
): CollectionDownloadActions {
    fun run(block: suspend () -> String?) {
        scope.launch { block()?.let(messages::post) }
    }
    return CollectionDownloadActions(
        isPlaylist = kind == DownloadRepository.PLAYLIST,
        download = { run { repository.downloadCollection(kind, id) } },
        stop = { run { repository.stopCollection(kind, id); null } },
        resume = { run { repository.resumeCollection(kind, id) } },
        remove = { run { repository.removeCollection(kind, id); null } },
        update = { run { repository.updateCollection(kind, id) } },
        retry = { repository.retryFailed() },
        setKeepUpdated = { enabled -> run { repository.setKeepUpdated(id, enabled) } },
    )
}

/** Download actions for an entry in the downloads list. */
fun DownloadedCollection.actions(repository: DownloadRepository, messages: UserMessages, scope: CoroutineScope) = when (this) {
    is DownloadedCollection.Album -> collectionDownloadActions(repository, messages, scope, DownloadRepository.ALBUM, album.id)
    is DownloadedCollection.Playlist -> collectionDownloadActions(repository, messages, scope, DownloadRepository.PLAYLIST, playlist.id)
}
