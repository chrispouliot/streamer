package dev.streamer.app

import dev.streamer.app.model.Song
import dev.streamer.app.playback.PlaybackSource
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.playback.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

// Release builds have no player until the Media3 implementation (Phase 3);
// the simulated debug player is never shipped.
internal fun createPlayerController(scope: CoroutineScope): PlayerController = IdlePlayerController

private object IdlePlayerController : PlayerController {
    override val state: StateFlow<PlayerState> = MutableStateFlow(PlayerState())
    override fun play(songs: List<Song>, startIndex: Int, source: PlaybackSource?, shuffle: Boolean, sourcePositions: List<Int>?) = Unit
    override fun togglePlayPause() = Unit
    override fun next() = Unit
    override fun previous() = Unit
    override fun seekTo(position: Duration) = Unit
    override fun setShuffle(enabled: Boolean) = Unit
    override fun cycleRepeat() = Unit
    override fun skipTo(occurrenceId: Long) = Unit
    override fun playNext(song: Song) = Unit
    override fun addToQueue(song: Song) = Unit
    override fun moveUpcoming(occurrenceId: Long, offset: Int) = Unit
    override fun remove(occurrenceId: Long) = Unit
    override fun clearQueue() = Unit
}
