package dev.streamer.app

import dev.streamer.app.demo.DemoPlayerController
import dev.streamer.app.playback.PlayerController
import kotlinx.coroutines.CoroutineScope

// Until the Media3 player exists (Phase 3), debug builds use a silent
// simulated player so playback UI can be exercised with real library data.
internal fun createPlayerController(scope: CoroutineScope): PlayerController = DemoPlayerController(scope)
