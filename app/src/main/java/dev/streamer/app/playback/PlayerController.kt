package dev.streamer.app.playback

import dev.streamer.app.model.Song
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

enum class RepeatMode { Off, All, One }

/** Where the current queue came from, for the "Playing from" header. */
sealed interface PlaybackSource {
    val title: String

    data class Album(val id: String, override val title: String) : PlaybackSource
    data class Playlist(val id: String, override val title: String) : PlaybackSource
    data class Artist(val id: String, override val title: String) : PlaybackSource
    data class Songs(override val title: String) : PlaybackSource
}

/**
 * One queue occurrence. [occurrenceId] is unique within the queue so the same
 * song can appear more than once and still be selected, moved or removed.
 * [sourceIndex] is the entry's position in the album/playlist it was started
 * from, so duplicate playlist entries can be told apart; null for added songs.
 */
data class QueueItem(val occurrenceId: Long, val song: Song, val sourceIndex: Int? = null)

data class PlayerState(
    /** Entries in play order. */
    val queue: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val position: Duration = Duration.ZERO,
    val duration: Duration? = null,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    val source: PlaybackSource? = null,
    /** User-presentable playback error, if any. */
    val error: String? = null,
) {
    val current: QueueItem? get() = queue.getOrNull(currentIndex)
    val upNext: List<QueueItem> get() = if (currentIndex < 0) emptyList() else queue.drop(currentIndex + 1)
    val hasQueue: Boolean get() = queue.isNotEmpty()
}

/**
 * The single app-wide player. All player surfaces observe [state] and send
 * commands here; none of them own playback. Phase 3 implements this with a
 * service-owned Media3 player.
 */
interface PlayerController {
    val state: StateFlow<PlayerState>

    fun play(songs: List<Song>, startIndex: Int = 0, source: PlaybackSource? = null, shuffle: Boolean = false)
    fun togglePlayPause()
    fun next()
    fun previous()
    fun seekTo(position: Duration)
    fun setShuffle(enabled: Boolean)
    fun cycleRepeat()
    fun skipTo(occurrenceId: Long)
    fun playNext(song: Song)
    fun addToQueue(song: Song)
    /** Moves an upcoming entry by [offset] positions within the upcoming part of the queue. */
    fun moveUpcoming(occurrenceId: Long, offset: Int)
    fun remove(occurrenceId: Long)
    fun clearQueue()
}
