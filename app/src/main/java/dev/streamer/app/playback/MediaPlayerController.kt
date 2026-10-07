package dev.streamer.app.playback

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.streamer.app.data.Connection
import dev.streamer.app.data.UserMessages
import dev.streamer.app.data.account.AccountRepository
import dev.streamer.app.data.account.SessionState
import dev.streamer.app.model.Song
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * App-side [PlayerController]: a MediaController connected to [PlaybackService].
 * Mirrors the session's player into [state] for every UI surface, saves the
 * queue, and restores it paused after the process restarts (never autoplays).
 * Must be used from the main thread.
 */
@OptIn(UnstableApi::class) // Refers to the service class, which uses unstable session APIs.
class MediaPlayerController(
    context: Context,
    private val scope: CoroutineScope,
    private val accounts: AccountRepository,
    private val store: QueueStore,
    private val onPlayed: (Song, PlaybackSource?) -> Unit,
    private val messages: UserMessages,
    private val connection: StateFlow<Connection>,
    private val isDownloaded: (accountId: String, songId: String) -> Boolean,
    /** A song failed to load because the server couldn't be reached. */
    private val onServerUnreachable: () -> Unit = {},
) : PlayerController {
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    /** Where the current queue came from; app-side only. */
    private var source: PlaybackSource? = null

    /** Unique across restarts: restored items keep their (older, smaller) IDs. */
    private var nextOccurrenceId = System.currentTimeMillis() * 1_000

    /** Queue in play order (shuffled order when shuffle is on) and the matching timeline indices. */
    private var queue: List<QueueItem> = emptyList()
    private var playOrder: IntArray = IntArray(0)
    private var lastRecordedOccurrence: Long? = null

    /** Unplayable songs skipped in a row; stops skipping once the whole queue has failed. */
    private var consecutiveFailures = 0
    private var saveJob: Job? = null

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            { runCatching { future.get() }.getOrNull()?.let(::onConnected) },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun onConnected(c: MediaController) {
        controller = c
        c.addListener(
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
                        rebuildQueue(c)
                    }
                    if (events.contains(Player.EVENT_PLAYER_ERROR)) skipUnplayable(c)
                    if (c.isPlaying) consecutiveFailures = 0
                    publish(c)
                    recordIfStarted(c)
                    scheduleSave()
                }
            },
        )
        rebuildQueue(c)
        publish(c)
        while (pending.isNotEmpty()) pending.removeFirst()(c)
        scope.launch { restoreIfEmpty(c) }
        scope.launch {
            var ticks = 0
            while (isActive) {
                delay(500.milliseconds)
                if (c.isPlaying) {
                    publish(c) // Position.
                    if (++ticks % 20 == 0) scheduleSave()
                }
            }
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let(block) ?: pending.addLast(block)
    }

    // --- State mirroring ---

    private fun rebuildQueue(c: MediaController) {
        val timeline = c.currentTimeline
        val count = c.mediaItemCount
        val order = if (c.shuffleModeEnabled && count > 0 && !timeline.isEmpty) {
            buildList {
                var i = timeline.getFirstWindowIndex(true)
                while (i != C.INDEX_UNSET && size < count) {
                    add(i)
                    i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
                }
            }
        } else {
            (0 until count).toList()
        }
        val pairs = order.mapNotNull { index -> c.getMediaItemAt(index).toQueueItem()?.let { index to it } }
        playOrder = pairs.map { it.first }.toIntArray()
        queue = pairs.map { it.second }
    }

    private fun publish(c: MediaController) {
        val index = playOrder.indexOf(c.currentMediaItemIndex)
        val current = queue.getOrNull(index)
        val playbackState = c.playbackState
        _state.value = PlayerState(
            queue = queue,
            currentIndex = index,
            isPlaying = c.playWhenReady && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED,
            isBuffering = c.playWhenReady && playbackState == Player.STATE_BUFFERING,
            position = c.currentPosition.coerceAtLeast(0).milliseconds,
            duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.milliseconds ?: current?.song?.duration,
            shuffle = c.shuffleModeEnabled,
            repeat = when (c.repeatMode) {
                Player.REPEAT_MODE_ALL -> RepeatMode.All
                Player.REPEAT_MODE_ONE -> RepeatMode.One
                else -> RepeatMode.Off
            },
            source = if (queue.isEmpty()) null else source,
            error = c.playerError?.let(::describe),
        )
    }

    /** Records a play once per queue occurrence, when it actually starts playing. */
    private fun recordIfStarted(c: MediaController) {
        if (!c.isPlaying) return
        val item = c.currentMediaItem?.toQueueItem() ?: return
        if (item.occurrenceId == lastRecordedOccurrence) return
        lastRecordedOccurrence = item.occurrenceId
        onPlayed(item.song, source)
    }

    // --- Persistence ---

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1_000.milliseconds)
            val c = controller ?: return@launch
            val items = (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).toQueueItem() }
            val accountId = c.currentMediaItem?.accountId ?: (0 until c.mediaItemCount).firstNotNullOfOrNull { c.getMediaItemAt(it).accountId }
            if (items.isEmpty() || accountId == null) {
                store.clear()
                return@launch
            }
            store.save(
                SavedQueue(
                    accountId = accountId,
                    items = items.map(SavedItem::from),
                    currentOccurrence = c.currentMediaItem?.mediaId?.toLongOrNull(),
                    positionMs = c.currentPosition.coerceAtLeast(0),
                    shuffle = c.shuffleModeEnabled,
                    repeat = _state.value.repeat,
                    source = source?.let(SavedSource::from),
                ),
            )
        }
    }

    private suspend fun restoreIfEmpty(c: MediaController) {
        if (c.mediaItemCount > 0) return // The service kept playing; nothing to restore.
        val session = accounts.state.first { it != SessionState.Loading }
        val accountId = (session as? SessionState.Active)?.account?.id ?: return
        val saved = store.load() ?: return
        if (saved.accountId != accountId || saved.items.isEmpty() || c.mediaItemCount > 0) return
        val items = saved.items.map { it.toSong(accountId).toMediaItem(it.occurrenceId, it.sourceIndex, accountId) }
        val start = saved.items.indexOfFirst { it.occurrenceId == saved.currentOccurrence }.coerceAtLeast(0)
        nextOccurrenceId = maxOf(nextOccurrenceId, saved.items.maxOf { it.occurrenceId } + 1)
        source = saved.source?.toSource()
        c.shuffleModeEnabled = false
        c.setMediaItems(items, start, saved.positionMs)
        c.repeatMode = saved.repeat.toPlayer()
        if (saved.shuffle) c.shuffleModeEnabled = true
        // Deliberately not prepared or played: no network use and no surprise playback.
    }

    // --- Commands ---

    override fun play(songs: List<Song>, startIndex: Int, source: PlaybackSource?, shuffle: Boolean, sourcePositions: List<Int>?) {
        if (songs.isEmpty()) return
        val accountId = songs.first().artwork.accountId ?: activeAccountId() ?: return
        val conn = connection.value
        if (conn != Connection.Online) {
            // Without the server only downloaded songs can play; say what was left out.
            val why = when (conn) {
                Connection.OfflineMode -> "offline mode is on"
                Connection.NoNetwork -> "there's no connection"
                else -> "your server can't be reached"
            }
            val keep = songs.indices.filter { isDownloaded(songs[it].artwork.accountId ?: accountId, songs[it].id) }
            if (keep.isEmpty()) {
                messages.post(if (songs.size == 1) "That song isn't downloaded, and $why." else "None of these songs are downloaded, and $why.")
                return
            }
            if (keep.size < songs.size) messages.post("Playing the ${keep.size} downloaded of ${songs.size} songs, as $why.")
            val start = keep.indexOfFirst { it >= startIndex }.takeIf { it >= 0 } ?: 0
            return playFiltered(keep.map { songs[it] }, start, source, shuffle, keep.map { sourcePositions?.getOrNull(it) ?: it }, accountId)
        }
        playFiltered(songs, startIndex, source, shuffle, sourcePositions, accountId)
    }

    private fun playFiltered(
        songs: List<Song>,
        startIndex: Int,
        source: PlaybackSource?,
        shuffle: Boolean,
        sourcePositions: List<Int>?,
        accountId: String,
    ) {
        consecutiveFailures = 0
        val items = songs.mapIndexed { i, song ->
            song.toMediaItem(nextOccurrenceId++, sourcePositions?.getOrNull(i) ?: i, song.artwork.accountId ?: accountId)
        }
        this.source = source
        withController { c ->
            when {
                // Shuffle button: shuffle the whole list, starting anywhere.
                shuffle -> {
                    c.shuffleModeEnabled = true
                    c.setMediaItems(items, true)
                }
                // Shuffle already on: start with the chosen song, then shuffle the rest.
                c.shuffleModeEnabled -> {
                    c.shuffleModeEnabled = false
                    c.setMediaItems(items, startIndex.coerceIn(items.indices), 0)
                    c.shuffleModeEnabled = true
                }
                else -> c.setMediaItems(items, startIndex.coerceIn(items.indices), 0)
            }
            c.prepare()
            c.play()
        }
    }

    override fun togglePlayPause() = withController { c ->
        if (c.mediaItemCount == 0) return@withController
        if (c.playWhenReady && c.playbackState != Player.STATE_IDLE && c.playbackState != Player.STATE_ENDED) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare() // Also retries after an error.
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(c.currentTimeline.getFirstWindowIndex(c.shuffleModeEnabled))
            c.play()
        }
    }

    override fun next() = withController { it.seekToNext() }

    // Restarts the song if past the first few seconds, otherwise goes to the previous one.
    override fun previous() = withController { it.seekToPrevious() }

    override fun seekTo(position: kotlin.time.Duration) = withController { it.seekTo(position.inWholeMilliseconds) }

    override fun setShuffle(enabled: Boolean) = withController { it.shuffleModeEnabled = enabled }

    override fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun skipTo(occurrenceId: Long) = withController { c ->
        val index = indexOf(c, occurrenceId) ?: return@withController
        c.seekTo(index, 0)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    override fun playNext(song: Song) {
        val accountId = song.artwork.accountId ?: activeAccountId() ?: return
        withController { c ->
            if (c.mediaItemCount == 0) return@withController play(listOf(song))
            c.addMediaItem(c.currentMediaItemIndex + 1, song.toMediaItem(nextOccurrenceId++, null, accountId))
        }
    }

    override fun addToQueue(song: Song) {
        val accountId = song.artwork.accountId ?: activeAccountId() ?: return
        withController { c ->
            if (c.mediaItemCount == 0) return@withController play(listOf(song))
            c.addMediaItem(song.toMediaItem(nextOccurrenceId++, null, accountId))
        }
    }

    /** Reordering applies to the unshuffled queue; the UI offers it only with shuffle off. */
    override fun moveUpcoming(occurrenceId: Long, offset: Int) = withController { c ->
        if (c.shuffleModeEnabled) return@withController
        val from = indexOf(c, occurrenceId) ?: return@withController
        val current = c.currentMediaItemIndex
        if (from <= current) return@withController
        val to = (from.toLong() + offset).coerceIn((current + 1).toLong(), (c.mediaItemCount - 1).toLong()).toInt()
        if (to != from) c.moveMediaItem(from, to)
    }

    override fun remove(occurrenceId: Long) = withController { c ->
        val index = indexOf(c, occurrenceId) ?: return@withController
        if (index != c.currentMediaItemIndex) c.removeMediaItem(index)
    }

    override fun clearQueue() {
        source = null
        withController { c ->
            c.stop()
            c.clearMediaItems()
        }
        scope.launch { store.clear() }
    }

    /**
     * A song that can't be loaded (offline, server unreachable, not downloaded)
     * is skipped with a message, once per song; after the whole queue has
     * failed, playback stops with the reason instead of looping.
     */
    private fun skipUnplayable(c: MediaController) {
        val error = c.playerError ?: return
        if (error.errorCode !in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..2999) return
        val title = c.currentMediaItem?.mediaMetadata?.title ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        ) {
            onServerUnreachable()
        }
        consecutiveFailures++
        if (consecutiveFailures >= c.mediaItemCount || !c.hasNextMediaItem()) {
            consecutiveFailures = 0
            messages.post("Couldn't play \u201c$title\u201d: ${describe(error).removeSuffix(" Tap play to retry.")}")
            return
        }
        messages.post("Skipped \u201c$title\u201d: ${unavailableReason(error)}")
        c.seekToNextMediaItem()
        c.prepare()
        c.play()
    }

    private fun unavailableReason(error: PlaybackException): String =
        if (error.cause is OfflineModeException) "it isn't downloaded." else "it isn't downloaded and your server can't be reached."

    private fun indexOf(c: MediaController, occurrenceId: Long): Int? {
        val id = occurrenceId.toString()
        return (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == id }
    }

    private fun activeAccountId() = (accounts.state.value as? SessionState.Active)?.account?.id

    private fun RepeatMode.toPlayer() = when (this) {
        RepeatMode.Off -> Player.REPEAT_MODE_OFF
        RepeatMode.All -> Player.REPEAT_MODE_ALL
        RepeatMode.One -> Player.REPEAT_MODE_ONE
    }

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "Couldn't reach your server to play this song."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Your server refused to stream this song."
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "Unencrypted HTTP isn't allowed for this server."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        -> "This song couldn't be played: its format isn't supported, or the server returned an error."
        else -> error.cause?.message?.takeIf { it.isNotBlank() } ?: "Playback failed."
    } + " Tap play to retry."
}
