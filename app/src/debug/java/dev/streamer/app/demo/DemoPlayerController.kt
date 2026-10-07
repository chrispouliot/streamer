package dev.streamer.app.demo

import dev.streamer.app.model.Song
import dev.streamer.app.playback.PlaybackSource
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.playback.PlayerState
import dev.streamer.app.playback.QueueItem
import dev.streamer.app.playback.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Silent in-memory player for debug builds: advances a clock instead of
 * playing audio. Replaced by the Media3 controller in Phase 3.
 */
class DemoPlayerController(
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val tick: Duration = 250.milliseconds,
) : PlayerController {
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var nextOccurrenceId = 1L
    /** Occurrence IDs in un-shuffled order, used to restore order when shuffle is turned off. */
    private var originalOrder: List<Long> = emptyList()
    private var clock: Job? = null

    override fun play(songs: List<Song>, startIndex: Int, source: PlaybackSource?, shuffle: Boolean, sourcePositions: List<Int>?) {
        if (songs.isEmpty()) return
        val items = songs.mapIndexed { i, song -> QueueItem(nextOccurrenceId++, song, sourceIndex = sourcePositions?.getOrNull(i) ?: i) }
        originalOrder = items.map { it.occurrenceId }
        val start = if (shuffle && startIndex == 0) random.nextInt(items.size) else startIndex.coerceIn(items.indices)
        val queue = if (shuffle) {
            listOf(items[start]) + (items - items[start]).shuffled(random)
        } else {
            items
        }
        _state.update {
            it.copy(
                queue = queue,
                currentIndex = if (shuffle) 0 else start,
                isPlaying = true,
                position = Duration.ZERO,
                duration = queue[if (shuffle) 0 else start].song.duration,
                shuffle = shuffle,
                source = source,
                error = null,
            )
        }
        syncClock()
    }

    override fun togglePlayPause() {
        if (!_state.value.hasQueue) return
        _state.update { it.copy(isPlaying = !it.isPlaying) }
        syncClock()
    }

    override fun next() {
        val s = _state.value
        when {
            s.currentIndex < s.queue.lastIndex -> moveTo(s.currentIndex + 1)
            s.repeat != RepeatMode.Off && s.hasQueue -> moveTo(0)
        }
    }

    override fun previous() {
        val s = _state.value
        when {
            s.position > RESTART_THRESHOLD -> seekTo(Duration.ZERO)
            s.currentIndex > 0 -> moveTo(s.currentIndex - 1)
            s.repeat != RepeatMode.Off && s.hasQueue -> moveTo(s.queue.lastIndex)
            else -> seekTo(Duration.ZERO)
        }
    }

    override fun seekTo(position: Duration) {
        _state.update { s ->
            val max = s.duration ?: position
            s.copy(position = position.coerceIn(Duration.ZERO, max))
        }
    }

    override fun setShuffle(enabled: Boolean) {
        _state.update { s ->
            if (s.shuffle == enabled) return@update s
            val current = s.current
            if (current == null) return@update s.copy(shuffle = enabled)
            val queue = if (enabled) {
                s.queue.take(s.currentIndex + 1) + s.upNext.shuffled(random)
            } else {
                val rank = originalOrder.withIndex().associate { (i, id) -> id to i }
                s.queue.sortedBy { rank[it.occurrenceId] ?: Int.MAX_VALUE }
            }
            s.copy(queue = queue, currentIndex = queue.indexOf(current), shuffle = enabled)
        }
    }

    override fun cycleRepeat() {
        _state.update {
            it.copy(
                repeat = when (it.repeat) {
                    RepeatMode.Off -> RepeatMode.All
                    RepeatMode.All -> RepeatMode.One
                    RepeatMode.One -> RepeatMode.Off
                },
            )
        }
    }

    override fun skipTo(occurrenceId: Long) {
        val index = _state.value.queue.indexOfFirst { it.occurrenceId == occurrenceId }
        if (index >= 0) moveTo(index, play = true)
    }

    override fun playNext(song: Song) {
        val s = _state.value
        if (!s.hasQueue) return play(listOf(song))
        val item = QueueItem(nextOccurrenceId++, song)
        insertOriginal(item.occurrenceId, after = s.current?.occurrenceId)
        _state.update {
            it.copy(queue = it.queue.toMutableList().apply { add(it.currentIndex + 1, item) })
        }
    }

    override fun addToQueue(song: Song) {
        if (!_state.value.hasQueue) return play(listOf(song))
        val item = QueueItem(nextOccurrenceId++, song)
        originalOrder = originalOrder + item.occurrenceId
        _state.update { it.copy(queue = it.queue + item) }
    }

    override fun moveUpcoming(occurrenceId: Long, offset: Int) {
        _state.update { s ->
            val from = s.queue.indexOfFirst { it.occurrenceId == occurrenceId }
            if (from <= s.currentIndex) return@update s
            val to = (from + offset).coerceIn(s.currentIndex + 1, s.queue.lastIndex)
            if (to == from) return@update s
            val queue = s.queue.toMutableList().apply { add(to, removeAt(from)) }
            // A manual reorder becomes the new un-shuffled order for these items.
            if (!s.shuffle) originalOrder = queue.map { it.occurrenceId }
            s.copy(queue = queue)
        }
    }

    override fun remove(occurrenceId: Long) {
        _state.update { s ->
            val index = s.queue.indexOfFirst { it.occurrenceId == occurrenceId }
            // The current entry is changed with next/previous, not removed in place.
            if (index < 0 || index == s.currentIndex) return@update s
            originalOrder = originalOrder - occurrenceId
            s.copy(
                queue = s.queue.filterNot { it.occurrenceId == occurrenceId },
                currentIndex = if (index < s.currentIndex) s.currentIndex - 1 else s.currentIndex,
            )
        }
    }

    override fun clearQueue() {
        originalOrder = emptyList()
        _state.update { PlayerState(shuffle = it.shuffle, repeat = it.repeat) }
        syncClock()
    }

    private fun moveTo(index: Int, play: Boolean = _state.value.isPlaying) {
        _state.update {
            it.copy(
                currentIndex = index,
                position = Duration.ZERO,
                duration = it.queue[index].song.duration,
                isPlaying = play,
            )
        }
        syncClock()
    }

    private fun insertOriginal(id: Long, after: Long?) {
        val at = originalOrder.indexOf(after)
        originalOrder = originalOrder.toMutableList().apply { add(if (at < 0) size else at + 1, id) }
    }

    private fun onTrackEnded() {
        val s = _state.value
        when {
            s.repeat == RepeatMode.One -> seekTo(Duration.ZERO)
            s.currentIndex < s.queue.lastIndex -> moveTo(s.currentIndex + 1)
            s.repeat == RepeatMode.All -> moveTo(0)
            else -> {
                _state.update { it.copy(isPlaying = false, position = Duration.ZERO) }
                syncClock()
            }
        }
    }

    private fun syncClock() {
        val playing = _state.value.isPlaying
        if (playing && clock?.isActive != true) {
            clock = scope.launch {
                while (isActive) {
                    delay(tick)
                    val s = _state.value
                    val duration = s.duration ?: DEFAULT_DURATION
                    val position = s.position + tick
                    if (position >= duration) onTrackEnded() else _state.update { it.copy(position = position) }
                }
            }
        } else if (!playing) {
            clock?.cancel()
            clock = null
        }
    }

    private companion object {
        val RESTART_THRESHOLD = 3.seconds
        val DEFAULT_DURATION = 3.minutes
    }
}
